package eu.darken.butler.editor.ui.editor

import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import eu.darken.butler.common.coroutine.DispatcherProvider
import eu.darken.butler.common.debug.logging.Logging
import eu.darken.butler.common.debug.logging.asLog
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.debug.logging.logTag
import eu.darken.butler.common.files.APath
import eu.darken.butler.common.navigation.NavigationController
import eu.darken.butler.common.ui.ViewModel4
import eu.darken.butler.editor.core.EditorSettings
import eu.darken.butler.editor.core.EditorWorkspace
import eu.darken.butler.editor.core.engine.FileInfo
import eu.darken.butler.editor.core.engine.SearchResult
import eu.darken.butler.editor.core.engine.TextPosition
import eu.darken.butler.workspace.core.Workspace
import eu.darken.butler.workspace.core.WorkspaceEvent
import eu.darken.butler.workspace.core.WorkspaceProvider
import eu.darken.butler.workspace.core.WorkspaceRemote
import eu.darken.butler.workspace.core.handleResult
import eu.darken.butler.workspace.core.launchPicker
import eu.darken.butler.explorer.core.picker.PickerConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach

@HiltViewModel(assistedFactory = EditorWorkspaceViewModel.Factory::class)
class EditorWorkspaceViewModel @AssistedInject constructor(
    @Assisted private val id: Workspace.Id,
    dispatchers: DispatcherProvider,
    navCtrl: NavigationController,
    private val workspaceProvider: WorkspaceProvider,
    private val workspaceRemote: WorkspaceRemote,
    private val editorSettings: EditorSettings,
) : ViewModel4(dispatchers, logTag("Editor", "Workspace", id.shortTag, "Page"), navCtrl) {

    private val workspaceFlow = flow {
        emit(workspaceProvider.retrieve(id))
    }.flatMapLatest { it }

    private val _isLoading = MutableStateFlow(true)
    private var currentWorkspace: EditorWorkspace? = null

    val state = workspaceFlow
        .flatMapLatest { workspace ->
            if (workspace is EditorWorkspace) {
                combine(
                    workspace.editorState,
                    _isLoading,
                    flowOf(id),
                ) { editorState, isLoading, workspaceId ->
                    State(
                        id = workspaceId,
                        fileInfo = editorState.fileInfo,
                        totalLines = editorState.totalLines,
                        isModified = editorState.isModified,
                        currentContent = editorState.currentContent,
                        cursorPosition = editorState.cursorPosition,
                        selectionRange = editorState.selectionRange,
                        isLoading = isLoading,
                        error = editorState.error,
                        searchQuery = editorState.searchQuery,
                        searchResults = editorState.searchResults,
                        visibleRange = editorState.visibleRange,
                        showLineNumbers = editorState.showLineNumbers,
                        wordWrap = editorState.wordWrap,
                        hasWorkspace = true
                    )
                }
            } else {
                flowOf(
                    State(
                        id = id,
                        hasWorkspace = false,
                        isLoading = true
                    )
                )
            }
        }
        .catch { e ->
            log(tag, Logging.Priority.ERROR) { "Failed to monitor workspace state - ${e.asLog()}" }
            emit(
                State(
                    id = id,
                    hasWorkspace = false,
                    error = e
                )
            )
        }
        .asStateFlow()

    init {
        workspaceFlow
            .onEach { workspace ->
                if (workspace != null) {
                    log(tag) { "Workspace available: ${workspace.id}" }
                    currentWorkspace = workspace as? EditorWorkspace
                    _isLoading.value = false
                } else {
                    log(tag) { "Workspace removed" }
                    currentWorkspace = null
                    // Navigate back when workspace is removed
                    navUp()
                }
            }
            .catch { e ->
                log(tag, Logging.Priority.ERROR) { "Failed to monitor workspace - ${e.asLog()}" }
            }
            .launchInViewModel()

        // Listen for picker results
        workspaceRemote.events
            .handleResult<WorkspaceEvent.PickerResult>(callerWorkspaceId = id) { result ->
                log(tag) { "Received picker result: ${result.selectedPaths.firstOrNull()}" }
                result.selectedPaths.firstOrNull()?.let { openFile(it) }
            }
            .launchIn(vmScope)
    }

    // All operations delegate to workspace

    fun launchFilePicker() = launch {
        val currentPath = state.first().fileInfo?.path?.parent
        workspaceRemote.launchPicker(id, currentPath, PickerConfig.Selection.FileSingle)
    }

    fun openFile(filePath: APath<*>) {
        launch {
            try {
                _isLoading.value = true
                val workspace = workspaceFlow.first() as? EditorWorkspace
                workspace?.openFile(filePath)
            } catch (e: Exception) {
                log(tag, Logging.Priority.ERROR) { "Failed to open file: $filePath - ${e.asLog()}" }
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun closeFile() {
        launch {
            try {
                _isLoading.value = true
                val workspace = workspaceFlow.first() as? EditorWorkspace
                workspace?.closeFile()
            } catch (e: Exception) {
                log(tag, Logging.Priority.ERROR) { "Failed to close file - ${e.asLog()}" }
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun saveFile() {
        launch {
            try {
                _isLoading.value = true
                val workspace = workspaceFlow.first() as? EditorWorkspace
                workspace?.saveFile()
            } catch (e: Exception) {
                log(tag, Logging.Priority.ERROR) { "Failed to save file - ${e.asLog()}" }
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun updateVisibleRange(startLine: Int, endLine: Int) = launch {
        val workspace = getCurrentWorkspace()
        workspace?.updateVisibleRange(startLine, endLine)
    }

    fun insertText(text: String) {
        launch {
            try {
                val workspace = workspaceFlow.first() as? EditorWorkspace
                workspace?.insertText(text)
            } catch (e: Exception) {
                log(tag, Logging.Priority.ERROR) { "Failed to insert text - ${e.asLog()}" }
            }
        }
    }

    fun deleteSelection() {
        launch {
            try {
                val workspace = workspaceFlow.first() as? EditorWorkspace
                workspace?.deleteSelection()
            } catch (e: Exception) {
                log(tag, Logging.Priority.ERROR) { "Failed to delete selection - ${e.asLog()}" }
            }
        }
    }

    fun setCursorPosition(position: TextPosition) {
        val workspace = getCurrentWorkspace()
        workspace?.setCursorPosition(position)
    }

    fun setSelection(start: TextPosition, end: TextPosition) {
        val workspace = getCurrentWorkspace()
        workspace?.setSelection(start, end)
    }

    fun search(query: String) {
        launch {
            try {
                val workspace = workspaceFlow.first() as? EditorWorkspace
                workspace?.search(query)
            } catch (e: Exception) {
                log(tag, Logging.Priority.ERROR) { "Failed to search - ${e.asLog()}" }
            }
        }
    }

    fun goToLine(lineNumber: Int) {
        launch {
            try {
                val workspace = workspaceFlow.first() as? EditorWorkspace
                workspace?.goToLine(lineNumber)
            } catch (e: Exception) {
                log(tag, Logging.Priority.ERROR) { "Failed to go to line: $lineNumber - ${e.asLog()}" }
            }
        }
    }

    fun undo() {
        launch {
            try {
                val workspace = workspaceFlow.first() as? EditorWorkspace
                workspace?.undo()
            } catch (e: Exception) {
                log(tag, Logging.Priority.ERROR) { "Failed to undo - ${e.asLog()}" }
            }
        }
    }

    fun redo() {
        launch {
            try {
                val workspace = workspaceFlow.first() as? EditorWorkspace
                workspace?.redo()
            } catch (e: Exception) {
                log(tag, Logging.Priority.ERROR) { "Failed to redo - ${e.asLog()}" }
            }
        }
    }

    fun clearError() {
        val workspace = getCurrentWorkspace()
        workspace?.clearError()
    }

    private fun getCurrentWorkspace(): EditorWorkspace? {
        return currentWorkspace
    }

    /**
     * Unified handler for all page-level actions.
     * Dispatches to appropriate ViewModel methods based on action type.
     */
    fun onPageAction(action: EditorPageAction) {
        when (action) {
            // File actions
            is EditorPageAction.File.LaunchPicker -> launchFilePicker()
            is EditorPageAction.File.Open -> openFile(action.path)
            is EditorPageAction.File.Save -> saveFile()
            is EditorPageAction.File.Close -> closeFile()

            // Edit actions
            is EditorPageAction.Edit.InsertText -> insertText(action.text)
            is EditorPageAction.Edit.DeleteSelection -> deleteSelection()
            is EditorPageAction.Edit.Undo -> undo()
            is EditorPageAction.Edit.Redo -> redo()

            // Navigation actions
            is EditorPageAction.Navigation.SetCursor -> setCursorPosition(action.position)
            is EditorPageAction.Navigation.SetSelection -> setSelection(action.start, action.end)
            is EditorPageAction.Navigation.ClearSelection -> setCursorPosition(action.cursorPosition)
            is EditorPageAction.Navigation.Search -> search(action.query)
            is EditorPageAction.Navigation.GoToLine -> goToLine(action.lineNumber)
            is EditorPageAction.Navigation.UpdateVisibleRange -> updateVisibleRange(action.startLine, action.endLine)

            // Error actions
            is EditorPageAction.Error.Clear -> clearError()
        }
    }

    data class State(
        val id: Workspace.Id,
        val fileInfo: FileInfo? = null,
        val totalLines: Int = 0,
        val isModified: Boolean = false,
        val currentContent: String = "",
        val cursorPosition: TextPosition = TextPosition.Companion.ZERO,
        val selectionRange: Pair<TextPosition, TextPosition>? = null,
        val isLoading: Boolean = false,
        val error: Throwable? = null,
        val searchQuery: String = "",
        val searchResults: List<SearchResult> = emptyList(),
        val visibleRange: IntRange = 0..50,
        val showLineNumbers: Boolean = true,
        val wordWrap: Boolean = false,
        val hasWorkspace: Boolean = true
    ) {
        val hasFile: Boolean get() = fileInfo != null
        val fileName: String get() = fileInfo?.path?.name ?: "Untitled"
        val hasSelection: Boolean get() = selectionRange != null
        val hasSearchResults: Boolean get() = searchResults.isNotEmpty()
        val isSearchActive: Boolean get() = searchQuery.isNotEmpty()
        val hasError: Boolean get() = error != null
    }

    @AssistedFactory
    interface Factory {
        fun create(id: Workspace.Id): EditorWorkspaceViewModel
    }
}