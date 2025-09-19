package eu.darken.butler.explorer.core.operations

import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import eu.darken.butler.common.debug.logging.Logging
import eu.darken.butler.common.debug.logging.asLog
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.debug.logging.logTag
import eu.darken.butler.common.files.operations.Issue
import eu.darken.butler.workspace.core.Workspace
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap

class IssueHandler @AssistedInject constructor(
    @Assisted private val workspaceId: Workspace.Id,
) {
    private val tag = logTag("Explorer", "Workspace", "IssueHandler", workspaceId.shortTag)

    private val pendingIssues = ConcurrentHashMap<OperationId, CompletableDeferred<Issue.Resolution?>>()
    private val mutex = Mutex()

    suspend fun handleIssue(
        context: OperationContext,
        issue: Issue,
    ): Issue.Resolution? {
        log(tag) { "handleIssue(): ${context.operationId} - $issue" }

        val deferred = CompletableDeferred<Issue.Resolution?>()

        mutex.withLock {
            pendingIssues[context.operationId] = deferred
        }

        return try {
            context.emit(
                OperationState.AwaitingInput(
                    operationId = context.operationId,
                    startedAt = context.startedAt,
                    issue = issue,
                )
            )

            deferred.await()
        } catch (e: Exception) {
            log(tag, Logging.Priority.WARN) { "Issue resolution failed: ${e.asLog()}" }
            null
        } finally {
            mutex.withLock {
                pendingIssues.remove(context.operationId)
            }
        }
    }

    suspend fun resolveIssue(operationId: OperationId, resolution: Issue.Resolution?) = mutex.withLock {
        log(tag) { "resolveIssue(): Operation $operationId: $resolution" }
        pendingIssues[operationId]?.complete(resolution)
    }

    @AssistedFactory
    interface Factory {
        fun create(workspaceId: Workspace.Id): IssueHandler
    }
}