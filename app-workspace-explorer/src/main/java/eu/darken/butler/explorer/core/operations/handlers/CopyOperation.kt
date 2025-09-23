package eu.darken.butler.explorer.core.operations.handlers

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.twotone.CopyAll
import androidx.compose.ui.graphics.vector.ImageVector
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import eu.darken.butler.common.ca.caString
import eu.darken.butler.common.coroutine.DispatcherProvider
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.debug.logging.logTag
import eu.darken.butler.common.files.GatewaySwitch
import eu.darken.butler.explorer.core.operations.ExplorerCommand
import eu.darken.butler.explorer.core.operations.ExplorerOperation
import eu.darken.butler.workspace.core.Workspace
import eu.darken.butler.workspace.core.operations.IssueHandler
import eu.darken.butler.workspace.core.operations.Operation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

class CopyOperation @AssistedInject constructor(
    @Assisted workspaceId: Workspace.Id,
    @Assisted private val command: ExplorerCommand.Copy,
    private val issueHandler: IssueHandler,
    gatewaySwitch: GatewaySwitch,
    dispatcherProvider: DispatcherProvider,
) : ExplorerOperation() {

    private val tag = logTag("Explorer", "Workspace", workspaceId.shortTag, "Operation", "Copy")

    override val metadata: Operation.Metadata = object : Operation.Metadata {
        override val origin = Operation.Metadata.Origin.Explorer(workspaceId)
        override val icon: ImageVector = Icons.TwoTone.CopyAll
        override val title = caString { "Copy" } // TODO
        override val description = caString { "Copy selected files" } // TODO
    }

    override suspend fun execute(
        operationContext: Operation.Context
    ): Flow<State> = flow {
        log(tag) { "execute(): $command" }
//
//        val operationState = OperationState.OnGoing(
//            operationId = operationId,
//            startedAt = startedAt,
//        )
//
//        operation.sources.copy(
//            gateway = gatewaySwitch,
//            destination = operation.destination,
//            options = CopyAction.Options(
//                onIssue = { issue -> issueHandler.handleIssue(context, issue) }
//            )
//        )
//            .onEach { copyState ->
//                when (copyState) {
//                    is CopyAction.State.Progress<*> -> {
//                        reportBuilder.updateBytesProcessed(copyState.bytesCopied)
//
//                        emit(
//                            operationState.copy(
//                                actionProgress = Progress.Data(
//                                    count = Progress.Count.Size(
//                                        current = copyState.bytesCopied,
//                                        max = copyState.totalBytes,
//                                    )
//                                ),
//                                bytesProcessed = copyState.bytesCopied,
//                            )
//                        )
//                    }
//                    is CopyAction.State.Result<*> -> {
//                        trackPathsAdded(copyState.copiedFiles.map { it.second })
//                    }
//                }
//            }
//            .last()
//
//        OperationResult.Success(
//            summary = caString { "Copied ${operation.sources.size} files" }  // TODO localize
//        )
    }


    @AssistedFactory
    interface Factory {
        fun create(
            workspaceId: Workspace.Id,
            command: ExplorerCommand.Copy,
        ): CopyOperation
    }
}