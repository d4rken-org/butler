package eu.darken.butler.explorer.core.operations.handlers

import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import eu.darken.butler.common.coroutine.DispatcherProvider
import eu.darken.butler.common.debug.logging.Logging.Priority.*
import eu.darken.butler.common.debug.logging.asLog
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.debug.logging.logTag
import eu.darken.butler.common.files.GatewaySwitch
import eu.darken.butler.common.files.LocalPath
import eu.darken.butler.common.files.extensions.delete
import eu.darken.butler.common.files.extensions.deleteWalk
import eu.darken.butler.common.files.extensions.exists
import eu.darken.butler.explorer.core.engine.ExplorerOperation
import eu.darken.butler.explorer.core.operations.IssueHandler
import eu.darken.butler.explorer.core.operations.OperationContext
import eu.darken.butler.explorer.core.operations.OperationNotifier
import eu.darken.butler.workspace.core.Workspace

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
    ): Unit = with(context) {
        log(tag) { "execute(): $operation" }
        operation.paths.size
        var processedCount = 0

        // Emit hint that files will be removed
        val parentPath = when (val first = operation.paths.firstOrNull()) {
            is LocalPath -> first.parent()
            else -> null // TODO We should support more path types
        }
        if (parentPath != null) {
            OperationNotifier.Hint.FilesRemoved(
                affectedFolder = parentPath,
                files = operation.paths.toList(),
                operationId = operation.operationId,
            ).run { emit(this) }
        }

        for (path in operation.paths) {
            try {
                // Get size before deletion for metrics
                if (path.exists(gatewaySwitch)) {
                    gatewaySwitch.lookup(path).size
                } else 0L

                // Perform deletion
                if (operation.recursive) {
                    path.deleteWalk(gatewaySwitch)
                } else {
                    path.delete(gatewaySwitch)
                }

                processedCount++
            } catch (e: Exception) {
                if (operation.options.skipOnError) {
                    log(tag, WARN) { "Failed to delete $path: ${e.asLog()}" }
                    continue
                } else {
                    throw e
                }
            }
        }
    }

    @AssistedFactory
    interface Factory {
        fun create(
            workspaceId: Workspace.Id,
            issueHandler: IssueHandler,
        ): DeleteOperationHandler
    }
}