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
import eu.darken.butler.common.error.ErrorIncidentStore
import eu.darken.butler.common.error.ErrorReportPackager
import eu.darken.butler.common.error.ErrorReportTool
import eu.darken.butler.common.flow.SingleEventFlow
import eu.darken.butler.common.flow.replayingShare
import eu.darken.butler.common.issue.Issue
import eu.darken.butler.workspace.contracts.history.HistoryArguments
import eu.darken.butler.workspace.core.Workspace
import eu.darken.butler.workspace.core.WorkspaceAction
import eu.darken.butler.workspace.core.WorkspaceRemote
import eu.darken.butler.workspace.core.createAndFocus
import eu.darken.butler.workspace.core.clipboard.ClipboardClip
import eu.darken.butler.workspace.core.clipboard.ClipboardRepo
import eu.darken.butler.workspace.core.operations.Operation
import eu.darken.butler.workspace.core.operations.OperationErrorRecorder
import eu.darken.butler.workspace.core.operations.OperationsManager
import eu.darken.butler.workspace.core.operations.history.HistorySettings
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
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.job
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
    private val errorIncidentStore: ErrorIncidentStore,
    private val systemClipboardHelper: SystemClipboardHelper,
    private val workspaceRemote: WorkspaceRemote,
    private val historySettings: HistorySettings,
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

    init {
        // The consent can die with its holder (activity destroyed, tab closed from another pane),
        // so neither confirm nor dismiss runs and the hold would outlive the process' need for it.
        scope.coroutineContext.job.invokeOnCompletion {
            _pendingErrorShare.getAndUpdate { null }?.let { errorIncidentStore.unpin(it.incident) }
        }
    }

    val clipboard: Flow<ClipboardDisplayState> = clipboardRepo.state
        .map { repoState -> ClipboardDisplayState(entries = repoState.entries) }
        .distinctUntilChanged()

    // replayingShare, NOT shareLatest: withStateUpdates() re-emits the SAME list instance on
    // operation state changes, and shareLatest's stateIn conflates equal values - state-only
    // transitions (Queued->Running->Waiting->Completed) would never reach collectors.
    private val managedOps = operationsManager.operationsForWorkspace(workspaceId)
        .withStateUpdates()
        .replayingShare(scope)

    val operations: Flow<OperationsDisplayState> = combine(
        managedOps.toOperationsDisplayState(),
        historySettings.saveHistory.flow,
    ) { state, historyEnabled -> state.copy(historyEnabled = historyEnabled) }

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
     * Opens a History tab on the operation's own entry. The entry is keyed by the operation id, and
     * the tab waits for it, so this also works right as the operation finishes.
     */
    fun showOperationInHistory(id: Operation.Id) {
        log(tag) { "showOperationInHistory($id)" }
        scope.launch {
            workspaceRemote.createAndFocus(
                type = Workspace.Type.HISTORY,
                arguments = HistoryArguments.Default(focusEntryId = id.longTag),
                sourceWorkspaceId = workspaceId,
            )
        }
    }

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

            val incident = errorIncidentStore.getOrFreeze(
                error = error,
                context = OperationErrorRecorder.operationContext(operation.id, operation.metadata, state),
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
        // Pinned for as long as the consent holds it: the store evicts at 32 entries and takes the
        // evicted incident's log trail with it, which the packager still has to read.
        errorIncidentStore.pin(incident)
        val replaced = _pendingErrorShare.getAndUpdate {
            PendingErrorShare(incident = incident, summary = summary)
        }
        // Released even when it names the same incident: replacing a consent hands over one hold,
        // while a confirmed share still packaging that incident keeps its own.
        replaced?.let { errorIncidentStore.unpin(it.incident) }
    }

    /**
     * Takes the pending share and clears it in one step, so a double tap on the consent packages
     * once instead of twice.
     */
    fun confirmErrorShare() {
        val pending = _pendingErrorShare.getAndUpdate { null } ?: return
        log(tag, INFO) { "confirmErrorShare(${pending.incident.incidentId})" }
        val packaging = scope.launch {
            val packaged = errorReportPackager.packageReport(pending.incident, pending.summary)
            shareIntentEvent.tryEmit(errorReportTool.createShareChooserIntent(packaged))
        }
        // On the job, not in a finally: the take above puts this hold out of reach of the scope
        // handler, and a coroutine cancelled before it starts never runs its own body.
        packaging.invokeOnCompletion { errorIncidentStore.unpin(pending.incident) }
    }

    fun dismissErrorShare() {
        log(tag) { "dismissErrorShare()" }
        _pendingErrorShare.getAndUpdate { null }?.let { errorIncidentStore.unpin(it.incident) }
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
