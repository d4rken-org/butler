package eu.darken.butler.viewer.core.operations

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
import eu.darken.butler.common.files.actions.PathActionIssue
import eu.darken.butler.workspace.core.Workspace
import eu.darken.butler.workspace.core.filesystem.FileSystemHinter
import eu.darken.butler.workspace.core.operations.CoreDeleteExecutor
import eu.darken.butler.workspace.core.operations.IssueHandler
import eu.darken.butler.workspace.core.operations.Operation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.onEach
import kotlin.time.Clock

/**
 * Deletes the single file a viewer tab is showing. The viewer never deletes more than one path, so
 * the description skips the plural wording the Explorer and Searcher variants need.
 */
class DeleteOperation @AssistedInject constructor(
    @Assisted workspaceId: Workspace.Id,
    @Assisted private val command: ViewerCommand.Delete,
    private val issueHandler: IssueHandler,
    private val coreDeleteExecutor: CoreDeleteExecutor,
    private val fileSystemHinter: FileSystemHinter,
) : ViewerOperation() {

    private val tag = logTag("Viewer", "Workspace", workspaceId.shortTag, "Operation", "Delete")

    override val metadata: Operation.Metadata = object : Operation.Metadata {
        override val origin = Operation.Metadata.Origin.Viewer(workspaceId)
        override val icon: ImageVector = Icons.TwoTone.Delete
        override val title = eu.darken.butler.workspace.R.string.workspace_operation_delete_title.toCaString()
        override val description = caString { cx ->
            val target = command.targets.first()
            cx.getString(
                eu.darken.butler.workspace.R.string.workspace_operation_delete_description_single,
                target.name,
                target.parent?.userReadablePath?.get(cx) ?: target.userReadablePath.get(cx),
            )
        }
        override val kind = Operation.Metadata.Kind.DELETE
        override val intendedPaths = command.targets
    }

    override fun perform(
        operationContext: Operation.Context,
    ): Flow<State> = channelFlow {
        log(tag) { "perform(): $command" }

        var stateActive = State.Active(startedAt = operationContext.startedAt)
        send(stateActive)

        val reportBuilder = DeleteOperationReport.Builder()

        coreDeleteExecutor.execute(
            targets = command.targets,
            config = CoreDeleteExecutor.Config(
                tag = tag,
                forcePermDelete = command.options.forcePermDelete,
                onIssue = { issue ->
                    send(
                        State.Waiting(
                            startedAt = operationContext.startedAt,
                            waitingSince = Clock.System.now(),
                            issue = issue,
                        )
                    )
                    val resolution =
                        issueHandler.handleIssue(operationContext.id, issue) as PathActionIssue.Resolution
                    send(stateActive)
                    resolution
                },
                onPathsRemoved = { deletedPaths ->
                    fileSystemHinter.trackPathsRemoved(operationContext.id, deletedPaths)
                },
            )
        )
            .onEach { coreState ->
                when (coreState) {
                    is CoreDeleteExecutor.State.Active -> {
                        stateActive = stateActive.copy(
                            primaryProgress = coreState.primaryProgress,
                            secondaryProgress = coreState.secondaryProgress,
                            performanceHistory = coreState.performanceHistory,
                        )
                        send(stateActive)
                    }

                    is CoreDeleteExecutor.State.Waiting -> {
                        // Already handled in the onIssue callback
                    }

                    is CoreDeleteExecutor.State.Completed -> {
                        val result = coreState.result
                        reportBuilder.setTrashed(result.trashed)
                        reportBuilder.setDeletions(result.deleted)
                        reportBuilder.setSkipped(result.skipped)
                        reportBuilder.setBytesFreed(result.bytesFreed)
                        reportBuilder.setPerformanceHistory(result.performanceHistory)

                        send(
                            State.Completed(
                                startedAt = operationContext.startedAt,
                                report = reportBuilder.build(),
                            )
                        )
                    }
                }
            }
            .collect {}
    }

    @AssistedFactory
    interface Factory {
        fun create(
            workspaceId: Workspace.Id,
            command: ViewerCommand.Delete,
        ): DeleteOperation
    }
}
