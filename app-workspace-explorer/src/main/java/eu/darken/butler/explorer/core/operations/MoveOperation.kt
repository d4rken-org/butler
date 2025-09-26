package eu.darken.butler.explorer.core.operations

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.twotone.DriveFileMove
import androidx.compose.ui.graphics.vector.ImageVector
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import eu.darken.butler.common.ca.caString
import eu.darken.butler.common.coroutine.DispatcherProvider
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.debug.logging.logTag
import eu.darken.butler.common.files.GatewaySwitch
import eu.darken.butler.workspace.core.Workspace
import eu.darken.butler.workspace.core.operations.IssueHandler
import eu.darken.butler.workspace.core.operations.Operation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

class MoveOperation @AssistedInject constructor(
    @Assisted workspaceId: Workspace.Id,
    @Assisted private val command: ExplorerCommand.Move,
    private val issueHandler: IssueHandler,
    gatewaySwitch: GatewaySwitch,
    dispatcherProvider: DispatcherProvider,
) : ExplorerOperation() {

    private val tag = logTag("Explorer", "Workspace", workspaceId.shortTag, "Operation", "Move")

    override val metadata: Operation.Metadata = object : Operation.Metadata {
        override val origin = Operation.Metadata.Origin.Explorer(workspaceId)
        override val icon: ImageVector = Icons.AutoMirrored.TwoTone.DriveFileMove
        override val title = caString { "Move" } // TODO
        override val description = caString { "Move selected files" } // TODO
    }

    override fun perform(
        operationContext: Operation.Context
    ): Flow<State> = flow {
        log(tag) { "execute(): $command" }

//        val operationState = OperationState.OnGoing(
//            operationId = operationId,
//            startedAt = startedAt,
//        )
//
//        operation.sources.move(
//            gateway = gatewaySwitch,
//            destination = operation.destination,
//            options = MoveAction.Options(
//                onIssue = { issue -> issueHandler.handleIssue(context, issue) }
//            )
//        )
//            .onEach { moveState ->
//                when (moveState) {
//                    is MoveAction.State.Progress<*> -> {
//                        reportBuilder.updateBytesProcessed(moveState.bytesMoved)
//
//                        emit(
//                            operationState.copy(
//                                actionProgress = Progress.Data(
//                                    count = Progress.Count.Size(
//                                        current = moveState.bytesMoved,
//                                        max = moveState.totalBytes,
//                                    )
//                                ),
//                                bytesProcessed = moveState.bytesMoved,
//                            )
//                        )
//                    }
//                    is MoveAction.State.Result<*> -> {
//                        trackPathsRemoved(moveState.movedFiles.map { it.first })
//                        trackPathsAdded(moveState.movedFiles.map { it.second })
//                    }
//                }
//            }
//            .last()
//
//        OperationResult.Success(
//            summary = caString { "Moved ${operation.sources.size} files" }  // TODO localize
//        )
    }

    @AssistedFactory
    interface Factory {
        fun create(
            workspaceId: Workspace.Id,
            command: ExplorerCommand.Move,
        ): MoveOperation
    }
}