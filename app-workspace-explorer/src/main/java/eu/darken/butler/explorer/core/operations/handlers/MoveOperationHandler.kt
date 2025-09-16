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
import eu.darken.butler.explorer.core.operations.OperationMetrics
import eu.darken.butler.explorer.core.operations.OperationNotifier
import eu.darken.butler.explorer.core.operations.OperationState
import eu.darken.butler.workspace.core.Workspace
import kotlin.time.Instant

class MoveOperationHandler @AssistedInject constructor(
    @Assisted workspaceId: Workspace.Id,
    @Assisted operationNotifier: OperationNotifier,
    @Assisted private val copyHandler: CopyOperationHandler,
    gatewaySwitch: GatewaySwitch,
    dispatcherProvider: DispatcherProvider,
) : BaseOperationHandler<ExplorerOperation.FileOp.Move>(
    workspaceId,
    gatewaySwitch,
    dispatcherProvider,
    operationNotifier
) {

    private val tag = logTag("Explorer", "Workspace", workspaceId.shortTag, "OperationEngine", "Move")

    override suspend fun execute(
        operation: ExplorerOperation.FileOp.Move,
        startTime: Instant,
        emitState: suspend (OperationState) -> Unit,
    ): OperationMetrics {
        log(tag) { "execute(): $operation" }

        // Emit hint for move operation
        val sourcePath = when (val first = operation.sources.firstOrNull()) {
            is LocalPath -> first.parent() ?: operation.destination
            else -> operation.destination
        }
        val hint = OperationNotifier.Hint.FilesMoved(
            targetPath = operation.destination,
            sourcePath = sourcePath,
            files = operation.sources.toList(),
            operationId = operation.operationId,
        )
        operationNotifier.publish(hint.asAdditionHint())
        operationNotifier.publish(hint.asRemovalHint())

        // Move is copy + delete
        val metrics = copyHandler.execute(
            operation = ExplorerOperation.FileOp.Copy(
                sources = operation.sources,
                destination = operation.destination,
                options = CopyOptions(
                    conflictStrategy = operation.options.conflictStrategy,
                    preserveAttributes = operation.options.preserveAttributes,
                ),
            ),
            startTime = startTime,
            emitState = emitState,
        )

        // Delete sources after successful copy
        for (source in operation.sources) {
            source.deleteWalk(gatewaySwitch)
        }

        return metrics
    }

    @AssistedFactory
    interface Factory {
        fun create(
            workspaceId: Workspace.Id,
            operationNotifier: OperationNotifier,
            copyHandler: CopyOperationHandler,
        ): MoveOperationHandler
    }
}