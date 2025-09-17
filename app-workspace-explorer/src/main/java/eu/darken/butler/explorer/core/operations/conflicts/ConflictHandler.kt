package eu.darken.butler.explorer.core.operations.conflicts

import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import eu.darken.butler.common.debug.logging.Logging.Priority.*
import eu.darken.butler.common.debug.logging.asLog
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.debug.logging.logTag
import eu.darken.butler.explorer.core.operations.OperationContext
import eu.darken.butler.explorer.core.operations.OperationId
import eu.darken.butler.explorer.core.operations.OperationState
import eu.darken.butler.workspace.core.Workspace
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap

class ConflictHandler @AssistedInject constructor(
    @Assisted private val workspaceId: Workspace.Id,
) {
    private val tag = logTag("Explorer", "Workspace", "ConflictHandler", workspaceId.shortTag)

    private val pendingConflicts = ConcurrentHashMap<OperationId, CompletableDeferred<Conflict.Resolution?>>()
    private val mutex = Mutex()

    suspend fun handleConflict(
        context: OperationContext,
        conflict: Conflict,
    ): Conflict.Resolution? {
        log(tag) { "handleConflict(): ${context.operationId} - $conflict" }

        val deferred = CompletableDeferred<Conflict.Resolution?>()

        mutex.withLock {
            pendingConflicts[context.operationId] = deferred
        }

        return try {
            context.emit(
                OperationState.AwaitingInput(
                    operationId = context.operationId,
                    startTime = context.startTime,
                    conflict = conflict,
                )
            )

            deferred.await()
        } catch (e: Exception) {
            log(tag, WARN) { "Conflict resolution failed: ${e.asLog()}" }
            null
        } finally {
            mutex.withLock {
                pendingConflicts.remove(context.operationId)
            }
        }
    }

    suspend fun resolveConflict(operationId: OperationId, resolution: Conflict.Resolution?) = mutex.withLock {
        log(tag) { "resolveConflict(): Operation $operationId: $resolution" }
        pendingConflicts[operationId]?.complete(resolution)
    }

    @AssistedFactory
    interface Factory {
        fun create(workspaceId: Workspace.Id): ConflictHandler
    }
}