package eu.darken.butler.workspace.core.operations

import android.text.format.Formatter
import eu.darken.butler.common.ca.caString
import eu.darken.butler.common.ca.toCaString
import eu.darken.butler.common.datastore.value
import eu.darken.butler.common.debug.logging.Logging.Priority.*
import eu.darken.butler.common.debug.logging.asLog
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.files.APath
import eu.darken.butler.common.files.APathLookup
import eu.darken.butler.common.files.GatewaySwitch
import eu.darken.butler.common.files.LocalPath
import eu.darken.butler.common.files.actions.DeleteAction
import eu.darken.butler.common.files.actions.PathActionIssue
import eu.darken.butler.common.files.extensions.delete
import eu.darken.butler.common.files.local.operations.core.PerformanceHistory
import eu.darken.butler.common.formatItemSpeed
import eu.darken.butler.common.getQuantityString2
import eu.darken.butler.common.progress.Progress
import eu.darken.butler.common.trash.TrashManager
import eu.darken.butler.common.trash.TrashSettings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.last
import kotlinx.coroutines.flow.onEach
import javax.inject.Inject
import kotlin.coroutines.cancellation.CancellationException

class CoreDeleteExecutor @Inject constructor(
    private val gatewaySwitch: GatewaySwitch,
    private val trashManager: TrashManager,
    private val trashSettings: TrashSettings,
) {

    fun execute(
        targets: Set<APath<*>>,
        config: Config,
    ): Flow<State> = flow {
        log(config.tag) { "execute(): Deleting ${targets.size} items" }

        var lastPerformanceHistory: PerformanceHistory? = null

        // Check if trash is enabled and paths are supported
        val trashEnabled = trashSettings.enabled.value()
        val supportsTrash = targets.all { it is LocalPath }

        // Initialize collections for tracking items during trash/delete operations
        val itemsForDirectDelete = mutableListOf<APath<*>>()
        val skippedItems = mutableSetOf<APathLookup<*>>()

        if (trashEnabled && supportsTrash) {
            log(config.tag, INFO) { "Attempting to move ${targets.size} items to trash" }

            // Check for items that exceed trash size limit
            val maxTrashSize = trashSettings.maxTrashSize.value()
            val itemsForTrash = mutableListOf<APath<*>>()
            var applyDeletePermanentlyToAll = false
            var applySkipToAll = false

            for (target in targets) {
                val lookup = gatewaySwitch.lookup(target, eu.darken.butler.common.files.LookupOptions.MAX)
                val itemSize = lookup.size ?: 0L

                if (itemSize > maxTrashSize) {
                    log(config.tag, INFO) { "Item $target ($itemSize bytes) exceeds trash limit ($maxTrashSize bytes)" }

                    // Check if we should apply a previous "apply to all" decision
                    if (applyDeletePermanentlyToAll) {
                        itemsForDirectDelete.add(target)
                        continue
                    }
                    if (applySkipToAll) {
                        skippedItems.add(lookup)
                        continue
                    }

                    // Emit issue and wait for user resolution
                    val issue = PathActionIssue.TrashSizeLimitExceeded(
                        source = lookup,
                        itemSize = itemSize,
                        trashMaxSize = maxTrashSize,
                    )
                    emit(State.Waiting(issue = issue))
                    val resolution = config.onIssue(issue)

                    when (resolution) {
                        is PathActionIssue.TrashSizeLimitExceeded.Resolution.DeletePermanently -> {
                            itemsForDirectDelete.add(target)
                        }
                        is PathActionIssue.TrashSizeLimitExceeded.Resolution.Skip -> {
                            skippedItems.add(lookup)
                            if (resolution.applyToAll) applySkipToAll = true
                        }
                        is PathActionIssue.TrashSizeLimitExceeded.Resolution.Cancel -> {
                            throw CancellationException("User cancelled operation")
                        }
                    }
                } else {
                    itemsForTrash.add(target)
                }
            }

            // Move suitable items to trash
            var trashedItems = emptySet<APathLookup<*>>()
            var trashedBytes = 0L

            if (itemsForTrash.isNotEmpty()) {
                try {
                    val trashReport = trashManager.moveToTrash(paths = itemsForTrash)

                    if (trashReport.failedToMove.isNotEmpty()) {
                        log(config.tag, WARN) {
                            "${trashReport.failedToMove.size} items failed to move to trash, will perform direct delete"
                        }
                        // Add failed items to direct delete list
                        itemsForDirectDelete.addAll(trashReport.failedToMove.map { it.lookedUp })
                    }

                    if (trashReport.movedToTrash.isNotEmpty()) {
                        log(config.tag, INFO) {
                            "Successfully moved ${trashReport.movedToTrash.size} items to trash"
                        }
                        trashedItems = trashReport.movedToTrash
                        trashedBytes = trashReport.bytesMoved

                        @Suppress("UNCHECKED_CAST")
                        config.onPathsRemoved(trashedItems as Set<APathLookup<APath<*>>>)
                    }
                } catch (e: Exception) {
                    log(config.tag, WARN) { "Trash move failed: ${e.asLog()}, falling back to direct delete" }
                    itemsForDirectDelete.addAll(itemsForTrash)
                }
            }

            // If no direct deletes needed and we have trashed items, we're done
            if (itemsForDirectDelete.isEmpty() && trashedItems.isNotEmpty()) {
                @Suppress("UNCHECKED_CAST")
                emit(
                    State.Completed(
                        result = Result(
                            deleted = trashedItems as Set<APathLookup<APath<*>>>,
                            skipped = skippedItems,
                            bytesFreed = trashedBytes,
                            performanceHistory = null,
                        )
                    )
                )
                return@flow
            }

            // If we have items for direct delete, fall through to direct deletion below
            if (itemsForDirectDelete.isNotEmpty()) {
                log(config.tag, INFO) { "Performing direct delete for ${itemsForDirectDelete.size} items" }
            }
        } else {
            if (trashEnabled && !supportsTrash) {
                log(config.tag, INFO) {
                    "Trash enabled but paths not supported (non-LocalPath), performing direct delete"
                }
            } else {
                log(config.tag) { "Trash disabled, performing direct delete" }
            }
        }

        // Direct deletion (if recycle bin failed, disabled, or items too large)
        val targetsForDirectDelete = if (trashEnabled && supportsTrash) {
            // Use itemsForDirectDelete if we went through the trash path
            itemsForDirectDelete.toSet()
        } else {
            targets
        }

        if (targetsForDirectDelete.isEmpty()) {
            // Nothing to delete directly
            emit(
                State.Completed(
                    result = Result(
                        deleted = emptySet(),
                        skipped = emptySet(),
                        bytesFreed = 0L,
                        performanceHistory = null,
                    )
                )
            )
            return@flow
        }

        targetsForDirectDelete
            .delete(
                gateway = gatewaySwitch,
                options = DeleteAction.Options(
                    recursive = true,
                    onIssue = { issue ->
                        emit(State.Waiting(issue = issue))
                        val resolution = config.onIssue(issue)
                        resolution
                    }
                )
            )
            .onEach { deleteState ->
                when (deleteState) {
                    is DeleteAction.State.Active<APath<*>, APathLookup<APath<*>>> -> {
                        val activeState = buildActiveState(deleteState)
                        lastPerformanceHistory = activeState.performanceHistory
                        emit(activeState)
                    }

                    is DeleteAction.State.Completed<APath<*>, APathLookup<APath<*>>> -> {
                        config.onPathsRemoved(deleteState.deleted)
                    }
                }
            }
            .last()
            .let { finalState ->
                @Suppress("UNCHECKED_CAST")
                val completedState = finalState as DeleteAction.State.Completed<APath<*>, APathLookup<APath<*>>>

                emit(
                    State.Completed(
                        result = Result(
                            deleted = completedState.deleted,
                            skipped = completedState.skipped,
                            bytesFreed = completedState.deleted.mapNotNull { it.size }.sum(),
                            performanceHistory = lastPerformanceHistory,
                        )
                    )
                )
            }
    }

    private fun buildActiveState(
        deleteState: DeleteAction.State.Active<APath<*>, APathLookup<APath<*>>>
    ): State.Active {
        // Extract performance history from low-level operation
        val perfHistory = deleteState.primaryProgress.extra as? PerformanceHistory

        // Calculate overall metrics using PerformanceHistory
        val avgItemsSpeed = perfHistory?.getRecentItemsPerSecond()?.toLong() ?: 0L
        val avgBytesSpeed = perfHistory?.getRecentBytesPerSecond() ?: 0L

        val overallEta = if (avgItemsSpeed > 0 && deleteState.primaryProgress.count.max > 0) {
            val remaining =
                deleteState.primaryProgress.count.max - deleteState.primaryProgress.count.current
            (remaining / avgItemsSpeed) // seconds
        } else null

        // Format overall metrics for primary progress
        val overallMetrics = if (avgItemsSpeed > 0 || avgBytesSpeed > 0) {
            caString { ctx ->
                val parts = mutableListOf<String>()
                if (avgItemsSpeed > 0) {
                    parts.add(formatItemSpeed(ctx, avgItemsSpeed.toDouble()))
                }
                if (avgBytesSpeed > 0) {
                    val bytesFormatted = Formatter.formatShortFileSize(ctx, avgBytesSpeed)
                    parts.add(
                        ctx.getString(
                            eu.darken.butler.workspace.R.string.workspace_operation_progress_bytes_speed_freed,
                            bytesFormatted
                        )
                    )
                }
                val speedPart = parts.joinToString(" • ")
                val etaPart = if (overallEta != null) {
                    val duration = ctx.getQuantityString2(
                        eu.darken.butler.common.R.plurals.common_duration_seconds_full,
                        overallEta.toInt(),
                        overallEta
                    )
                    " • " + ctx.getString(
                        eu.darken.butler.workspace.R.string.workspace_operation_progress_time_remaining,
                        duration
                    )
                } else ""
                speedPart + etaPart
            }
        } else null

        // Build enhanced primary progress with overall metrics
        val enhancedPrimary = deleteState.primaryProgress.copy(
            secondary = overallMetrics ?: deleteState.primaryProgress.secondary
        )

        // Build secondary progress with bytes freed info
        val secondaryProgress = if (deleteState.deletedBytes > 0) {
            Progress.Data(
                primary = deleteState.target.lookedUp.name.toCaString(),
                secondary = caString { ctx ->
                    val bytesFormatted = Formatter.formatShortFileSize(ctx, deleteState.deletedBytes)
                    ctx.getString(
                        eu.darken.butler.workspace.R.string.workspace_operation_progress_bytes_freed,
                        bytesFormatted
                    )
                }
            )
        } else null

        return State.Active(
            primaryProgress = enhancedPrimary,
            secondaryProgress = secondaryProgress,
            performanceHistory = perfHistory,
        )
    }

    data class Config(
        val tag: String,
        val onIssue: suspend (PathActionIssue) -> PathActionIssue.Resolution,
        val onPathsRemoved: suspend (Set<APathLookup<*>>) -> Unit = {},
    )

    sealed interface State {
        data class Active(
            val primaryProgress: Progress.Data,
            val secondaryProgress: Progress.Data?,
            val performanceHistory: PerformanceHistory?,
        ) : State

        data class Waiting(
            val issue: PathActionIssue,
        ) : State

        data class Completed(
            val result: Result,
        ) : State
    }

    data class Result(
        val deleted: Set<APathLookup<*>>,
        val skipped: Set<APathLookup<*>>,
        val bytesFreed: Long,
        val performanceHistory: PerformanceHistory?,
    )
}
