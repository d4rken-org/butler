package eu.darken.butler.explorer.core.operations.handlers

import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import eu.darken.butler.common.ca.caString
import eu.darken.butler.common.coroutine.DispatcherProvider
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.debug.logging.logTag
import eu.darken.butler.common.files.GatewaySwitch
import eu.darken.butler.common.files.extensions.delete
import eu.darken.butler.common.files.operations.DeleteOperation
import eu.darken.butler.common.progress.Progress
import eu.darken.butler.explorer.core.engine.ExplorerOperation
import eu.darken.butler.explorer.core.operations.IssueHandler
import eu.darken.butler.explorer.core.operations.OperationContext
import eu.darken.butler.explorer.core.operations.OperationResult
import eu.darken.butler.explorer.core.operations.OperationState
import eu.darken.butler.workspace.core.Workspace
import kotlinx.coroutines.flow.last
import kotlinx.coroutines.flow.onEach

class DeleteOperationHandler @AssistedInject constructor(
    @Assisted workspaceId: Workspace.Id,
    @Assisted private val issueHandler: IssueHandler,
    gatewaySwitch: GatewaySwitch,
    dispatcherProvider: DispatcherProvider,
) : BaseOperationHandler<ExplorerOperation.FileOp.Delete>(
    workspaceId,
    gatewaySwitch,
    dispatcherProvider,
) {

    private val tag = logTag("Explorer", "Workspace", workspaceId.shortTag, "Operation", "Delete")

    override suspend fun execute(
        context: OperationContext,
        operation: ExplorerOperation.FileOp.Delete,
    ): OperationResult.Success = with(context) {
        log(tag) { "execute(): $operation" }

        val operationState = OperationState.OnGoing(
            operationId = operationId,
            startedAt = startedAt,
        )

        operation.targets.delete(
            gateway = gatewaySwitch,
            options = DeleteOperation.Options(
                recursive = true,
                onIssue = { issue -> issueHandler.handleIssue(context, issue) }
            )
        )
            .onEach { deleteState ->
                when (deleteState) {
                    is DeleteOperation.State.Progress<*> -> {
                        reportBuilder.updateBytesProcessed(deleteState.bytesCurrent)

                        emit(
                            operationState.copy(
                                actionProgress = Progress.Data(
                                    count = Progress.Count.Counter(
                                        current = deleteState.pathsCurrent,
                                        max = deleteState.pathsTotal,
                                    )
                                ),
                                bytesProcessed = deleteState.bytesCurrent,
                            )
                        )
                    }
                    is DeleteOperation.State.Result<*> -> {
                        trackPathsRemoved(deleteState.deleted)
                    }
                }
            }
            .last()

        OperationResult.Success(
            summary = caString { "Deleted ${operation.targets.size} files" }  // TODO localize
        )
    }

    @AssistedFactory
    interface Factory {
        fun create(
            workspaceId: Workspace.Id,
            issueHandler: IssueHandler,
        ): DeleteOperationHandler
    }
}