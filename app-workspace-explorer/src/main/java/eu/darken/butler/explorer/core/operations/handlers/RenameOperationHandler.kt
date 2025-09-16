package eu.darken.butler.explorer.core.operations.handlers

import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import eu.darken.butler.common.coroutine.DispatcherProvider
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.debug.logging.logTag
import eu.darken.butler.common.files.GatewaySwitch
import eu.darken.butler.explorer.core.engine.ExplorerOperation
import eu.darken.butler.explorer.core.operations.OperationMetrics
import eu.darken.butler.explorer.core.operations.OperationNotifier
import eu.darken.butler.explorer.core.operations.OperationState
import eu.darken.butler.workspace.core.Workspace
import kotlin.time.Instant

class RenameOperationHandler @AssistedInject constructor(
    @Assisted workspaceId: Workspace.Id,
    @Assisted operationNotifier: OperationNotifier,
    gatewaySwitch: GatewaySwitch,
    dispatcherProvider: DispatcherProvider,
) : BaseOperationHandler<ExplorerOperation.FileOp.Rename>(
    workspaceId,
    gatewaySwitch,
    dispatcherProvider,
    operationNotifier
) {

    private val tag = logTag("Explorer", "Workspace", workspaceId.shortTag, "Operation", "Rename")

    override suspend fun execute(
        operation: ExplorerOperation.FileOp.Rename,
        startTime: Instant,
        emitState: suspend (OperationState) -> Unit,
    ): OperationMetrics {
        log(tag) { "execute(): $operation" }

        // TODO: Implement actual rename with gateway
        // Note: suspend is needed once gateway operations are implemented
        // For now, just log the operation
        log(tag) { "Rename: ${operation.path} -> ${operation.newName}" }

        // Placeholder
        return OperationMetrics().withAddedFile(0L)
    }

    @AssistedFactory
    interface Factory {
        fun create(
            workspaceId: Workspace.Id,
            operationNotifier: OperationNotifier,
        ): RenameOperationHandler
    }
}