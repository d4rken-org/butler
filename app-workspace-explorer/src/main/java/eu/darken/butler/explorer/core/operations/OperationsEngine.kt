package eu.darken.butler.explorer.core.operations

import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import eu.darken.butler.common.coroutine.DispatcherProvider
import eu.darken.butler.common.debug.logging.Logging.Priority.*
import eu.darken.butler.common.debug.logging.asLog
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.debug.logging.logTag
import eu.darken.butler.common.files.operations.Issue
import eu.darken.butler.explorer.core.engine.ExplorerOperation
import eu.darken.butler.explorer.core.operations.handlers.CopyOperationHandler
import eu.darken.butler.explorer.core.operations.handlers.CreateOperationHandler
import eu.darken.butler.explorer.core.operations.handlers.DeleteOperationHandler
import eu.darken.butler.explorer.core.operations.handlers.MoveOperationHandler
import eu.darken.butler.workspace.core.Workspace
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import java.util.concurrent.ConcurrentHashMap

/**
 * Executes file system operations with support for progress tracking,
 * conflict resolution, and cancellation. Operations are executed asynchronously
 * and can be suspended while awaiting user input for conflict resolution.
 */
class OperationsEngine @AssistedInject constructor(
    @Assisted private val workspaceId: Workspace.Id,
    private val dispatcherProvider: DispatcherProvider,
    private val operationNotifier: OperationNotifier,
    private val issueHandlerFactory: IssueHandler.Factory,
    private val copyHandlerFactory: CopyOperationHandler.Factory,
    private val moveHandlerFactory: MoveOperationHandler.Factory,
    private val deleteHandlerFactory: DeleteOperationHandler.Factory,
    private val createHandlerFactory: CreateOperationHandler.Factory,
) {

    private val issueHandler = issueHandlerFactory.create(workspaceId)
    private val tag = logTag("Explorer", "Workspace", workspaceId.shortTag, "OperationEngine")

    private val activeOperations = ConcurrentHashMap<OperationId, Job>()

    private val copyHandler = copyHandlerFactory.create(workspaceId, issueHandler)
    private val moveHandler = moveHandlerFactory.create(workspaceId, issueHandler, copyHandler)
    private val deleteHandler = deleteHandlerFactory.create(workspaceId, issueHandler)
    private val createHandler = createHandlerFactory.create(workspaceId, issueHandler)

    val hints = operationNotifier.hints

    fun execute(
        operation: ExplorerOperation,
        scope: CoroutineScope,
    ): Flow<OperationState> = flow {
        log(tag, DEBUG) { "execute(): $operation" }
        val opCon = OperationContext(
            operationId = operation.operationId,
            emitState = { state ->
                log(tag) { "execute(): Current state: $state" }
                emit(state)
            },
            emitHint = { operationNotifier.publish(it) }
        )

        try {
            activeOperations[operation.operationId] = scope.coroutineContext[Job]!!

            opCon.emit(
                OperationState.OnGoing(
                    operationId = opCon.operationId,
                    startedAt = opCon.startedAt,
                )
            )

            // Execute based on operation type using context extension pattern
            with(opCon) {
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
                }
            }

            emit(
                OperationState.Completed(
                    operationId = opCon.operationId,
                    startedAt = opCon.startedAt,
                    result = OperationResult.Success(
                        metrics = opCon.getMetrics(),
                    ),
                )
            )

        } catch (e: CancellationException) {
            log(tag, WARN) { "Operation cancelled: $operation" }
            emit(
                OperationState.Completed(
                    operationId = opCon.operationId,
                    startedAt = opCon.startedAt,
                    result = OperationResult.Cancelled(
                        metrics = opCon.getMetrics(),
                    ),
                )
            )
            throw e
        } catch (e: Exception) {
            log(tag, ERROR) { "Operation failed: $operation - ${e.asLog()}" }
            emit(
                OperationState.Completed(
                    operationId = opCon.operationId,
                    startedAt = opCon.startedAt,
                    result = OperationResult.Failure(
                        metrics = opCon.getMetrics(),
                        exception = e
                    ),
                )
            )
        } finally {
            activeOperations.remove(operation.operationId)
        }
    }.flowOn(dispatcherProvider.IO)

    suspend fun resolveConflict(operationId: OperationId, resolution: Issue.Resolution?) {
        log(tag) { "resolveConflict(): Operation $operationId: $resolution" }
        issueHandler.resolveIssue(operationId, resolution)
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