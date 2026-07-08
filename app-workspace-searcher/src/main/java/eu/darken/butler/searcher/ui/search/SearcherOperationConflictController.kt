package eu.darken.butler.searcher.ui.search

import eu.darken.butler.common.debug.logging.Logging.Priority.*
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.files.actions.PathActionIssue
import eu.darken.butler.common.issue.Issue
import eu.darken.butler.searcher.core.SearcherWorkspace
import eu.darken.butler.workspace.core.operations.Operation
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach

/**
 * Conflict/issue sheet state for operations awaiting user resolution. The Searcher auto-surfaces
 * the first pending conflict: the page shows the sheet whenever [issueState] is non-null.
 */
class SearcherOperationConflictController(
    private val pendingConflicts: Flow<Map<Operation.Id, Issue>>,
    private val workspace: suspend () -> SearcherWorkspace,
    private val doLaunch: (suspend CoroutineScope.() -> Unit) -> Unit,
    private val tag: String,
) {

    private val issueStateFlow = MutableStateFlow<Issue?>(null)
    val issueState: StateFlow<Issue?> = issueStateFlow

    private var currentIssueOperationId: Operation.Id? = null

    /** Auto-surfaces the first pending conflict. The ViewModel launches this in its scope. */
    val conflictObserver = pendingConflicts
        .map { it.entries.firstOrNull() }
        .onEach { pending ->
            if (pending != null) {
                log(tag, INFO) { "Detected pending issue for operation ${pending.key}: ${pending.value}" }
                issueStateFlow.value = pending.value
                currentIssueOperationId = pending.key
            } else {
                issueStateFlow.value = null
                currentIssueOperationId = null
            }
        }

    fun resolve(resolution: PathActionIssue.Resolution) = doLaunch {
        val operationId = currentIssueOperationId
        if (operationId != null) {
            log(tag, INFO) { "Resolving issue for operation $operationId with resolution: $resolution" }
            workspace().resolveConflict(operationId, resolution)
        } else {
            log(tag, WARN) { "Cannot resolve issue: no current issue operation ID" }
        }
    }
}
