package eu.darken.butler.explorer.core.operations

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
import eu.darken.butler.explorer.core.filesystem.FileSystemHinter
import eu.darken.butler.workspace.core.Workspace
import eu.darken.butler.workspace.core.operations.IssueHandler
import eu.darken.butler.workspace.core.operations.Operation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.last
import kotlinx.coroutines.flow.onEach
import kotlin.time.Clock

class DeleteOperation @AssistedInject constructor(
    @Assisted workspaceId: Workspace.Id,
    @Assisted private val command: ExplorerCommand.Delete,
    private val issueHandler: IssueHandler,
    private val gatewaySwitch: GatewaySwitch,
    private val fileSystemHinter: FileSystemHinter,
    private val recycleBinManager: RecycleBinManager,
    private val recycleBinSettings: RecycleBinSettings,
) : ExplorerOperation() {

    private val tag = logTag("Explorer", "Workspace", workspaceId.shortTag, "Operation", "Delete")

    override val metadata: Operation.Metadata = object : Operation.Metadata {
        override val origin = Operation.Metadata.Origin.Explorer(workspaceId)
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
            log(tag) { "Attempting to move ${command.targets.size} items to recycle bin" }

            try {
                // Try to move to recycle bin - convert Set to List
                val recycleBinReport = recycleBinManager.moveToRecycleBin(
                    paths = command.targets.toList()
                )

                if (recycleBinReport.failedToMove.isNotEmpty()) {
                    log(tag, WARN) { "${recycleBinReport.failedToMove.size} items failed to move to recycle bin" }

                    // Ask user if they want to delete directly
                    val firstFailedPath = recycleBinReport.failedToMove.first()
                    val issue = PathActionIssue.UnknownError(
                        source = firstFailedPath,
                        exception = Exception("Failed to move items to recycle bin. Delete permanently?"),
                        canSkip = false,
                        canRetry = true,
                    )

                    emit(
                        State.Waiting(
                            startedAt = operationContext.startedAt,
                            waitingSince = Clock.System.now(),
                            issue = issue,
                        )
                    )

                    val resolution = issueHandler.handleIssue(operationContext.id, issue) as PathActionIssue.Resolution
                    emit(stateActive)

                    if (resolution is PathActionIssue.UnknownError.Resolution.Retry) {
                        // Fall back to direct delete for failed items
                        val pathsToDelete = recycleBinReport.failedToMove.map { it.lookedUp }

                        // Perform direct delete
                        pathsToDelete.toSet()
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
                                        val res = issueHandler.handleIssue(
                                            operationContext.id,
                                            issue
                                        ) as PathActionIssue.Resolution
                                        emit(stateActive)
                                        res
                                    }
                                )
                            )
                            .onEach { deleteState ->
                                when (deleteState) {
                                    is DeleteAction.State.Active<*, *> -> {
                                        handleActiveDeleteState(deleteState, stateActive)?.let {
                                            stateActive = it
                                            emit(it)
                                        }
                                    }
                                    is DeleteAction.State.Completed<*, *> -> {
                                        handleCompletedDeleteState(deleteState, operationContext.id, reportBuilder)
                                    }
                                }
                            }
                            .last()
                    }
                }

                // Track successful moves to recycle bin
                if (recycleBinReport.movedToRecycleBin.isNotEmpty()) {
                    fileSystemHinter.trackPathsRemoved(operationContext.id, recycleBinReport.movedToRecycleBin)
                    reportBuilder.setDeletions(recycleBinReport.movedToRecycleBin)
                    reportBuilder.setBytesFreed(recycleBinReport.bytesMoved)
                }

                emit(
                    State.Completed(
                        startedAt = operationContext.startedAt,
                        report = reportBuilder.build()
                    )
                )
            } catch (e: Exception) {
                log(tag, WARN) { "Recycle bin failed, falling back to direct delete: ${e.asLog()}" }

                // Complete fallback to direct delete - just run the normal delete operation
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
                            is DeleteAction.State.Active<*, *> -> {
                                handleActiveDeleteState(deleteState, stateActive)?.let {
                                    stateActive = it
                                    lastPerformanceHistory = it.performanceHistory
                                    emit(it)
                                }
                            }
                            is DeleteAction.State.Completed<*, *> -> {
                                handleCompletedDeleteState(deleteState, operationContext.id, reportBuilder)
                                reportBuilder.setPerformanceHistory(lastPerformanceHistory)
                            }
                        }
                    }
                    .last()

                emit(
                    State.Completed(
                        startedAt = operationContext.startedAt,
                        report = reportBuilder.build()
                    )
                )
            }
        } else {
            // Direct delete (recycle bin disabled or unsupported paths)
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
                        is DeleteAction.State.Active<*, *> -> {
                            val newState = handleActiveDeleteState(deleteState, stateActive)
                            if (newState != null) {
                                stateActive = newState
                                lastPerformanceHistory = newState.performanceHistory
                                emit(newState)
                            }
                        }
                        is DeleteAction.State.Completed<*, *> -> {
                            handleCompletedDeleteState(deleteState, operationContext.id, reportBuilder)
                            reportBuilder.setPerformanceHistory(lastPerformanceHistory)
                        }
                    }
                }
                .last()

            emit(
                State.Completed(
                    startedAt = operationContext.startedAt,
                    report = reportBuilder.build()
                )
            )
        }
    }

    private fun handleActiveDeleteState(
        deleteState: DeleteAction.State.Active<*, *>,
        currentStateActive: State.Active
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
            @Suppress("UNCHECKED_CAST")
            val target = deleteState.target as APathLookup<*>
            eu.darken.butler.common.progress.Progress.Data(
                primary = target.lookedUp.name.toCaString(),
                secondary = caString { ctx ->
                    val bytesFormatted = Formatter.formatShortFileSize(ctx, deleteState.deletedBytes)
                    ctx.getString(
                        eu.darken.butler.workspace.R.string.workspace_operation_progress_bytes_freed,
                        bytesFormatted
                    )
                }
            )
        } else null

        return currentStateActive.copy(
            primaryProgress = enhancedPrimary,
            secondaryProgress = secondaryProgress,
            performanceHistory = perfHistory,
        )
    }

    private suspend fun handleCompletedDeleteState(
        deleteState: DeleteAction.State.Completed<*, *>,
        operationId: Operation.Id,
        reportBuilder: DeleteOperationReport.Builder
    ) {
        @Suppress("UNCHECKED_CAST")
        val deleted = deleteState.deleted as Set<APathLookup<*>>

        @Suppress("UNCHECKED_CAST")
        val skipped = deleteState.skipped as Set<APathLookup<*>>

        fileSystemHinter.trackPathsRemoved(operationId, deleted)
        reportBuilder.setDeletions(deleted)
        reportBuilder.setSkipped(skipped)
        reportBuilder.setBytesFreed(deleted.mapNotNull { it.size }.sum())
    }

    @AssistedFactory
    interface Factory {
        fun create(
            workspaceId: Workspace.Id,
            command: ExplorerCommand.Delete,
        ): DeleteOperation
    }
}