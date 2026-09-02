package eu.darken.butler.saver.ui.saver

import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import eu.darken.butler.common.coroutine.DispatcherProvider
import eu.darken.butler.common.debug.logging.Logging.Priority.*
import eu.darken.butler.common.debug.logging.asLog
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.debug.logging.logTag
import eu.darken.butler.common.files.APath
import eu.darken.butler.common.files.actions.PathActionIssue
import eu.darken.butler.common.issue.Issue
import eu.darken.butler.common.navigation.NavEvent
import eu.darken.butler.common.pkgs.Pkg
import eu.darken.butler.common.storage.StorageEnvironment
import eu.darken.butler.common.ui.ViewModel4
import eu.darken.butler.saver.core.ContentUriHelper
import eu.darken.butler.saver.core.SaverWorkspace
import eu.darken.butler.saver.core.operations.SaveFilesReport
import eu.darken.butler.workspace.contracts.explorer.ExplorerArguments
import eu.darken.butler.workspace.contracts.explorer.PickerConfig
import eu.darken.butler.workspace.core.Workspace
import eu.darken.butler.workspace.core.WorkspaceAction
import eu.darken.butler.workspace.core.WorkspaceEvent
import eu.darken.butler.workspace.core.WorkspaceProvider
import eu.darken.butler.workspace.core.WorkspaceRemote
import eu.darken.butler.workspace.core.createAndFocus
import eu.darken.butler.workspace.core.handleResult
import eu.darken.butler.workspace.core.launchPicker
import eu.darken.butler.workspace.core.operations.Operation
import eu.darken.butler.workspace.core.operations.OperationFocusRequest
import eu.darken.butler.workspace.ui.operations.OperationDisplay
import eu.darken.butler.workspace.ui.operations.toDisplayModel
import eu.darken.butler.workspace.ui.page.WorkspacePageChrome
import kotlin.time.Instant
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update

@HiltViewModel(assistedFactory = SaverWorkspaceViewModel.Factory::class)
class SaverWorkspaceViewModel @AssistedInject constructor(
    @Assisted private val id: Workspace.Id,
    dispatchers: DispatcherProvider,
    workspaceProvider: WorkspaceProvider,
    private val workspaceRemote: WorkspaceRemote,
    private val storageEnvironment: StorageEnvironment,
    private val operationFocusRequest: OperationFocusRequest,
    chromeFactory: WorkspacePageChrome.Factory,
) : ViewModel4(dispatchers, logTag("Saver", "Workspace", id.shortTag, "Page")) {

    private val chrome = chromeFactory.create(id, vmScope)

    private val workspaceSource: Flow<SaverWorkspace?> =
        workspaceProvider.retrieve(id)
            .map { workspace: Workspace<out Workspace.Arguments>? -> workspace as? SaverWorkspace }

    private suspend fun getWorkspace(): SaverWorkspace = workspaceSource.filterNotNull().first()

    val shareIntentEvent = chrome.shareIntentEvent

    val pendingErrorShare = chrome.pendingErrorShare

    // Conflict sheet UI state — one atomic holder so the pending-conflict observer, the
    // notification-focus path, manual taps, auto-surface, resolve and dismiss can never interleave
    // into an inconsistent (operation, issue, visibility) combination.
    // Durable (StateFlow) so a notification-driven open isn't lost if the page subscribes late.
    private val conflictUiStateFlow = MutableStateFlow(ConflictUiState())
    val conflictUiState: StateFlow<ConflictUiState> = conflictUiStateFlow

    /** Atomic view of the conflict bottom sheet: which operation, which issue, and whether it shows. */
    data class ConflictUiState(
        val operationId: Operation.Id? = null,
        val issue: Issue? = null,
        val visible: Boolean = false,
    ) {
        /** Stable identity of the shown conflict; null when nothing is pending. */
        val key: Pair<Operation.Id, Issue.Id>?
            get() = operationId?.let { op -> issue?.id?.let { op to it } }
    }

    data class State(
        val sourceInfos: List<ContentUriHelper.SourceInfo> = emptyList(),
        val destination: APath<*>? = null,
        val filename: String = "",
        val saveState: SaverWorkspace.SaveState = SaverWorkspace.SaveState.Idle,
        val callerLabel: String? = null,
        val callerPackage: Pkg.Id? = null,
        val createdAt: Instant? = null,
        val operationDisplay: OperationDisplay? = null,
        /** True when launched by another workspace (APK export): render as a modal, not an app-share tab. */
        val isModal: Boolean = false,
        val historyEnabled: Boolean = false,
    ) {
        val isBatchMode: Boolean
            get() = sourceInfos.size > 1

        val fileCount: Int
            get() = sourceInfos.size

        val totalSize: Long?
            get() = sourceInfos.mapNotNull { it.size }.takeIf { it.size == sourceInfos.size }?.sum()

        val hasInaccessibleFiles: Boolean
            get() = sourceInfos.any { !it.isAccessible }

        val inaccessibleFileCount: Int
            get() = sourceInfos.count { !it.isAccessible }

        val canSave: Boolean
            get() = destination != null
                && !hasInaccessibleFiles
                && sourceInfos.isNotEmpty()
                && (isBatchMode || filename.isNotBlank())
                && saveState is SaverWorkspace.SaveState.Idle

        val isSaving: Boolean
            get() = saveState is SaverWorkspace.SaveState.Saving

        val isCompleted: Boolean
            get() = saveState is SaverWorkspace.SaveState.Success

        val hasError: Boolean
            get() = saveState is SaverWorkspace.SaveState.Error

        val report: SaveFilesReport?
            get() = (saveState as? SaverWorkspace.SaveState.Success)?.report

        val error: Throwable?
            get() = (saveState as? SaverWorkspace.SaveState.Error)?.error
    }

    val state: Flow<State> = workspaceSource
        .filterNotNull()
        .flatMapLatest { workspace ->
            combine(
                workspace.state,
                workspace.currentOperation,
                chrome.operations,
            ) { wsState, managedOp, chromeOps ->
                State(
                    sourceInfos = wsState.sourceInfos,
                    destination = wsState.destination,
                    filename = wsState.filename,
                    saveState = wsState.saveState,
                    callerLabel = wsState.callerLabel,
                    callerPackage = wsState.callerPackage,
                    createdAt = wsState.createdAt,
                    operationDisplay = managedOp?.toDisplayModel(),
                    isModal = wsState.callerWorkspaceId != null,
                    historyEnabled = chromeOps.historyEnabled,
                )
            }
        }

    init {
        // Listen for picker results
        workspaceRemote.events
            .handleResult<WorkspaceEvent.PickerResult>(callerWorkspaceId = id) { result ->
                log(tag, INFO) { "Received picker result: paths=${result.selectedPaths}, filename=${result.filename}" }
                result.selectedPaths.firstOrNull()?.let { path ->
                    val workspace = getWorkspace()
                    workspace.setDestination(path)

                    // Update filename if returned by SaveAs picker (single file mode only)
                    result.filename?.let { filename ->
                        workspace.updateFilename(filename)
                    }
                }
            }
            .launchIn(vmScope)

        // Track the pending conflict (any waiting issue) for the row/details and manual tap.
        // Visibility is owned by the auto-surface / notification / tap paths, so it is preserved here.
        chrome.pendingConflicts
            .onEach { conflicts ->
                val firstConflictEntry = conflicts.entries.firstOrNull()
                conflictUiStateFlow.update { current ->
                    if (firstConflictEntry == null) {
                        // Nothing pending -> clear identity and hide.
                        ConflictUiState()
                    } else {
                        val next = ConflictUiState(firstConflictEntry.key, firstConflictEntry.value)
                        // Preserve visibility ONLY while it's the same conflict; a different conflict
                        // must not inherit the previous one's visible=true (that would show issue X
                        // while a show-path had made visible=true for issue Y).
                        if (current.key == next.key) current else next
                    }
                }
            }
            .launchIn(vmScope)

        reportSavedPaths()

        // A "tap to resolve" conflict notification routes here. Wait until the conflict is present
        // for this workspace before surfacing the sheet, then consume the request.
        operationFocusRequest.requests
            .filterNotNull()
            .filter { it.workspaceId == id }
            .flatMapLatest { request ->
                chrome.pendingConflicts.map { request to it[request.operationId] }
            }
            .distinctUntilChanged()
            .onEach { (request, issue) ->
                if (issue != null) {
                    conflictUiStateFlow.value = ConflictUiState(request.operationId, issue, visible = true)
                    operationFocusRequest.consume(request)
                }
            }
            .launchIn(vmScope)
    }

    override fun onCleared() {
        operationFocusRequest.clearForWorkspace(id)
        super.onCleared()
    }

    /**
     * Tells the caller where a save landed, when it asked to be told.
     *
     * [emitEvent] rather than `returnResult`: that one closes the emitting workspace, which would
     * take the Saver's own "Open saved file" and "Save again" away the moment the save succeeded.
     * The event is informational and the Saver carries on exactly as before.
     *
     * Exactly one emission per save: the state identity dedupes, and [drop] discards the replayed
     * current value so a page that re-subscribes does not report a save that already happened. A
     * second Save-again cycle passes through Idle first and therefore reports again.
     */
    private fun reportSavedPaths() = launch {
        val workspace = getWorkspace()
        if (!workspace.reportSavedPaths) return@launch
        workspace.saveState
            .distinctUntilChanged()
            .drop(1)
            .filterIsInstance<SaverWorkspace.SaveState.Success>()
            .collect { success ->
                val savedPaths = success.report.successes.map { it.savedPath }
                log(tag, INFO) { "Reporting ${savedPaths.size} saved path(s) to ${workspace.callerWorkspaceId}" }
                workspaceRemote.emitEvent(
                    WorkspaceEvent.SaveResult(
                        workspaceId = id,
                        callerWorkspaceId = workspace.callerWorkspaceId,
                        savedPaths = savedPaths,
                    )
                )
            }
    }

    fun onPickDestination() = launch {
        log(tag) { "onPickDestination()" }
        val workspace = getWorkspace()
        val wsState = workspace.state.first()

        // Use current destination, or fall back to Downloads folder
        val startPath = wsState.destination ?: storageEnvironment.downloadsDirectory

        val selection = if (wsState.sourceInfos.size > 1) {
            // Batch mode: just pick directory
            PickerConfig.Selection.DirectorySingle
        } else {
            // Single file mode: use SaveAs
            PickerConfig.Selection.SaveAs(
                suggestedFilename = wsState.filename.ifBlank { "file" }
            )
        }

        workspaceRemote.launchPicker(
            callerWorkspaceId = id,
            startPath = startPath,
            selection = selection,
        )
    }

    fun onSave() = launch {
        log(tag, INFO) { "onSave()" }
        try {
            getWorkspace().save()
        } catch (e: Exception) {
            log(tag, ERROR) { "Save failed: ${e.asLog()}" }
        }
    }

    fun onOpenSavedFile() = launch {
        val workspace = getWorkspace()
        val report = (workspace.state.first().saveState as? SaverWorkspace.SaveState.Success)?.report
        val firstSavedPath = report?.successes?.firstOrNull()?.savedPath
        if (firstSavedPath != null) {
            log(tag, INFO) { "Opening saved file location: ${firstSavedPath.parent}" }
            workspaceRemote.createAndFocus(
                type = Workspace.Type.EXPLORER,
                // Straight to the file that was just written, not just to the folder holding it.
                arguments = ExplorerArguments.Default(
                    startPath = firstSavedPath.parent,
                    revealPath = firstSavedPath,
                ),
                sourceWorkspaceId = id,
            )
        }
    }

    fun onRetry() = launch {
        log(tag) { "onRetry()" }
        getWorkspace().resetSaveState()
    }

    fun onSaveAgain() = launch {
        log(tag) { "onSaveAgain()" }
        getWorkspace().resetSaveState()
    }

    fun onRefreshAccessibility() = launch {
        log(tag) { "onRefreshAccessibility()" }
        getWorkspace().refreshSourceAccessibility()
    }

    fun onUpdateFilename(filename: String) = launch {
        log(tag) { "onUpdateFilename($filename)" }
        getWorkspace().updateFilename(filename)
    }

    fun onClose() = chrome.closeWorkspace()

    fun onFinishApp() = launch {
        log(tag) { "onFinishApp() - closing workspace and finishing app" }
        workspaceRemote.execute(WorkspaceAction.Close(id))
        navEvents.tryEmit(NavEvent.Finish)
    }

    /**
     * Auto-open the conflict sheet when a NEW file-already-exists conflict appears — driven by the
     * Host only while the page is focused + resumed (see [SaverWorkspacePageHost]). Modal-only
     * (APK-export path); the share-intent tab never auto-opens.
     *
     * [drop] discards the conflict state present when this eligible collection begins, so a conflict
     * that arose while backgrounded / behind a deeper picker is treated as a baseline, not a new
     * transition, and does not pop on return. Only a genuine empty -> conflict transition surfaces.
     */
    suspend fun autoSurfaceModalConflicts() {
        // Immutable marker (safe before async init); the share-intent tab never auto-opens.
        if (getWorkspace().callerWorkspaceId == null) {
            log(tag) { "autoSurfaceModalConflicts(): non-modal saver, skipping" }
            return
        }
        chrome.pendingConflicts
            .map { conflicts ->
                conflicts.entries
                    .firstOrNull { it.value is PathActionIssue.PathAlreadyExists }
                    ?.let { it.key to it.value }
            }
            .distinctUntilChanged { old, new ->
                old?.first == new?.first && old?.second?.id == new?.second?.id
            }
            .drop(1)
            .collect { entry ->
                entry ?: return@collect
                val (operationId, issue) = entry
                log(tag, INFO) { "Auto-surfacing modal conflict sheet: op=$operationId" }
                conflictUiStateFlow.value = ConflictUiState(operationId, issue, visible = true)
            }
    }

    fun resolveConflict(resolution: PathActionIssue.Resolution) = launch {
        log(tag) { "resolveConflict(): $resolution" }

        val resolved = conflictUiStateFlow.value
        val operationId = resolved.operationId
        if (operationId == null) {
            log(tag, WARN) { "Cannot resolve conflict: no current operation ID" }
            return@launch
        }
        getWorkspace().resolveConflict(operationId, resolution)

        // Clear only if we're still showing the conflict we just resolved. A fast next-file conflict
        // may already have replaced it via the pending-conflict observer; don't clobber it.
        conflictUiStateFlow.update { current ->
            if (current.key == resolved.key) ConflictUiState() else current
        }
    }

    fun showConflictSheet(operationId: Operation.Id) = launch {
        log(tag) { "showConflictSheet($operationId)" }
        val conflict = chrome.pendingConflicts.first()[operationId]
        if (conflict != null) {
            conflictUiStateFlow.value = ConflictUiState(operationId, conflict, visible = true)
        } else {
            log(tag, WARN) { "Cannot show conflict sheet: no conflict for operation $operationId" }
        }
    }

    fun dismissConflictSheet() {
        log(tag) { "dismissConflictSheet()" }
        // Keep the pending conflict identity so the waiting row stays re-tappable; just hide the sheet.
        conflictUiStateFlow.update { it.copy(visible = false) }
    }

    fun shareError(operationId: Operation.Id) = chrome.shareOperationError(operationId)

    fun showOperationInHistory(operationId: Operation.Id) = chrome.showOperationInHistory(operationId)

    fun confirmErrorShare() = chrome.confirmErrorShare()

    fun dismissErrorShare() = chrome.dismissErrorShare()

    @AssistedFactory
    interface Factory {
        fun create(id: Workspace.Id): SaverWorkspaceViewModel
    }
}
