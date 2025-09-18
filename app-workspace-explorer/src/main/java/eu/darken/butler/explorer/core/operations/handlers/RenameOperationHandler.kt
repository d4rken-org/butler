package eu.darken.butler.explorer.core.operations.handlers

import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import eu.darken.butler.common.coroutine.DispatcherProvider
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.debug.logging.logTag
import eu.darken.butler.common.files.GatewaySwitch
import eu.darken.butler.explorer.core.engine.ExplorerOperation
import eu.darken.butler.explorer.core.operations.IssueHandler
import eu.darken.butler.explorer.core.operations.OperationContext
import eu.darken.butler.explorer.core.operations.OperationNotifier
import eu.darken.butler.workspace.core.Workspace

class RenameOperationHandler @AssistedInject constructor(
    @Assisted workspaceId: Workspace.Id,
    @Assisted issueHandler: IssueHandler,
    gatewaySwitch: GatewaySwitch,
    dispatcherProvider: DispatcherProvider,
) : BaseOperationHandler<ExplorerOperation.FileOp.Rename>(
    workspaceId,
    gatewaySwitch,
    dispatcherProvider,
) {

    private val tag = logTag("Explorer", "Workspace", workspaceId.shortTag, "Operation", "Rename")

    override suspend fun executeInContext(
        context: OperationContext,
        operation: ExplorerOperation.FileOp.Rename,
    ): Unit = with(context) {
        log(tag) { "execute(): $operation" }

        // TODO: Implement actual rename with gateway
        // Note: suspend is needed once gateway operations are implemented
        // For now, just log the operation
        log(tag) { "Rename: ${operation.path} -> ${operation.newName}" }


        OperationNotifier.Hint.FileRenamed(
            operationId = operationId,
            affectedFolder = operation.path,
            oldName = operation.path.name,
            newName = operation.newName,
        ).run { emit(this) }
    }

    @AssistedFactory
    interface Factory {
        fun create(
            workspaceId: Workspace.Id,
            issueHandler: IssueHandler,
        ): RenameOperationHandler
    }
}