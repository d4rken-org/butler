package eu.darken.butler.explorer.core.operations

import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import eu.darken.butler.common.coroutine.DispatcherProvider
import eu.darken.butler.common.debug.logging.Logging.Priority.*
import eu.darken.butler.common.debug.logging.asLog
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.debug.logging.logTag
import eu.darken.butler.explorer.core.engine.ExplorerOperation
import eu.darken.butler.explorer.core.operations.OperationContext
import eu.darken.butler.explorer.core.operations.conflicts.Conflict
import eu.darken.butler.explorer.core.operations.conflicts.ConflictHandler
import eu.darken.butler.explorer.core.operations.handlers.CopyOperationHandler
import eu.darken.butler.explorer.core.operations.handlers.CreateOperationHandler
import eu.darken.butler.explorer.core.operations.handlers.DeleteOperationHandler
import eu.darken.butler.explorer.core.operations.handlers.MoveOperationHandler
import eu.darken.butler.explorer.core.operations.handlers.RenameOperationHandler
import eu.darken.butler.workspace.core.Workspace
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Clock

/**
 * Executes file system operations with support for progress tracking,
 * conflict resolution, and cancellation. Operations are executed asynchronously
 * and can be suspended while awaiting user input for conflict resolution.
 */
class OperationsEngine @AssistedInject constructor(
    @Assisted private val workspaceId: Workspace.Id,
    private val dispatcherProvider: DispatcherProvider,
    private val operationNotifier: OperationNotifier,
    private val conflictHandlerFactory: ConflictHandler.Factory,
    private val copyHandlerFactory: CopyOperationHandler.Factory,
    private val moveHandlerFactory: MoveOperationHandler.Factory,
    private val deleteHandlerFactory: DeleteOperationHandler.Factory,
    private val createHandlerFactory: CreateOperationHandler.Factory,
    private val renameHandlerFactory: RenameOperationHandler.Factory,
) {

    private val conflictHandler = conflictHandlerFactory.create(workspaceId)
    private val tag = logTag("Explorer", "Workspace", workspaceId.shortTag, "OperationEngine")

    private val activeOperations = ConcurrentHashMap<OperationId, Job>()

    private val copyHandler = copyHandlerFactory.create(workspaceId, operationNotifier, conflictHandler)
    private val moveHandler = moveHandlerFactory.create(workspaceId, operationNotifier, copyHandler)
    private val deleteHandler = deleteHandlerFactory.create(workspaceId, operationNotifier)
    private val createHandler = createHandlerFactory.create(workspaceId, operationNotifier, conflictHandler)
    private val renameHandler = renameHandlerFactory.create(workspaceId, operationNotifier)

    val hints = operationNotifier.hints

    fun execute(
        operation: ExplorerOperation,
        scope: CoroutineScope,
    ): Flow<OperationState> = flow {
        log(tag, DEBUG) { "execute(): $operation" }
        val startTime = Clock.System.now()
        var metrics = OperationMetrics()


        try {
            activeOperations[operation.operationId] = scope.coroutineContext[Job]!!
            // Create operation context
            val context = OperationContext(
                operationId = operation.operationId,
                startTime = startTime,
                emitState = { state -> emit(state) }
            )

            // Emit initial state
            context.emit(
                OperationState.OnGoing(
                    operationId = operation.operationId,
                    startTime = startTime,
                    canCancel = operation.canCancel,
                )
            )

            // Execute based on operation type using context extension pattern
            metrics = with(context) {
                when (operation) {
                    is ExplorerOperation.FileOp.Copy -> {
                        copyHandler.execute(operation)
                    }
                    is ExplorerOperation.FileOp.Move -> {
                        moveHandler.execute(operation)
                    }
                    is ExplorerOperation.FileOp.Delete -> {
                        deleteHandler.execute(operation)
                    }
                    is ExplorerOperation.FileOp.Create -> {
                        createHandler.execute(operation)
                    }
                    is ExplorerOperation.FileOp.Rename -> {
                        renameHandler.execute(operation)
                    }
                }
            }

            // Emit success
            emit(
                OperationState.Completed(
                    operationId = operation.operationId,
                    startTime = startTime,
                    result = OperationResult.Success(metrics = metrics),
                )
            )

        } catch (e: CancellationException) {
            log(tag, WARN) { "Operation cancelled: $operation" }
            emit(
                OperationState.Completed(
                    operationId = operation.operationId,
                    startTime = startTime,
                    result = OperationResult.Cancelled(metrics = metrics),
                )
            )
            throw e
        } catch (e: Exception) {
            log(tag, ERROR) { "Operation failed: $operation - ${e.asLog()}" }
            emit(
                OperationState.Completed(
                    operationId = operation.operationId,
                    startTime = startTime,
                    result = OperationResult.Failure(
                        metrics = metrics,
                        exception = e,
                    ),
                )
            )
        } finally {
            activeOperations.remove(operation.operationId)
        }
    }.flowOn(dispatcherProvider.IO)

    suspend fun resolveConflict(operationId: OperationId, resolution: Conflict.Resolution?) {
        log(tag) { "resolveConflict(): Operation $operationId: $resolution" }
        conflictHandler.resolveConflict(operationId, resolution)
    }

    fun cancelOperation(operationId: OperationId) {
        log(tag) { "cancelOperation(): $operationId" }
        activeOperations[operationId]?.cancel()
    }

    @AssistedFactory
    interface Factory {
        fun create(workspaceId: Workspace.Id): OperationsEngine
    }
}