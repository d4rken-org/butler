package eu.darken.butler.explorer.core.operations

import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import eu.darken.butler.common.debug.logging.Logging.Priority.*
import eu.darken.butler.common.debug.logging.asLog
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.debug.logging.logTag
import eu.darken.butler.common.files.operations.Issue
import eu.darken.butler.workspace.core.Workspace
import eu.darken.butler.workspace.core.operations.Operation
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap

class IssueHandler @AssistedInject constructor(
    @Assisted private val workspaceId: Workspace.Id,
) {
    private val tag = logTag("Explorer", "Workspace", "IssueHandler", workspaceId.shortTag)

    private val pendingIssues = ConcurrentHashMap<Operation.Id, CompletableDeferred<Issue.Resolution>>()
    private val mutex = Mutex()

    // Apply to All rules per operation
    private data class ApplyToAllRules(
        var skipAll: Boolean = false,
        var overwriteAll: Boolean = false,
        var mergeAllDirectories: Boolean = false,
        var retryAll: Boolean = false,
    )

    private val operationRules = ConcurrentHashMap<Operation.Id, ApplyToAllRules>()

    suspend fun handleIssue(
        context: OperationContext,
        issue: Issue,
    ): Issue.Resolution {
        log(tag) { "handleIssue(): ${context.operationId} - $issue" }

        // Get or create rules for this specific operation
        val rules = operationRules.getOrPut(context.operationId) { ApplyToAllRules() }

        // Check if we have an applicable "apply to all" rule
        val existingResolution = when (issue) {
            is Issue.PathAlreadyExists -> {
                when {
                    rules.skipAll && issue.canSkip ->
                        Issue.PathAlreadyExists.Resolution.Skip(applyToAll = true)
                    rules.overwriteAll && issue.canOverwrite ->
                        Issue.PathAlreadyExists.Resolution.Overwrite(applyToAll = true)
                    rules.mergeAllDirectories && issue.canMerge ->
                        Issue.PathAlreadyExists.Resolution.Merge(applyToAll = true)
                    else -> null
                }
            }
            is Issue.InsufficientPermission -> {
                when {
                    rules.skipAll && issue.canSkip ->
                        Issue.InsufficientPermission.Resolution.Skip(applyToAll = true)
                    else -> null
                }
            }
            is Issue.InsufficientSpace -> {
                when {
                    rules.skipAll && issue.canSkip ->
                        Issue.InsufficientSpace.Resolution.Skip(applyToAll = true)
                    else -> null
                }
            }
            is Issue.UnknownError -> {
                when {
                    rules.skipAll && issue.canSkip ->
                        Issue.UnknownError.Resolution.Skip(applyToAll = true)
                    rules.retryAll && issue.canRetry ->
                        Issue.UnknownError.Resolution.Retry(applyToAll = true)
                    else -> null
                }
            }
        }

        existingResolution?.let {
            log(tag) { "Applying saved resolution: $it" }
            return it
        }

        val deferred = CompletableDeferred<Issue.Resolution>()

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

            val res = deferred.await()

            // Store "apply to all" preferences for this operation
            when (res) {
                is Issue.PathAlreadyExists.Resolution.Skip -> {
                    if (res.applyToAll) rules.skipAll = true
                }
                is Issue.PathAlreadyExists.Resolution.Overwrite -> {
                    if (res.applyToAll) rules.overwriteAll = true
                }
                is Issue.PathAlreadyExists.Resolution.Merge -> {
                    if (res.applyToAll) rules.mergeAllDirectories = true
                }

                is Issue.InsufficientPermission.Resolution.Skip -> {
                    if (res.applyToAll) rules.skipAll = true
                }

                is Issue.InsufficientSpace.Resolution.Skip -> {
                    if (res.applyToAll) rules.skipAll = true
                }

                is Issue.UnknownError.Resolution.Skip -> {
                    if (res.applyToAll) rules.skipAll = true
                }
                is Issue.UnknownError.Resolution.Retry -> {
                    if (res.applyToAll) rules.retryAll = true
                }
            }

            res
        } catch (e: Exception) {
            log(tag, WARN) { "Issue resolution failed: ${e.asLog()}" }
            when (issue) {
                is Issue.InsufficientPermission -> Issue.InsufficientPermission.Resolution.Cancel(error = e)
                is Issue.InsufficientSpace -> Issue.InsufficientSpace.Resolution.Cancel(error = e)
                is Issue.PathAlreadyExists -> Issue.PathAlreadyExists.Resolution.Cancel(error = e)
                is Issue.UnknownError -> Issue.UnknownError.Resolution.Cancel(error = e)
            }
        } finally {
            mutex.withLock {
                pendingIssues.remove(context.operationId)
            }
        }
    }

    suspend fun resolveIssue(operationId: Operation.Id, resolution: Issue.Resolution) = mutex.withLock {
        log(tag) { "resolveIssue(): Operation $operationId: $resolution" }
        pendingIssues[operationId]?.complete(resolution)
    }

    fun cleanupOperation(operationId: Operation.Id) {
        log(tag) { "cleanupOperation(): $operationId" }
        operationRules.remove(operationId)
    }

    @AssistedFactory
    interface Factory {
        fun create(workspaceId: Workspace.Id): IssueHandler
    }
}