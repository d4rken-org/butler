package eu.darken.butler.explorer.core.operations.handlers

import eu.darken.butler.common.coroutine.DispatcherProvider
import eu.darken.butler.common.files.GatewaySwitch
import eu.darken.butler.explorer.core.engine.ExplorerOperation
import eu.darken.butler.explorer.core.operations.OperationContext
import eu.darken.butler.workspace.core.Workspace

/**
 * Base class for operation handlers providing shared functionality.
 */
abstract class BaseOperationHandler<T : ExplorerOperation>(
    protected val workspaceId: Workspace.Id,
    protected val gatewaySwitch: GatewaySwitch,
    protected val dispatcherProvider: DispatcherProvider,
) {
    abstract suspend fun execute(
        context: OperationContext,
        operation: T,
    )
}