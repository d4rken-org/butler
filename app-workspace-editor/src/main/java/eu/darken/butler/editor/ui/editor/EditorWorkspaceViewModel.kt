package eu.darken.butler.editor.ui.editor

import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import eu.darken.butler.common.SystemClipboardHelper
import eu.darken.butler.common.ca.CaString
import eu.darken.butler.common.ca.toCaString
import eu.darken.butler.common.coroutine.DispatcherProvider
import eu.darken.butler.common.debug.logging.Logging.Priority.*
import eu.darken.butler.common.debug.logging.asLog
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.debug.logging.logTag
import eu.darken.butler.common.files.APath
import eu.darken.butler.common.ui.ViewModel4
import eu.darken.butler.editor.core.EditorSettings
import eu.darken.butler.editor.core.EditorWorkspace
import eu.darken.butler.editor.core.engine.FileInfo
import eu.darken.butler.editor.core.engine.SearchResult
import eu.darken.butler.editor.core.engine.TextPosition
import eu.darken.butler.explorer.core.picker.PickerConfig
import eu.darken.butler.workspace.core.Workspace
import eu.darken.butler.workspace.core.WorkspaceEvent
import eu.darken.butler.workspace.core.WorkspaceProvider
import eu.darken.butler.workspace.core.WorkspaceRemote
import eu.darken.butler.workspace.core.handleResult
import eu.darken.butler.workspace.core.launchPicker
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map

@HiltViewModel(assistedFactory = EditorWorkspaceViewModel.Factory::class)
class EditorWorkspaceViewModel @AssistedInject constructor(
    @Assisted private val id: Workspace.Id,
    dispatchers: DispatcherProvider,
    private val workspaceProvider: WorkspaceProvider,
    private val workspaceRemote: WorkspaceRemote,
    private val editorSettings: EditorSettings,
    private val clipboardHelper: SystemClipboardHelper,
) : ViewModel4(dispatchers, logTag("Editor", "Workspace", id.shortTag, "Page")) {

    private val workspaceSource: Flow<EditorWorkspace?> = workspaceProvider.retrieve(id).map { it as EditorWorkspace? }
    private suspend fun getWorkspace(): EditorWorkspace = workspaceSource.filterNotNull().first()

    private val _isLoading = MutableStateFlow(true)
    private val _showGoToLineDialog = MutableStateFlow(false)
    private val _showSearchDialog = MutableStateFlow(false)
    private val _searchQueryInput = MutableStateFlow("")
    private val _currentSearchResultIndex = MutableStateFlow(0)
    private val _searchCaseSensitive = MutableStateFlow(false)
    private var currentWorkspace: EditorWorkspace? = null

    private val dialogStates = combine(
        _showGoToLineDialog,
        _showSearchDialog,
    ) { showGoToLineDialog, showSearchDialog ->
        showGoToLineDialog to showSearchDialog
    }

    private val searchStates = combine(
        _searchQueryInput,
        _currentSearchResultIndex,
        _searchCaseSensitive,
    ) { searchQueryInput, currentSearchResultIndex, searchCaseSensitive ->
        Triple(searchQueryInput, currentSearchResultIndex, searchCaseSensitive)
    }

    val state = combine(
        workspaceSource.filterNotNull().flatMapLatest { it.editorState },
        _isLoading,
        dialogStates,
        searchStates,
        flowOf(id),
    ) { editorState, isLoading, dialogs, search, workspaceId ->
        val (showGoToLineDialog, showSearchDialog) = dialogs
        val (searchQueryInput, currentSearchResultIndex, searchCaseSensitive) = search

        State(
            id = workspaceId,
            fileInfo = editorState.fileInfo,
            title = editorState.fileInfo?.path?.userReadableName ?: "No File".toCaString(),
            subTitle = editorState.fileInfo?.path?.parent?.userReadablePath ?: "In-Memory-Buffer".toCaString(),
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
            showGoToLineDialog = showGoToLineDialog,
            showSearchDialog = showSearchDialog,
            searchQueryInput = searchQueryInput,
            currentSearchResultIndex = currentSearchResultIndex,
            searchCaseSensitive = searchCaseSensitive,
        )
    }
        .asStateFlow()

    init {
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
                getWorkspace().openFile(filePath)
            } catch (e: Exception) {
                log(tag, ERROR) { "Failed to open file: $filePath - ${e.asLog()}" }
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun closeFile() {
        launch {
            try {
                _isLoading.value = true
                getWorkspace().closeFile()
            } catch (e: Exception) {
                log(tag, ERROR) { "Failed to close file - ${e.asLog()}" }
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun saveFile() {
        launch {
            try {
                _isLoading.value = true
                getWorkspace().saveFile()
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun updateVisibleRange(startLine: Int, endLine: Int) = launch {
        getWorkspace().updateVisibleRange(startLine, endLine)
    }

    fun insertText(text: String) = launch {
        getWorkspace().insertText(text)
    }

    fun deleteSelection() = launch {
        getWorkspace().deleteSelection()
    }

    fun deleteAtCursor(count: Int) = launch {
        getWorkspace().deleteAtCursor(count)
    }

    fun copyToClipboard() = launch {
        val result = getWorkspace()?.copySelection()
        result?.fold(
            onSuccess = { text ->
                clipboardHelper.copyToClipboard(text)
                log(tag) { "Copied ${text.length} characters to clipboard" }
            },
            onFailure = { e ->
                log(tag, ERROR) { "Failed to copy selection - ${e.asLog()}" }
            }
        )
    }

    fun cutToClipboard() = launch {
        val workspace = getWorkspace()
        // First copy to clipboard
        val copyResult = getWorkspace().copySelection()
        copyResult.fold(
            onSuccess = { text ->
                clipboardHelper.copyToClipboard(text)
                // Then delete the selection
                workspace.deleteSelection()
                log(tag) { "Cut ${text.length} characters to clipboard" }
            },
            onFailure = { e ->
                log(tag, ERROR) { "Failed to cut selection - ${e.asLog()}" }
            }
        )
    }

    fun pasteFromClipboard() = launch {
        val text = clipboardHelper.getClipboardText()
        if (text != null) {
            getWorkspace().insertText(text)
            log(tag) { "Pasted ${text.length} characters from clipboard" }
        } else {
            log(tag) { "No text content in clipboard to paste" }
        }
    }

    fun selectAll() = launch {
        getWorkspace().selectAll()
    }

    fun setCursorPosition(position: TextPosition) = launch {
        getWorkspace().setCursorPosition(position)
    }


    fun setSelection(start: TextPosition, end: TextPosition) = launch {
        getWorkspace().setSelection(start, end)
    }

    private fun search(query: String, caseSensitive: Boolean) = launch {
        val result = getWorkspace().search(query, caseSensitive)
        result.onSuccess { searchResults ->
            // Auto-navigate to first result if available
            if (searchResults.isNotEmpty()) {
                _currentSearchResultIndex.value = 0
                getWorkspace().setCursorPosition(searchResults[0].position)
            } else {
                _currentSearchResultIndex.value = 0
            }
        }
    }

    fun updateSearchQuery(query: String) {
        _searchQueryInput.value = query
        // Trigger search immediately (debouncing can be added later if needed)
        if (query.isNotEmpty()) {
            search(query, _searchCaseSensitive.value)
        } else {
            // Clear results when query is empty
            launch {
                getWorkspace().search("", false)
                _currentSearchResultIndex.value = 0
            }
        }
    }

    fun nextSearchResult() = launch {
        val currentState = state.first()
        if (currentState.searchResults.isNotEmpty()) {
            val newIndex = (_currentSearchResultIndex.value + 1) % currentState.searchResults.size
            _currentSearchResultIndex.value = newIndex
            getWorkspace().setCursorPosition(currentState.searchResults[newIndex].position)
        }
    }

    fun previousSearchResult() = launch {
        val currentState = state.first()
        if (currentState.searchResults.isNotEmpty()) {
            val newIndex = if (_currentSearchResultIndex.value == 0) {
                currentState.searchResults.size - 1
            } else {
                _currentSearchResultIndex.value - 1
            }
            _currentSearchResultIndex.value = newIndex
            getWorkspace().setCursorPosition(currentState.searchResults[newIndex].position)
        }
    }

    fun toggleCaseSensitivity() {
        _searchCaseSensitive.value = !_searchCaseSensitive.value
        // Re-run search with new case sensitivity if there's a query
        val query = _searchQueryInput.value
        if (query.isNotEmpty()) {
            search(query, _searchCaseSensitive.value)
        }
    }

    fun closeSearch() {
        _searchQueryInput.value = ""
        _currentSearchResultIndex.value = 0
        _showSearchDialog.value = false
        // Clear search results in workspace
        launch {
            getWorkspace().search("", false)
        }
    }

    fun goToLine(lineNumber: Int) = launch {
        getWorkspace().goToLine(lineNumber)
    }

    fun showGoToLineDialog() {
        _showGoToLineDialog.value = true
    }

    fun dismissGoToLineDialog() {
        _showGoToLineDialog.value = false
    }

    fun showSearchDialog() {
        _showSearchDialog.value = true
    }

    fun dismissSearchDialog() {
        _showSearchDialog.value = false
    }

    fun undo() = launch {
        getWorkspace().undo()
    }

    fun redo() = launch {
        getWorkspace().redo()
    }

    fun clearError() = launch {
        getWorkspace().clearError()
    }

    /**
     * Executes workspace-level domain actions from action bar.
     * Routes EditorAction objects to appropriate handlers.
     */
    fun executeAction(action: EditorAction) {
        when (action) {
            EditorAction.Copy -> copyToClipboard()
            EditorAction.Cut -> cutToClipboard()
            EditorAction.Paste -> pasteFromClipboard()
            EditorAction.Delete -> deleteSelection()
            EditorAction.SelectAll -> selectAll()
            EditorAction.GoToLine -> showGoToLineDialog()
            EditorAction.Search -> showSearchDialog()
        }
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
            is EditorPageAction.Edit.DeleteAtCursor -> deleteAtCursor(action.count)
            is EditorPageAction.Edit.DeleteSelection -> deleteSelection()
            is EditorPageAction.Edit.Copy -> copyToClipboard()
            is EditorPageAction.Edit.Cut -> cutToClipboard()
            is EditorPageAction.Edit.Paste -> pasteFromClipboard()
            is EditorPageAction.Edit.SelectAll -> selectAll()
            is EditorPageAction.Edit.Undo -> undo()
            is EditorPageAction.Edit.Redo -> redo()

            // Navigation actions
            is EditorPageAction.Navigation.SetCursor -> setCursorPosition(action.position)
            is EditorPageAction.Navigation.SetSelection -> setSelection(action.start, action.end)
            is EditorPageAction.Navigation.ClearSelection -> setCursorPosition(action.cursorPosition)
            is EditorPageAction.Navigation.Search -> search(action.query, false)
            is EditorPageAction.Navigation.GoToLine -> goToLine(action.lineNumber)
            is EditorPageAction.Navigation.UpdateVisibleRange -> updateVisibleRange(action.startLine, action.endLine)

            // Error actions
            is EditorPageAction.Error.Clear -> clearError()
        }
    }

    data class State(
        val id: Workspace.Id,
        val fileInfo: FileInfo? = null,
        val title: CaString,
        val subTitle: CaString,
        val totalLines: Int = 0,
        val isModified: Boolean = false,
        val currentContent: String = "",
        val cursorPosition: TextPosition = TextPosition.ZERO,
        val selectionRange: Pair<TextPosition, TextPosition>? = null,
        val isLoading: Boolean = false,
        val error: Throwable? = null,
        val searchQuery: String = "",
        val searchResults: List<SearchResult> = emptyList(),
        val visibleRange: IntRange = 0..50,
        val showLineNumbers: Boolean = true,
        val wordWrap: Boolean = false,
        val showGoToLineDialog: Boolean = false,
        val showSearchDialog: Boolean = false,
        val searchQueryInput: String = "",
        val currentSearchResultIndex: Int = 0,
        val searchCaseSensitive: Boolean = false,
    ) {
        val hasFile: Boolean get() = fileInfo != null
        val hasSelection: Boolean get() = selectionRange != null
        val hasSearchResults: Boolean get() = searchResults.isNotEmpty()
        val isSearchActive: Boolean get() = searchQuery.isNotEmpty()
        val hasError: Boolean get() = error != null
        val isSearchBarVisible: Boolean get() = showSearchDialog

        // Info bar properties
        val fileSize: Long? get() = fileInfo?.size
        val totalCharacters: Int get() = fileSize?.toInt() ?: 0
        val fileEncoding: String get() = "UTF-8" // Default encoding for now
        val selectedCharacterCount: Int
            get() {
                if (selectionRange == null) return 0
                val (start, end) = selectionRange
                // Calculate character count from offset difference
                return (end.offset - start.offset).toInt()
            }

        val selectedLineCount: Int
            get() {
                if (selectionRange == null) return 0
                val (start, end) = selectionRange
                return (end.line - start.line) + 1
            }

        // Available actions based on current state
        val availableActions: List<EditorAction>
            get() = buildList {
                // Copy - visible when there's a selection
                if (hasSelection) {
                    add(EditorAction.Copy)
                }

                // Cut - visible when there's a selection
                if (hasSelection) {
                    add(EditorAction.Cut)
                }

                // Delete - visible when there's a selection
                if (hasSelection) {
                    add(EditorAction.Delete)
                }

                // Paste - always visible when there's a file/content
                if (hasFile || currentContent.isNotEmpty()) {
                    add(EditorAction.Paste)
                }

                // Select All - always visible when there's a file/content
                if (hasFile || currentContent.isNotEmpty()) {
                    add(EditorAction.SelectAll)
                }

                // Go to Line - always visible when there's a file
                if (hasFile) {
                    add(EditorAction.GoToLine)
                }

                // Search - visible when there's a file and search bar is not open
                if (hasFile && !isSearchBarVisible) {
                    add(EditorAction.Search)
                }
            }
    }

    @AssistedFactory
    interface Factory {
        fun create(id: Workspace.Id): EditorWorkspaceViewModel
    }
}