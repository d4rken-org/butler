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
import eu.darken.butler.common.ui.ViewModel4
import eu.darken.butler.explorer.core.arguments.ExplorerArguments
import eu.darken.butler.explorer.core.picker.PickerConfig
import eu.darken.butler.saver.core.ContentUriHelper
import eu.darken.butler.saver.core.SaverWorkspace
import eu.darken.butler.saver.core.operations.SaveFilesReport
import eu.darken.butler.workspace.core.Workspace
import eu.darken.butler.workspace.core.WorkspaceAction
import eu.darken.butler.workspace.core.WorkspaceEvent
import eu.darken.butler.workspace.core.WorkspaceProvider
import eu.darken.butler.workspace.core.WorkspaceRemote
import eu.darken.butler.workspace.core.createAndFocus
import eu.darken.butler.workspace.core.handleResult
import eu.darken.butler.workspace.core.launchPicker
import eu.darken.butler.workspace.core.operations.Operation
import eu.darken.butler.workspace.ui.operations.OperationDisplay
import eu.darken.butler.workspace.ui.operations.toDisplayModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlin.time.Instant

@HiltViewModel(assistedFactory = SaverWorkspaceViewModel.Factory::class)
class SaverWorkspaceViewModel @AssistedInject constructor(
    @Assisted private val id: Workspace.Id,
    dispatchers: DispatcherProvider,
    workspaceProvider: WorkspaceProvider,
    private val workspaceRemote: WorkspaceRemote,
) : ViewModel4(dispatchers, logTag("Saver", "Workspace", id.shortTag, "Page")) {

    private val workspaceSource: Flow<SaverWorkspace?> =
        workspaceProvider.retrieve(id)
            .map { workspace: Workspace<out Workspace.Arguments>? -> workspace as? SaverWorkspace }

    private suspend fun getWorkspace(): SaverWorkspace = workspaceSource.filterNotNull().first()

    // Issue handling state
    private val issueStateFlow = MutableStateFlow<Issue?>(null)
    val issueState = issueStateFlow.asStateFlow()
    private var currentConflictOperationId: Operation.Id? = null
    private val showIssueSheetFlow = MutableSharedFlow<Unit>()
    val showIssueSheetEvent = showIssueSheetFlow.asSharedFlow()

    data class State(
        val sourceInfos: List<ContentUriHelper.SourceInfo> = emptyList(),
        val destination: APath<*>? = null,
        val filename: String = "",
        val saveState: SaverWorkspace.SaveState = SaverWorkspace.SaveState.Idle,
        val callerLabel: String? = null,
        val createdAt: Instant? = null,
        val operationDisplay: OperationDisplay? = null,
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
            ) { wsState, managedOp ->
                State(
                    sourceInfos = wsState.sourceInfos,
                    destination = wsState.destination,
                    filename = wsState.filename,
                    saveState = wsState.saveState,
                    callerLabel = wsState.callerLabel,
                    createdAt = wsState.createdAt,
                    operationDisplay = managedOp?.toDisplayModel(),
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

        // Observe pending conflicts from workspace and update UI state
        workspaceSource
            .filterNotNull()
            .flatMapLatest { it.operations }
            .map { it.pendingConflicts }
            .distinctUntilChanged()
            .onEach { conflicts ->
                val firstConflictEntry = conflicts.entries.firstOrNull()
                if (firstConflictEntry != null) {
                    val (operationId, issue) = firstConflictEntry
                    currentConflictOperationId = operationId
                    issueStateFlow.value = issue
                } else {
                    currentConflictOperationId = null
                    issueStateFlow.value = null
                }
            }
            .launchIn(vmScope)
    }

    fun onPickDestination() = launch {
        log(tag) { "onPickDestination()" }
        val workspace = getWorkspace()
        val wsState = workspace.state.first()
        val currentDestination = wsState.destination

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
            startPath = currentDestination,
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
                arguments = ExplorerArguments.Default(startPath = firstSavedPath.parent),
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

    fun onClose() = launch {
        log(tag) { "onClose()" }
        workspaceRemote.execute(WorkspaceAction.Close(id))
    }

    fun resolveConflict(resolution: PathActionIssue.Resolution) = launch {
        log(tag) { "resolveConflict(): $resolution" }

        val operationId = currentConflictOperationId
        if (operationId != null) {
            getWorkspace().resolveConflict(operationId, resolution)
        } else {
            log(tag, WARN) { "Cannot resolve conflict: no current operation ID" }
        }

        // Clear conflict UI state
        issueStateFlow.value = null
        currentConflictOperationId = null
    }

    fun showConflictSheet(operationId: Operation.Id) = launch {
        log(tag) { "showConflictSheet($operationId)" }
        val workspace = getWorkspace()
        val conflict = workspace.operations.first().pendingConflicts[operationId]
        if (conflict != null) {
            currentConflictOperationId = operationId
            issueStateFlow.value = conflict
            showIssueSheetFlow.emit(Unit)
        } else {
            log(tag, WARN) { "Cannot show conflict sheet: no conflict for operation $operationId" }
        }
    }

    @AssistedFactory
    interface Factory {
        fun create(id: Workspace.Id): SaverWorkspaceViewModel
    }
}
