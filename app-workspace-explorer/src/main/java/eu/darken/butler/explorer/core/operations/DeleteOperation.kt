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
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.debug.logging.logTag
import eu.darken.butler.common.files.APathLookup
import eu.darken.butler.common.files.GatewaySwitch
import eu.darken.butler.common.files.actions.DeleteAction
import eu.darken.butler.common.files.actions.PathActionIssue
import eu.darken.butler.common.files.extensions.delete
import eu.darken.butler.common.files.local.operations.core.PerformanceHistory
import eu.darken.butler.common.formatItemSpeed
import eu.darken.butler.common.getQuantityString2
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
                        // Extract performance history from low-level operation
                        val perfHistory = deleteState.primaryProgress.extra as? PerformanceHistory
                        lastPerformanceHistory = perfHistory

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

                        stateActive = stateActive.copy(
                            primaryProgress = enhancedPrimary,
                            secondaryProgress = secondaryProgress,
                            performanceHistory = perfHistory,
                        )
                        emit(stateActive)
                    }
                    is DeleteAction.State.Completed<*, *> -> {
                        @Suppress("UNCHECKED_CAST")
                        val deleted = deleteState.deleted as Set<APathLookup<*>>

                        @Suppress("UNCHECKED_CAST")
                        val skipped = deleteState.skipped as Set<APathLookup<*>>

                        fileSystemHinter.trackPathsRemoved(operationContext.id, deleted)
                        reportBuilder.setDeletions(deleted)
                        reportBuilder.setSkipped(skipped)
                        reportBuilder.setBytesFreed(deleted.mapNotNull { it.size }.sum())
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
            command: ExplorerCommand.Delete,
        ): DeleteOperation
    }
}