package eu.darken.butler.searcher.core.operations

import android.text.format.Formatter
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.twotone.Delete
import androidx.compose.ui.graphics.vector.ImageVector
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import eu.darken.butler.common.ca.caString
import eu.darken.butler.common.ca.toCaString
import eu.darken.butler.common.datastore.value
import eu.darken.butler.common.debug.logging.Logging.Priority.*
import eu.darken.butler.common.debug.logging.asLog
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.debug.logging.logTag
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
import eu.darken.butler.common.recyclebin.RecycleBinManager
import eu.darken.butler.common.recyclebin.RecycleBinSettings
import eu.darken.butler.workspace.core.Workspace
import eu.darken.butler.workspace.core.operations.IssueHandler
import eu.darken.butler.workspace.core.operations.Operation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.last
import kotlinx.coroutines.flow.onEach
import kotlin.time.Clock
import kotlin.time.Instant
import kotlin.time.TimeSource

class DeleteOperation @AssistedInject constructor(
    @Assisted workspaceId: Workspace.Id,
    @Assisted private val command: SearcherCommand.Delete,
    private val issueHandler: IssueHandler,
    private val gatewaySwitch: GatewaySwitch,
    private val recycleBinManager: RecycleBinManager,
    private val recycleBinSettings: RecycleBinSettings,
) : SearcherOperation() {

    private val tag = logTag("Searcher", "Workspace", workspaceId.shortTag, "Operation", "Delete")

    override val metadata: Operation.Metadata = object : Operation.Metadata {
        override val origin = Operation.Metadata.Origin.Searcher(workspaceId)
        override val icon: ImageVector = Icons.TwoTone.Delete
        override val title = eu.darken.butler.workspace.R.string.workspace_operation_delete_title.toCaString()
        override val description = caString { cx ->
            if (command.targets.size == 1) {
                val target = command.targets.first()
                cx.getString(
                    eu.darken.butler.workspace.R.string.workspace_operation_delete_description_single,
                    target.name,
                    target.parent?.userReadablePath?.get(cx) ?: target.userReadablePath.get(cx)
                )
            } else {
                cx.getQuantityString2(
                    eu.darken.butler.workspace.R.plurals.workspace_operation_delete_description,
                    command.targets.size,
                    command.targets.size,
                    command.targets.first().let { it.parent?.userReadablePath?.get(cx) ?: it.userReadablePath.get(cx) }
                )
            }
        }
    }

    override fun perform(
        operationContext: Operation.Context
    ): Flow<State> = flow {
        log(tag) { "perform(): $command" }

        var stateActive = State.Active(startedAt = operationContext.startedAt)
        emit(stateActive)

        val reportBuilder = DeleteOperationReport.Builder()
        var lastPerformanceHistory: PerformanceHistory? = null

        // Check if recycle bin is enabled and paths are supported
        val recycleBinEnabled = recycleBinSettings.enabled.value()
        val supportsRecycleBin = command.targets.all { it is LocalPath }

        if (recycleBinEnabled && supportsRecycleBin) {
            log(tag, INFO) { "Attempting to move ${command.targets.size} items to recycle bin" }

            try {
                // Try to move to recycle bin
                val recycleBinReport = recycleBinManager.moveToRecycleBin(
                    paths = command.targets.toList()
                )

                if (recycleBinReport.failedToMove.isNotEmpty()) {
                    log(
                        tag,
                        WARN
                    ) { "${recycleBinReport.failedToMove.size} items failed to move to recycle bin, will perform direct delete" }
                    // Continue to direct deletion for failed items below
                } else {
                    // All items moved to recycle bin successfully
                    log(
                        tag,
                        INFO
                    ) { "Successfully moved ${recycleBinReport.movedToRecycleBin.size} items to recycle bin" }

                    @Suppress("UNCHECKED_CAST")
                    reportBuilder.setDeletions(recycleBinReport.movedToRecycleBin as Set<APathLookup<APath<*>>>)
                    reportBuilder.setBytesFreed(recycleBinReport.bytesMoved)

                    emit(
                        State.Completed(
                            startedAt = operationContext.startedAt,
                            report = reportBuilder.build()
                        )
                    )
                    return@flow // Early exit - all done via recycle bin
                }
            } catch (e: Exception) {
                log(tag, WARN) { "Recycle bin move failed: ${e.asLog()}, falling back to direct delete" }
                // Continue to direct deletion below
            }
        } else {
            if (recycleBinEnabled && !supportsRecycleBin) {
                log(
                    tag,
                    INFO
                ) { "Recycle bin enabled but paths not supported (non-LocalPath), performing direct delete" }
            } else {
                log(tag) { "Recycle bin disabled, performing direct delete" }
            }
        }

        // Direct deletion (if recycle bin failed or disabled)
        data class SpeedSample(
            val timestamp: Instant,
            val itemsPerSecond: Long,
            val bytesPerSecond: Long
        )

        val speedHistory = ArrayDeque<SpeedSample>(30) // 30 samples max
        var lastItemsProcessed = 0L
        var lastBytesDeleted = 0L
        var lastSpeedUpdate = TimeSource.Monotonic.markNow()

        command.targets
            .delete(
                gateway = gatewaySwitch,
                options = DeleteAction.Options(
                    recursive = true,
                    onIssue = { issue ->
                        emit(
                            State.Waiting(
                                startedAt = operationContext.startedAt,
                                waitingSince = Clock.System.now(),
                                issue = issue,
                            )
                        )
                        val resolution =
                            issueHandler.handleIssue(operationContext.id, issue) as PathActionIssue.Resolution
                        emit(stateActive)
                        resolution
                    }
                )
            )
            .onEach { deleteState ->
                when (deleteState) {
                    is DeleteAction.State.Active<APath<*>, APathLookup<APath<*>>> -> {
                        val now = Clock.System.now()
                        val elapsed = lastSpeedUpdate.elapsedNow().inWholeMilliseconds / 1000.0

                        // Calculate instantaneous speed (every ~1 second)
                        if (elapsed >= 1.0) {
                            val currentItems = deleteState.primaryProgress.count.current.toLong()
                            val itemsDelta = currentItems - lastItemsProcessed
                            val bytesDelta = deleteState.deletedBytes - lastBytesDeleted
                            val itemsSpeed = (itemsDelta / elapsed).toLong()
                            val bytesSpeed = (bytesDelta / elapsed).toLong()

                            speedHistory.addLast(SpeedSample(now, itemsSpeed, bytesSpeed))
                            if (speedHistory.size > 30) speedHistory.removeFirst()

                            lastItemsProcessed = currentItems
                            lastBytesDeleted = deleteState.deletedBytes
                            lastSpeedUpdate = TimeSource.Monotonic.markNow()
                        }

                        // Calculate overall metrics (from speed history)
                        val avgItemsSpeed = if (speedHistory.isNotEmpty()) {
                            speedHistory.map { it.itemsPerSecond }.average().toLong()
                        } else 0L

                        val avgBytesSpeed = if (speedHistory.isNotEmpty()) {
                            speedHistory.map { it.bytesPerSecond }.average().toLong()
                        } else 0L

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
                            eu.darken.butler.common.progress.Progress.Data(
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

                        // Extract performance history from low-level operation
                        val perfHistory = deleteState.primaryProgress.extra as? PerformanceHistory
                        lastPerformanceHistory = perfHistory

                        stateActive = stateActive.copy(
                            primaryProgress = enhancedPrimary,
                            secondaryProgress = secondaryProgress,
                            performanceHistory = perfHistory,
                        )
                        emit(stateActive)
                    }

                    is DeleteAction.State.Completed<APath<*>, APathLookup<APath<*>>> -> {
                        reportBuilder.setDeletions(deleteState.deleted)
                        reportBuilder.setSkipped(deleteState.skipped)
                        reportBuilder.setBytesFreed(deleteState.deleted.mapNotNull { it.size }.sum())
                    }
                }
            }
            .last()

        reportBuilder.setPerformanceHistory(lastPerformanceHistory)

        emit(
            State.Completed(
                startedAt = operationContext.startedAt,
                report = reportBuilder.build()
            )
        )
    }

    @AssistedFactory
    interface Factory {
        fun create(
            workspaceId: Workspace.Id,
            command: SearcherCommand.Delete,
        ): DeleteOperation
    }
}
