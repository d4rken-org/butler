package eu.darken.butler.explorer.core.operations.handlers

import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import eu.darken.butler.common.ca.caString
import eu.darken.butler.common.coroutine.DispatcherProvider
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.debug.logging.logTag
import eu.darken.butler.common.files.GatewaySwitch
import eu.darken.butler.common.files.extensions.copy
import eu.darken.butler.common.files.operations.CopyOperation
import eu.darken.butler.common.progress.Progress
import eu.darken.butler.explorer.core.engine.ExplorerOperation
import eu.darken.butler.explorer.core.operations.IssueHandler
import eu.darken.butler.explorer.core.operations.OperationContext
import eu.darken.butler.explorer.core.operations.OperationResult
import eu.darken.butler.explorer.core.operations.OperationState
import eu.darken.butler.workspace.core.Workspace
import kotlinx.coroutines.flow.last
import kotlinx.coroutines.flow.onEach

class CopyOperationHandler @AssistedInject constructor(
    @Assisted workspaceId: Workspace.Id,
    @Assisted private val issueHandler: IssueHandler,
    gatewaySwitch: GatewaySwitch,
    dispatcherProvider: DispatcherProvider,
) : BaseOperationHandler<ExplorerOperation.FileOp.Copy>(
    workspaceId,
    gatewaySwitch,
    dispatcherProvider,
) {
    private val tag = logTag("Explorer", "Workspace", workspaceId.shortTag, "Operation", "Copy")

    override suspend fun execute(
        context: OperationContext,
        operation: ExplorerOperation.FileOp.Copy,
    ): OperationResult.Success = with(context) {
        log(tag) { "execute(): $operation" }

        val operationState = OperationState.OnGoing(
            operationId = operationId,
            startedAt = startedAt,
        )

        operation.sources.copy(
            gateway = gatewaySwitch,
            destination = operation.destination,
            options = CopyOperation.Options(
                onIssue = { issue -> issueHandler.handleIssue(context, issue) }
            )
        )
            .onEach { copyState ->
                when (copyState) {
                    is CopyOperation.State.Progress<*> -> {
                        reportBuilder.updateBytesProcessed(copyState.bytesCopied)

                        emit(
                            operationState.copy(
                                actionProgress = Progress.Data(
                                    count = Progress.Count.Size(
                                        current = copyState.bytesCopied,
                                        max = copyState.totalBytes,
                                    )
                                ),
                                bytesProcessed = copyState.bytesCopied,
                            )
                        )
                    }
                    is CopyOperation.State.Result<*> -> {
                        trackPathsAdded(copyState.copiedFiles.map { it.second })
                    }
                }
            }
            .last()

        OperationResult.Success(
            summary = caString { "Copied ${operation.sources.size} files" }  // TODO localize
        )
    }


    @AssistedFactory
    interface Factory {
        fun create(
            workspaceId: Workspace.Id,
            issueHandler: IssueHandler,
        ): CopyOperationHandler
    }
}