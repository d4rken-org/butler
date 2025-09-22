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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
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
    private val moveHandler = moveHandlerFactory.create(workspaceId, issueHandler)
    private val deleteHandler = deleteHandlerFactory.create(workspaceId, issueHandler)
    private val createHandler = createHandlerFactory.create(workspaceId, issueHandler)
    private val publisher = MutableSharedFlow<FileSystemEvent>()
    val hints: Flow<FileSystemEvent> = publisher

    fun execute(
        operation: ExplorerOperation,
        scope: CoroutineScope,
    ): Flow<OperationState> = flow {
        log(tag, DEBUG) { "execute(): $operation" }

        val opCon = OperationContext(
            operationId = operation.operationId,
            emitState = { state ->
                log(tag) { "execute(): State: $state" }
                when (state) {
                    is OperationState.AwaitingInput -> {}
                    is OperationState.Completed -> {}
                    is OperationState.OnGoing -> {}
                }
                emit(state)
            },
            emitPathEvent = { event ->
                log(tag) { "execute(): Event: $event" }
                // TODO track affected paths for history in UI
                publisher.emit(event)
            }
        )

        try {
            activeOperations[operation.operationId] = scope.coroutineContext[Job]!!

            OperationState.OnGoing(
                operationId = opCon.operationId,
                startedAt = opCon.startedAt,
            ).run { emit(this) }

            val result = with(opCon) {
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

            log(tag, INFO) { "execute(): Completed: $result" }

            OperationState.Completed(
                operationId = opCon.operationId,
                startedAt = opCon.startedAt,
                result = result,
                metrics = opCon.getMetrics(),
            ).run { emit(this) }
        } catch (e: Exception) {
            val result = OperationResult.Failure(exception = e)

            if (result.isCancelled) log(tag, WARN) { "execute(): Operation cancelled: $operation" }
            else log(tag, ERROR) { "execute(): Operation failed: $operation - ${e.asLog()}" }

            OperationState.Completed(
                operationId = opCon.operationId,
                startedAt = opCon.startedAt,
                result = result,
                metrics = opCon.getMetrics(),
            ).run { emit(this) }

            if (result.isCancelled) throw e
        } finally {
            activeOperations.remove(operation.operationId)
            issueHandler.cleanupOperation(operation.operationId)
        }
    }.flowOn(dispatcherProvider.IO)

    suspend fun resolveConflict(operationId: OperationId, resolution: Issue.Resolution) {
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