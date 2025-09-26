package eu.darken.butler.workspace.core.operations

import eu.darken.butler.common.coroutine.DispatcherProvider
import eu.darken.butler.common.debug.logging.Logging.Priority.*
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.debug.logging.logTag
import eu.darken.butler.common.issue.Issue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Clock
import kotlin.time.Instant

@Singleton
class IssueHandler @Inject constructor(
    private val dispatcherProvider: DispatcherProvider
) {
    private val scope = CoroutineScope(dispatcherProvider.IO + SupervisorJob())

    // Deferred for efficient resolution delivery
    private val pendingResolutions = ConcurrentHashMap<Operation.Id, CompletableDeferred<Issue.Resolution>>()

    // StateFlow for UI observation
    private val _pendingIssues = MutableStateFlow<Map<Operation.Id, PendingIssue>>(emptyMap())
    val pendingIssues = _pendingIssues.map { it.values.toList() }

    data class PendingIssue(
        val operationId: Operation.Id,
        val issue: Issue,
        val timestamp: Instant = Clock.System.now(),
    )

    suspend fun handleIssue(
        operationId: Operation.Id,
        issue: Issue
    ): Issue.Resolution {
        log(TAG, INFO) { "handleIssue(): $operationId - $issue" }
        val deferred = CompletableDeferred<Issue.Resolution>()
        pendingResolutions[operationId] = deferred

        val pending = PendingIssue(
            operationId = operationId,
            issue = issue,
        )

        _pendingIssues.update { it + (operationId to pending) }

        try {
            log(TAG, VERBOSE) { "handleIssue(): Awaiting resolution of ${issue.id}$" }
            return deferred.await().also { resolution ->
                log(TAG, VERBOSE) { "handleIssue(): Resolved: ${issue.id}$ - $resolution" }
            }
        } catch (e: CancellationException) {
            log(TAG, DEBUG) { "Operation $operationId cancelled while waiting for issue resolution" }
            throw e
        } finally {
            pendingResolutions.remove(operationId)
            _pendingIssues.update { it - operationId }
        }
    }

    fun resolveIssue(id: Operation.Id, resolution: Issue.Resolution): Boolean {
        val deferred = pendingResolutions[id]
        return if (deferred != null) {
            deferred.complete(resolution)
            true
        } else {
            log(WARN) { "Attempted to resolve non-existent issue for operation: $id" }
            false
        }
    }

    fun cancelOperation(id: Operation.Id): Boolean {
        val deferred = pendingResolutions[id]
        return if (deferred != null) {
            deferred.cancel(CancellationException("Operation cancelled by user"))
            true
        } else {
            false
        }
    }

    fun cancelAllOperations() {
        pendingResolutions.values.forEach {
            it.cancel(CancellationException("All operations cancelled"))
        }
    }

    companion object {
        private val TAG = logTag("Workspace", "Operations", "IssueHandler")
    }
}