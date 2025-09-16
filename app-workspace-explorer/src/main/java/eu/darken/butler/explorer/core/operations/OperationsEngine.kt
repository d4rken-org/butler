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
import eu.darken.butler.explorer.core.errors.ConflictResolution
import eu.darken.butler.explorer.core.errors.ExplorerError
import eu.darken.butler.explorer.core.operations.handlers.ConflictHandler
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
class OperationEngine @AssistedInject constructor(
    @Assisted private val workspaceId: Workspace.Id,
    private val dispatcherProvider: DispatcherProvider,
    private val operationHints: OperationHints,
    private val conflictHandler: ConflictHandler,
    private val copyHandlerFactory: CopyOperationHandler.Factory,
    private val moveHandlerFactory: MoveOperationHandler.Factory,
    private val deleteHandlerFactory: DeleteOperationHandler.Factory,
    private val createHandlerFactory: CreateOperationHandler.Factory,
    private val renameHandlerFactory: RenameOperationHandler.Factory,
) {

    private val tag = logTag("Explorer", "Workspace", "OperationEngine", workspaceId.shortTag)

    private val activeOperations = ConcurrentHashMap<OperationId, Job>()
    private val conflictStrategies = ConcurrentHashMap<OperationId, ConflictStrategy>()

    private val copyHandler = copyHandlerFactory.create(operationHints, conflictHandler)
    private val moveHandler = moveHandlerFactory.create(operationHints, copyHandler)
    private val deleteHandler = deleteHandlerFactory.create(operationHints)
    private val createHandler = createHandlerFactory.create(operationHints, conflictHandler)
    private val renameHandler = renameHandlerFactory.create(operationHints)

    val hints = operationHints.hints

    fun execute(
        operation: ExplorerOperation,
        scope: CoroutineScope,
        conflictStrategy: ConflictStrategy = ConflictStrategy.ASK,
    ): Flow<OperationState> = flow {
        log(tag, DEBUG) { "execute(): $operation" }
        val startTime = Clock.System.now()
        var metrics = OperationMetrics()


        try {
            activeOperations[operation.operationId] = scope.coroutineContext[Job]!!
            conflictStrategies[operation.operationId] = conflictStrategy

            // Emit initial state
            emit(
                OperationState.OnGoing(
                    operationId = operation.operationId,
                    startTime = startTime,
                    canCancel = operation.canCancel,
                )
            )

            // Execute based on operation type
            when (operation) {
                is ExplorerOperation.FileOp.Copy -> {
                    metrics = copyHandler.execute(operation, startTime) { state ->
                        emit(state)
                    }
                }
                is ExplorerOperation.FileOp.Move -> {
                    metrics = moveHandler.execute(operation, startTime) { state ->
                        emit(state)
                    }
                }
                is ExplorerOperation.FileOp.Delete -> {
                    metrics = deleteHandler.execute(operation, startTime) { state ->
                        emit(state)
                    }
                }
                is ExplorerOperation.FileOp.CreateFolder -> {
                    metrics = createHandler.executeCreateFolder(operation, startTime) { state ->
                        emit(state)
                    }
                }
                is ExplorerOperation.FileOp.CreateFile -> {
                    metrics = createHandler.executeCreateFile(operation, startTime) { state ->
                        emit(state)
                    }
                }
                is ExplorerOperation.FileOp.Rename -> {
                    metrics = renameHandler.execute(operation, startTime) { state ->
                        emit(state)
                    }
                }
                else -> {
                    throw UnsupportedOperationException("Operation not yet implemented: $operation")
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
                        error = when (e) {
                            else -> ExplorerError.Unknown(e)
                        },
                        exception = e,
                    ),
                )
            )
        } finally {
            activeOperations.remove(operation.operationId)
            conflictStrategies.remove(operation.operationId)
        }
    }.flowOn(dispatcherProvider.IO)

    suspend fun resolveConflict(operationId: OperationId, resolution: ConflictResolution) {
        log(tag) { "resolveConflict(): Operation $operationId: $resolution" }
        conflictHandler.resolveConflict(operationId, resolution)

        // Update strategy if "apply to all" is set
        if (resolution is ConflictResolution.Skip && resolution.applyToAll ||
            resolution is ConflictResolution.Overwrite && resolution.applyToAll ||
            resolution is ConflictResolution.Merge && resolution.applyToAll
        ) {
            conflictStrategies[operationId] = ConflictStrategy(
                defaultResolution = resolution,
                applyToAll = true,
            )
        }
    }

    fun cancelOperation(operationId: OperationId) {
        log(tag) { "cancelOperation(): $operationId" }
        activeOperations[operationId]?.cancel()
    }

    @AssistedFactory
    interface Factory {
        fun create(workspaceId: Workspace.Id): DeleteOperationHandler
    }
}