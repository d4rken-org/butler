package eu.darken.butler.explorer.core.operations.handlers

import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import eu.darken.butler.common.ca.caString
import eu.darken.butler.common.coroutine.DispatcherProvider
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.debug.logging.logTag
import eu.darken.butler.common.files.GatewaySwitch
import eu.darken.butler.common.files.extensions.move
import eu.darken.butler.common.files.operations.MoveOperation
import eu.darken.butler.common.progress.Progress
import eu.darken.butler.explorer.core.engine.ExplorerOperation
import eu.darken.butler.explorer.core.operations.IssueHandler
import eu.darken.butler.explorer.core.operations.OperationContext
import eu.darken.butler.explorer.core.operations.OperationResult
import eu.darken.butler.explorer.core.operations.OperationState
import eu.darken.butler.workspace.core.Workspace
import kotlinx.coroutines.flow.last
import kotlinx.coroutines.flow.onEach

class MoveOperationHandler @AssistedInject constructor(
    @Assisted workspaceId: Workspace.Id,
    @Assisted private val issueHandler: IssueHandler,
    gatewaySwitch: GatewaySwitch,
    dispatcherProvider: DispatcherProvider,
) : BaseOperationHandler<ExplorerOperation.FileOp.Move>(
    workspaceId,
    gatewaySwitch,
    dispatcherProvider,
) {

    private val tag = logTag("Explorer", "Workspace", workspaceId.shortTag, "Operation", "Move")

    override suspend fun execute(
        context: OperationContext,
        operation: ExplorerOperation.FileOp.Move,
    ): OperationResult.Success = with(context) {
        log(tag) { "execute(): $operation" }

        val operationState = OperationState.OnGoing(
            operationId = operationId,
            startedAt = startedAt,
        )

        operation.sources.move(
            gateway = gatewaySwitch,
            destination = operation.destination,
            options = MoveOperation.Options(
                onIssue = { issue -> issueHandler.handleIssue(context, issue) }
            )
        )
            .onEach { moveState ->
                when (moveState) {
                    is MoveOperation.State.Progress<*> -> {
                        reportBuilder.updateBytesProcessed(moveState.bytesMoved)

                        emit(
                            operationState.copy(
                                actionProgress = Progress.Data(
                                    count = Progress.Count.Size(
                                        current = moveState.bytesMoved,
                                        max = moveState.totalBytes,
                                    )
                                ),
                                bytesProcessed = moveState.bytesMoved,
                            )
                        )
                    }
                    is MoveOperation.State.Result<*> -> {
                        trackPathsRemoved(moveState.movedFiles.map { it.first })
                        trackPathsAdded(moveState.movedFiles.map { it.second })
                    }
                }
            }
            .last()

        OperationResult.Success(
            summary = caString { "Moved ${operation.sources.size} files" }  // TODO localize
        )
    }

    @AssistedFactory
    interface Factory {
        fun create(
            workspaceId: Workspace.Id,
            issueHandler: IssueHandler,
        ): MoveOperationHandler
    }
}