package eu.darken.butler.workspace.ui.page

import android.content.Context
import android.content.Intent
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.qualifiers.ApplicationContext
import eu.darken.butler.common.SystemClipboardHelper
import eu.darken.butler.common.debug.logging.Logging.Priority.*
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.debug.logging.logTag
import eu.darken.butler.common.error.ErrorIncident
import eu.darken.butler.common.error.ErrorIncidentFactory
import eu.darken.butler.common.error.ErrorReportPackager
import eu.darken.butler.common.error.ErrorReportTool
import eu.darken.butler.common.flow.SingleEventFlow
import eu.darken.butler.common.flow.replayingShare
import eu.darken.butler.common.issue.Issue
import eu.darken.butler.workspace.core.Workspace
import eu.darken.butler.workspace.core.WorkspaceAction
import eu.darken.butler.workspace.core.WorkspaceRemote
import eu.darken.butler.workspace.core.clipboard.ClipboardClip
import eu.darken.butler.workspace.core.clipboard.ClipboardRepo
import eu.darken.butler.workspace.core.operations.Operation
import eu.darken.butler.workspace.core.operations.OperationsManager
import eu.darken.butler.workspace.core.operations.get
import eu.darken.butler.workspace.core.operations.operationsForWorkspace
import eu.darken.butler.workspace.core.operations.withStateUpdates
import eu.darken.butler.workspace.ui.clipboard.ClipboardDisplayState
import eu.darken.butler.workspace.ui.operations.OperationsDisplayState
import eu.darken.butler.workspace.ui.operations.toOperationsDisplayState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Shared per-workspace "page chrome": clipboard display state, operation display state and
 * actions, error-report sharing and workspace close. Workspace ViewModels create one instance
 * with their [Workspace.Id] and `vmScope` (whose context carries the VM's error handler) and
 * delegate the corresponding calls to it.
 */
class WorkspacePageChrome @AssistedInject constructor(
    @Assisted private val workspaceId: Workspace.Id,
    @Assisted private val scope: CoroutineScope,
    @ApplicationContext private val context: Context,
    private val clipboardRepo: ClipboardRepo,
    private val operationsManager: OperationsManager,
    private val errorReportTool: ErrorReportTool,
    private val errorReportPackager: ErrorReportPackager,
    private val errorIncidentFactory: ErrorIncidentFactory,
    private val systemClipboardHelper: SystemClipboardHelper,
    private val workspaceRemote: WorkspaceRemote,
) {

    private val tag = logTag("Workspace", "PageChrome", workspaceId.shortTag)

    val shareIntentEvent = SingleEventFlow<Intent>()

    private val _pendingErrorShare = MutableStateFlow<PendingErrorShare?>(null)

    /** A frozen incident waiting for the user to consent to sharing it. */
    val pendingErrorShare: StateFlow<PendingErrorShare?> = _pendingErrorShare.asStateFlow()

    data class PendingErrorShare(
        val incident: ErrorIncident,
        val summary: String?,
    )

    val clipboard: Flow<ClipboardDisplayState> = clipboardRepo.state
        .map { repoState -> ClipboardDisplayState(entries = repoState.entries) }
        .distinctUntilChanged()

    // replayingShare, NOT shareLatest: withStateUpdates() re-emits the SAME list instance on
    // operation state changes, and shareLatest's stateIn conflates equal values - state-only
    // transitions (Queued->Running->Waiting->Completed) would never reach collectors.
    private val managedOps = operationsManager.operationsForWorkspace(workspaceId)
        .withStateUpdates()
        .replayingShare(scope)

    val operations: Flow<OperationsDisplayState> = managedOps.toOperationsDisplayState()

    val pendingConflicts: Flow<Map<Operation.Id, Issue>> = managedOps
        .map { ops ->
            ops.mapNotNull { op ->
                (op.state.value as? Operation.State.Waiting)?.let { op.id to it.issue }
            }.toMap()
        }
        .distinctUntilChanged()

    fun removeClipboardEntry(clip: ClipboardClip) {
        log(tag) { "removeClipboardEntry($clip)" }
        scope.launch { clipboardRepo.remove(clip.id) }
    }

    fun clearClipboard() {
        log(tag) { "clearClipboard()" }
        scope.launch { clipboardRepo.clear() }
    }

    fun copyToSystemClipboard(text: String) {
        log(tag) { "copyToSystemClipboard($text)" }
        systemClipboardHelper.copyToClipboard(text)
    }

    fun cancelOperation(id: Operation.Id) {
        log(tag) { "cancelOperation($id)" }
        scope.launch { operationsManager.cancel(id) }
    }

    fun dismissOperation(id: Operation.Id) {
        log(tag) { "dismissOperation($id)" }
        scope.launch { operationsManager.remove(id) }
    }

    // Clears completed operations across ALL workspaces (existing OperationsManager semantics,
    // intentionally preserved).
    fun clearCompletedOperations() {
        log(tag) { "clearCompletedOperations()" }
        scope.launch { operationsManager.clearCompleted() }
    }

    /**
     * The operation carries the moment it failed, so this is the one site whose incident timestamp
     * is exact rather than the moment the user reached for Share.
     */
    fun shareOperationError(id: Operation.Id) {
        log(tag) { "shareOperationError($id)" }
        scope.launch {
            val operation = operationsManager.get(id)
            if (operation == null) {
                log(tag, ERROR) { "Operation with id $id not found" }
                return@launch
            }
            val state = operation.state.value as? Operation.State.Completed ?: return@launch
            val error = state.error ?: return@launch

            val incident = errorIncidentFactory.freeze(
                error = error,
                context = mapOf(
                    "op.id" to operation.id.toString(),
                    "op.origin" to operation.metadata.origin.toString(),
                    "op.completedAt" to state.completedAt.toString(),
                ),
                occurredAt = state.completedAt,
            )
            requestErrorShare(
                incident = incident,
                summary = "${operation.metadata.title.get(context)}\n${operation.metadata.description.get(context)}",
            )
        }
    }

    fun shareWorkspaceError(incident: ErrorIncident, summary: String? = null) {
        log(tag, INFO) { "shareWorkspaceError(${incident.incidentId}): ${incident.error.message}" }
        requestErrorShare(incident, summary)
    }

    private fun requestErrorShare(incident: ErrorIncident, summary: String?) {
        _pendingErrorShare.value = PendingErrorShare(incident = incident, summary = summary)
    }

    /**
     * Takes the pending share and clears it in one step, so a double tap on the consent packages
     * once instead of twice.
     */
    fun confirmErrorShare() {
        val pending = _pendingErrorShare.getAndUpdate { null } ?: return
        log(tag, INFO) { "confirmErrorShare(${pending.incident.incidentId})" }
        scope.launch {
            val packaged = errorReportPackager.packageReport(pending.incident, pending.summary)
            shareIntentEvent.tryEmit(errorReportTool.createShareChooserIntent(packaged))
        }
    }

    fun dismissErrorShare() {
        log(tag) { "dismissErrorShare()" }
        _pendingErrorShare.value = null
    }

    fun closeWorkspace() {
        log(tag, INFO) { "closeWorkspace()" }
        scope.launch { workspaceRemote.execute(WorkspaceAction.Close(workspaceId)) }
    }

    @AssistedFactory
    interface Factory {
        fun create(workspaceId: Workspace.Id, scope: CoroutineScope): WorkspacePageChrome
    }
}
