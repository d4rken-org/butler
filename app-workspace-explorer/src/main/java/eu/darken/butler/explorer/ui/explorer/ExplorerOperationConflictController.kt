package eu.darken.butler.explorer.ui.explorer

import eu.darken.butler.common.debug.logging.Logging.Priority.*
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.files.actions.PathActionIssue
import eu.darken.butler.common.issue.Issue
import eu.darken.butler.explorer.core.ExplorerWorkspace
import eu.darken.butler.workspace.core.Workspace
import eu.darken.butler.workspace.core.operations.Operation
import eu.darken.butler.workspace.core.operations.OperationFocusRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach

/**
 * Conflict/issue sheet state for operations awaiting user resolution, including the
 * notification-driven ("tap to resolve") flow.
 */
class ExplorerOperationConflictController(
    private val workspaceId: Workspace.Id,
    private val pendingConflicts: Flow<Map<Operation.Id, Issue>>,
    private val operationFocusRequest: OperationFocusRequest,
    private val workspace: suspend () -> ExplorerWorkspace,
    private val doLaunch: (suspend CoroutineScope.() -> Unit) -> Unit,
    private val tag: String,
) {

    private val issueStateFlow = MutableStateFlow<Issue?>(null)
    val issueState: StateFlow<Issue?> = issueStateFlow

    // Durable (StateFlow) rather than a transient event so a notification-driven open isn't lost
    // if the page's collector subscribes after the request is issued.
    private val showIssueSheetFlow = MutableStateFlow(false)
    val showIssueSheet: StateFlow<Boolean> = showIssueSheetFlow

    private var currentConflictOperationId: Operation.Id? = null

    /**
     * A "tap to resolve" conflict notification routes here. Wait until the conflict is actually
     * present for this workspace before surfacing it, then consume the request.
     * The ViewModel launches this in its scope.
     */
    val focusRequestHandler = operationFocusRequest.requests
        .filterNotNull()
        .filter { it.workspaceId == workspaceId }
        .flatMapLatest { request ->
            pendingConflicts.map { request to it[request.operationId] }
        }
        .distinctUntilChanged()
        .onEach { (request, issue) ->
            if (issue != null) {
                currentConflictOperationId = request.operationId
                issueStateFlow.value = issue
                showIssueSheetFlow.value = true
                operationFocusRequest.consume(request)
            }
        }

    fun resolve(resolution: PathActionIssue.Resolution) = doLaunch {
        log(tag) { "resolveConflict(): $resolution" }

        val operationId = currentConflictOperationId
        if (operationId != null) {
            // Forward resolution to workspace
            workspace().resolveConflict(operationId, resolution)
        } else {
            log(tag, WARN) { "Cannot resolve conflict: no current operation ID" }
        }

        // Clear conflict UI state (it will be updated by workspace state observer if needed)
        issueStateFlow.value = null
        currentConflictOperationId = null
        showIssueSheetFlow.value = false
    }

    fun showSheet(operationId: Operation.Id) = doLaunch {
        log(tag) { "showConflictSheet($operationId): Requesting to show conflict sheet" }

        // Get current conflicts map
        val conflicts = pendingConflicts.first()
        val issue = conflicts[operationId]

        if (issue != null) {
            currentConflictOperationId = operationId
            issueStateFlow.value = issue
            showIssueSheetFlow.value = true
        } else {
            log(tag, WARN) { "Cannot show conflict sheet: no conflict for operation $operationId" }
        }
    }

    fun dismissSheet() {
        log(tag) { "dismissConflictSheet()" }
        showIssueSheetFlow.value = false
        issueStateFlow.value = null
        currentConflictOperationId = null
    }

    fun onCleared() {
        operationFocusRequest.clearForWorkspace(workspaceId)
    }
}
