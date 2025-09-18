package eu.darken.butler.explorer.core.operations.handlers

import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import eu.darken.butler.common.coroutine.DispatcherProvider
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.debug.logging.logTag
import eu.darken.butler.common.files.GatewaySwitch
import eu.darken.butler.common.files.LocalPath
import eu.darken.butler.common.files.extensions.deleteWalk
import eu.darken.butler.explorer.core.engine.CopyOptions
import eu.darken.butler.explorer.core.engine.ExplorerOperation
import eu.darken.butler.explorer.core.operations.IssueHandler
import eu.darken.butler.explorer.core.operations.OperationContext
import eu.darken.butler.explorer.core.operations.OperationNotifier
import eu.darken.butler.workspace.core.Workspace

class MoveOperationHandler @AssistedInject constructor(
    @Assisted workspaceId: Workspace.Id,
    @Assisted issueHandler: IssueHandler,
    @Assisted private val copyHandler: CopyOperationHandler,
    gatewaySwitch: GatewaySwitch,
    dispatcherProvider: DispatcherProvider,
) : BaseOperationHandler<ExplorerOperation.FileOp.Move>(
    workspaceId,
    gatewaySwitch,
    dispatcherProvider,
) {

    private val tag = logTag("Explorer", "Workspace", workspaceId.shortTag, "OperationEngine", "Move")

    override suspend fun execute(
        context: OperationContext,
        operation: ExplorerOperation.FileOp.Move,
    ): Unit = with(context) {
        log(tag) { "execute(): $operation" }

        // Emit hint for move operation
        when (val first = operation.sources.firstOrNull()) {
            is LocalPath -> first.parent() ?: operation.destination
            else -> operation.destination
        }

        // Move is copy + delete
        copyHandler.execute(
            context,
            ExplorerOperation.FileOp.Copy(
                sources = operation.sources,
                destination = operation.destination,
                options = CopyOptions(
                    preserveAttributes = operation.options.preserveAttributes,
                ),
            )
        )
        OperationNotifier.Hint.FilesAdded(
            operationId = operation.operationId,
            affectedFolder = operation.destination,
            files = operation.sources.toList(),
        ).run { emit(this) }

        // Delete sources after successful copy
        for (source in operation.sources) {
            source.deleteWalk(gatewaySwitch)
        }
        OperationNotifier.Hint.FilesRemoved(
            operationId = operation.operationId,
            affectedFolder = operation.destination,
            files = operation.sources.toList(),
        ).run { emit(this) }
    }

    @AssistedFactory
    interface Factory {
        fun create(
            workspaceId: Workspace.Id,
            issueHandler: IssueHandler,
            copyHandler: CopyOperationHandler,
        ): MoveOperationHandler
    }
}