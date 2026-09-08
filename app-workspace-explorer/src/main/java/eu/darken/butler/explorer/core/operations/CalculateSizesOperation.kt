package eu.darken.butler.explorer.core.operations

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.twotone.DataUsage
import androidx.compose.ui.graphics.vector.ImageVector
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import eu.darken.butler.common.ca.CaString
import eu.darken.butler.common.ca.caString
import eu.darken.butler.common.ca.toCaString
import eu.darken.butler.common.coroutine.DispatcherProvider
import eu.darken.butler.common.debug.logging.Logging.Priority.*
import eu.darken.butler.common.debug.logging.asLog
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.debug.logging.logTag
import eu.darken.butler.common.files.APath
import eu.darken.butler.common.files.APathGateway
import eu.darken.butler.common.files.APathLookup
import eu.darken.butler.common.files.GatewaySwitch
import eu.darken.butler.common.files.LookupOptions
import eu.darken.butler.common.files.local.LocalPathLookup
import eu.darken.butler.common.files.local.operations.core.PathOperationProgressTracker
import eu.darken.butler.common.files.local.operations.core.PerformanceHistory
import eu.darken.butler.common.files.metadata.FileType
import eu.darken.butler.common.formatItemSpeed
import eu.darken.butler.common.getQuantityString2
import eu.darken.butler.common.progress.Progress
import eu.darken.butler.common.storage.ExternalStorageStatsProvider
import eu.darken.butler.explorer.R
import eu.darken.butler.explorer.core.sizes.DirectorySizeAggregator
import eu.darken.butler.explorer.core.sizes.DirectorySizeStore
import eu.darken.butler.explorer.core.sizes.AndroidDataEstimate
import eu.darken.butler.explorer.core.sizes.AndroidDataSizeEstimator
import eu.darken.butler.explorer.core.sizes.TopLevelProgress
import eu.darken.butler.workspace.core.Workspace
import eu.darken.butler.workspace.core.operations.Operation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.cancellable
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Clock

class CalculateSizesOperation @AssistedInject constructor(
    @Assisted workspaceId: Workspace.Id,
    @Assisted private val command: ExplorerCommand.CalculateSizes,
    @Assisted store: DirectorySizeStore,
    private val gatewaySwitch: GatewaySwitch,
    private val dispatcherProvider: DispatcherProvider,
    private val clock: Clock,
    private val externalStorageStats: ExternalStorageStatsProvider,
) : ExplorerOperation() {

    private val tag = logTag("Explorer", "Workspace", workspaceId.shortTag, "Operation", "CalculateSizes")

    /** Dropped once this operation terminates so a retained receipt can't keep the tab's sizes alive. */
    private var resultStore: DirectorySizeStore? = store

    override val metadata: Operation.Metadata = object : Operation.Metadata {
        override val origin = Operation.Metadata.Origin.Explorer(workspaceId)
        override val icon: ImageVector = Icons.TwoTone.DataUsage
        override val title = R.string.explorer_operation_calculate_sizes_title.toCaString()
        override val description = caString {
            it.getString(
                R.string.explorer_operation_calculate_sizes_description,
                command.directory.userReadablePath.get(it),
            )
        }
    }

    override fun perform(
        operationContext: Operation.Context
    ): Flow<State> = channelFlow {
        log(tag) { "perform(): $command" }
        val root = command.directory

        // The only cheap total available: how many of the root's own children the walk has behind it.
        val children: Map<String, APath<*>>? = try {
            gatewaySwitch.listFiles(root).associateBy { it.path }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log(tag, WARN) { "Failed to list children of $root: ${e.asLog()}" }
            null
        }
        val topLevel = children?.let { TopLevelProgress(root.path, it.keys) }

        val estimateTarget = try {
            externalStorageStats.prepare(root)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log(tag, WARN) { "Storage estimate unavailable for $root: ${e.asLog()}" }
            null
        }
        val estimator = estimateTarget?.let { AndroidDataSizeEstimator(it.root) }
        val lookupProjection = LOOKUP_PROJECTION.copy(fetchAllocatedSize = estimator != null)
        if (estimator != null) {
            try {
                val rootLookup = withContext(dispatcherProvider.IO) {
                    gatewaySwitch.lookup(checkNotNull(estimateTarget).canonicalRoot, lookupProjection)
                }
                estimator.onEntry((rootLookup as LocalPathLookup).copy(lookedUp = estimateTarget.root))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                estimator.onError(root)
                log(tag, WARN) { "Cannot measure root allocation for $root: ${e.asLog()}" }
            }
        }
        val aggregator = DirectorySizeAggregator(root, estimator)
        val tracker = PathOperationProgressTracker(clock = clock)

        // The directory the walk is inside or about to enter, never the scan root itself.
        var currentDir: APath<*>? = null

        fun activeState() = State.Active(
            startedAt = operationContext.startedAt,
            primaryProgress = Progress.Data(
                primary = metadata.title,
                secondary = scannedLabel(
                    itemCount = aggregator.itemCount,
                    itemsPerSecond = tracker.performanceHistory.getRecentItemsPerSecond(),
                ),
                count = if (topLevel != null && topLevel.total > 0) {
                    Progress.Count.Counter(topLevel.done, topLevel.total)
                } else {
                    Progress.Count.Indeterminate()
                },
            ),
            secondaryProgress = currentDir?.let {
                Progress.Data(
                    primary = relativeLabel(it),
                    secondary = CaString.EMPTY,
                    count = Progress.Count.None(),
                )
            },
            performanceHistory = tracker.performanceHistory.copy(totalItems = tracker.itemsProcessed),
        )

        send(activeState())

        // The walker invokes onError from its own context while entries are consumed in ours, so
        // failures are queued and folded in by the collector instead of racing it.
        val errors = ConcurrentLinkedQueue<Pair<APathLookup<*>, String?>>()

        val gateway = gatewaySwitch.getGateway(root)

        @Suppress("UNCHECKED_CAST")
        val typedGateway = gateway as APathGateway<APath<*>, APathLookup<APath<*>>>

        // No onFilter: every directory is traversed anyway, and its absence keeps escalated
        // subtrees on the host-side streaming walk instead of per-directory IPC.
        val walkOptions = APathGateway.WalkOptions<APath<*>, APathLookup<APath<*>>>(
            onError = { lookup, error ->
                log(tag, VERBOSE) { "Error accessing ${lookup.lookedUp}: $error" }
                errors.add(lookup to (error.message ?: lookup.error))
                true
            },
        )

        typedGateway.walk(root, lookupProjection, walkOptions)
            .cancellable()
            .flowOn(dispatcherProvider.IO)
            .collect { lookup ->
                val drained = aggregator.drain(errors, topLevel)
                aggregator.onEntry(lookup)
                topLevel?.onSeen(lookup.path)
                // A directory entry is the next one descended into, anything else names its parent.
                currentDir = when {
                    lookup.fileType == FileType.DIRECTORY -> lookup.lookedUp
                    else -> lookup.parent ?: lookup.lookedUp
                }
                currentDir = drained ?: currentDir
                tracker.completeItem()
                // Compaction buckets samples by this total, without it a long scan drops its start.
                tracker.totalItems = tracker.itemsProcessed
                if (tracker.shouldReportProgress()) send(activeState())
            }
        currentDir = aggregator.drain(errors, topLevel) ?: currentDir
        topLevel?.finish()
        tracker.totalItems = tracker.itemsProcessed
        tracker.shouldReportProgress(force = true)
        send(activeState())

        var scan = aggregator.result(clock.now())
        if (estimator?.dataIncomplete == true && estimateTarget != null) {
            scan = if (!estimator.canEstimate) {
                scan.copy(estimateFailure = AndroidDataEstimate.Failure.INCOMPLETE_COVERAGE)
            } else try {
                estimator.apply(scan, estimateTarget, externalStorageStats.query(estimateTarget))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log(tag, WARN) { "Failed to estimate Android/data: ${e.asLog()}" }
                scan.copy(estimateFailure = AndroidDataEstimate.Failure.STATISTICS_UNAVAILABLE)
            }
        }
        currentCoroutineContext().ensureActive()
        log(tag, INFO) { "Scanned $root: ${scan.sizes.size} folders, ${scan.errorCount} errors" }
        val stored = checkNotNull(resultStore).publish(scan)

        send(
            State.Completed(
                startedAt = operationContext.startedAt,
                report = Report(
                    root = root,
                    directoryCount = scan.sizes.size,
                    itemCount = scan.itemCount,
                    errorCount = scan.errorCount,
                    problems = scan.problems.map { Operation.Report.Problem(it.path, it.message) },
                    wasDiscarded = !stored,
                    performanceHistory = tracker.performanceHistory.copy(totalItems = scan.itemCount.toInt()),
                ),
            )
        )
    }

    override fun onDiscarded() {
        resultStore = null
    }

    private fun scannedLabel(itemCount: Long, itemsPerSecond: Float): CaString = caString {
        val scanned = it.getQuantityString2(R.plurals.explorer_operation_calculate_sizes_progress, itemCount.toInt())
        if (itemsPerSecond > 0f) {
            "$scanned • ${formatItemSpeed(it, itemsPerSecond.toDouble())}"
        } else {
            scanned
        }
    }

    /** `/storage/emulated/0` scanned, `/storage/emulated/0/Android/data` -> `Android/data`. */
    private fun relativeLabel(dir: APath<*>): CaString {
        val root = command.directory
        if (dir.path == root.path) return root.userReadableName
        val prefix = if (root.path == "/") "/" else "${root.path}/"
        return if (dir.path.startsWith(prefix)) {
            dir.path.removePrefix(prefix).toCaString()
        } else {
            dir.userReadablePath
        }
    }

    /** @return the last failed location that was drained, null when the queue was empty. */
    private fun DirectorySizeAggregator.drain(
        errors: ConcurrentLinkedQueue<Pair<APathLookup<*>, String?>>,
        topLevel: TopLevelProgress?,
    ): APath<*>? {
        var lastFailed: APath<*>? = null
        while (true) {
            val (lookup, message) = errors.poll() ?: break
            onError(lookup, message)
            topLevel?.onSeen(lookup.path)
            lastFailed = lookup.lookedUp
        }
        return lastFailed
    }

    data class Report(
        val root: APath<*>,
        val directoryCount: Int,
        val itemCount: Long,
        val errorCount: Int,
        override val problems: List<Operation.Report.Problem>,
        val wasDiscarded: Boolean,
        override val performanceHistory: PerformanceHistory? = null,
    ) : ExplorerOperation.Report {

        override val summary: CaString = caString {
            if (wasDiscarded) {
                it.getString(
                    R.string.explorer_operation_calculate_sizes_summary_discarded,
                    root.userReadablePath.get(it),
                )
            } else if (errorCount == 0) {
                it.getString(R.string.explorer_operation_calculate_sizes_summary, directoryCount)
            } else {
                it.getQuantityString2(
                    R.plurals.explorer_operation_calculate_sizes_summary_partial,
                    errorCount,
                    directoryCount,
                    errorCount,
                )
            }
        }
        override val affectedPaths = emptyList<Operation.Report.Paths.PathChange>()
        override val subjectPath: APath<*> = root
        override val partialErrorCount: Int = errorCount
    }

    @AssistedFactory
    interface Factory {
        fun create(
            workspaceId: Workspace.Id,
            command: ExplorerCommand.CalculateSizes,
            store: DirectorySizeStore,
        ): CalculateSizesOperation
    }

    companion object {
        /** Sizes are the point; nothing else the walk could fetch is read. */
        private val LOOKUP_PROJECTION = LookupOptions(
            continueOnError = true,
            fetchSize = true,
        )
    }
}
