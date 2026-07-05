package eu.darken.butler.editor.ui.editor

import androidx.compose.ui.text.input.TextFieldValue
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
import eu.darken.butler.common.progress.Progress
import eu.darken.butler.common.ui.ViewModel4
import eu.darken.butler.editor.core.EditorWorkspace
import eu.darken.butler.editor.core.engine.ContentSource
import eu.darken.butler.editor.core.engine.SearchOptions
import eu.darken.butler.editor.core.engine.SearchResult
import eu.darken.butler.editor.core.engine.TextPosition
import eu.darken.butler.editor.ui.editor.elements.EditorActionBarItem
import eu.darken.butler.editor.ui.editor.text.CursorDirection
import eu.darken.butler.workspace.contracts.explorer.PickerConfig
import eu.darken.butler.workspace.core.Workspace
import eu.darken.butler.workspace.core.WorkspaceAction
import eu.darken.butler.workspace.core.WorkspaceEvent
import eu.darken.butler.workspace.core.WorkspaceProvider
import eu.darken.butler.workspace.core.WorkspaceRemote
import eu.darken.butler.workspace.core.clipboard.ClipboardClip
import eu.darken.butler.workspace.core.clipboard.ClipboardRepo
import eu.darken.butler.workspace.core.handleResult
import eu.darken.butler.workspace.core.launchPicker
import eu.darken.butler.workspace.ui.clipboard.ClipboardDisplayState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

@HiltViewModel(assistedFactory = EditorWorkspaceViewModel.Factory::class)
class EditorWorkspaceViewModel @AssistedInject constructor(
    @Assisted private val id: Workspace.Id,
    dispatchers: DispatcherProvider,
    private val workspaceProvider: WorkspaceProvider,
    private val workspaceRemote: WorkspaceRemote,
    private val clipboardHelper: SystemClipboardHelper,
    private val clipboardRepo: ClipboardRepo,
) : ViewModel4(dispatchers, logTag("Editor", "Workspace", id.shortTag, "Page")) {

    private val workspaceSource: Flow<EditorWorkspace?> = workspaceProvider.retrieve(id).map { it as EditorWorkspace? }
    private suspend fun getWorkspace(): EditorWorkspace = workspaceSource.filterNotNull().first()

    private val _showGoToLineDialog = MutableStateFlow(false)
    private val _showSearchDialog = MutableStateFlow(false)
    private val _showCloseConfirmDialog = MutableStateFlow(false)
    private val _searchQueryInput = MutableStateFlow(TextFieldValue(""))
    private val _currentSearchResultIndex = MutableStateFlow(0)
    private val _searchCaseSensitive = MutableStateFlow(false)
    private val _searchRegexEnabled = MutableStateFlow(false)
    private val _searchWholeWord = MutableStateFlow(false)
    private val _scrollTrigger = MutableStateFlow(0)
    private val _clipboardInfoClip = MutableStateFlow<ClipboardClip?>(null)
    private var openFileJob: Job? = null

    private data class DialogStates(
        val showGoToLineDialog: Boolean,
        val showSearchDialog: Boolean,
        val showCloseConfirmDialog: Boolean,
    )

    private val dialogStates = combine(
        _showGoToLineDialog,
        _showSearchDialog,
        _showCloseConfirmDialog,
    ) { showGoToLineDialog, showSearchDialog, showCloseConfirmDialog ->
        DialogStates(showGoToLineDialog, showSearchDialog, showCloseConfirmDialog)
    }

    private data class SearchStates(
        val queryInput: TextFieldValue,
        val currentResultIndex: Int,
        val caseSensitive: Boolean,
        val regexEnabled: Boolean,
        val wholeWord: Boolean,
        val scrollTrigger: Int,
    )

    private val searchStates = combine(
        _searchQueryInput,
        _currentSearchResultIndex,
        _searchCaseSensitive,
        _searchRegexEnabled,
        _searchWholeWord,
        _scrollTrigger,
    ) { values ->
        SearchStates(
            queryInput = values[0] as TextFieldValue,
            currentResultIndex = values[1] as Int,
            caseSensitive = values[2] as Boolean,
            regexEnabled = values[3] as Boolean,
            wholeWord = values[4] as Boolean,
            scrollTrigger = values[5] as Int,
        )
    }

    private val workspaceWithState: Flow<Pair<EditorWorkspace, EditorWorkspace.State>> = workspaceSource
        .filterNotNull()
        .flatMapLatest { ws -> ws.state.map { state -> ws to state } }

    private val _hasSystemClipboardContent = MutableStateFlow(clipboardHelper.hasClipboardContent())

    val state: Flow<State> = combine(
        workspaceWithState,
        dialogStates,
        searchStates,
        flowOf(id),
        _hasSystemClipboardContent,
    ) { (workspace, wsState), dialogs, search, workspaceId, hasClipboardContent ->
        // Only emit Ready states - Init/Error are handled globally by WorkspaceMapper
        val readyState = wsState as? EditorWorkspace.State.Ready ?: return@combine null

        val editorState = readyState.editor

        val displayPath = (editorState.contentSource as? ContentSource.File)?.path
        val title = displayPath?.userReadableName ?: editorState.contentSource.name.toCaString()
        val subTitle = displayPath?.parent?.userReadablePath ?: "In-Memory-Buffer".toCaString()

        State(
            id = workspaceId,
            contentSource = editorState.contentSource,
            title = title,
            subTitle = subTitle,
            totalLines = editorState.totalLines,
            isModified = editorState.isModified,
            currentContent = editorState.currentContent,
            cursorPosition = editorState.cursorPosition,
            selectionRange = editorState.selectionRange,
            progress = readyState.progress,
            error = editorState.error,
            searchQuery = editorState.searchQuery,
            searchResults = editorState.searchResults,
            visibleRange = editorState.visibleRange,
            showLineNumbers = editorState.showLineNumbers,
            wordWrap = editorState.wordWrap,
            showGoToLineDialog = dialogs.showGoToLineDialog,
            showSearchDialog = dialogs.showSearchDialog,
            showCloseConfirmDialog = dialogs.showCloseConfirmDialog,
            searchQueryInput = search.queryInput,
            currentSearchResultIndex = search.currentResultIndex,
            searchCaseSensitive = search.caseSensitive,
            searchRegexEnabled = search.regexEnabled,
            searchWholeWord = search.wholeWord,
            scrollTrigger = search.scrollTrigger,
            canUndo = editorState.canUndo,
            canRedo = editorState.canRedo,
            hasSystemClipboardContent = hasClipboardContent,
        )
    }.filterNotNull()

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
        val currentState = state.first()
        val currentPath = (currentState.contentSource as? ContentSource.File)?.path?.parent
        workspaceRemote.launchPicker(id, currentPath, PickerConfig.Selection.FileSingle)
    }

    fun openFile(filePath: APath<*>) {
        openFileJob?.cancel()
        openFileJob = vmScope.launch {
            try {
                getWorkspace().openFile(filePath)  // Workspace handles loading state
            } catch (e: CancellationException) {
                log(tag, INFO) { "File open cancelled: $filePath" }
                throw e
            } catch (e: Exception) {
                log(tag, ERROR) { "Failed to open file: $filePath - ${e.asLog()}" }
            } finally {
                openFileJob = null
            }
        }
    }

    fun cancelFileOpen() {
        log(tag) { "Cancelling file open" }
        // Cancel the ViewModel's waiting job
        openFileJob?.cancel()
        openFileJob = null
        // Also cancel the engine initialization directly (bypasses scope isolation)
        vmScope.launch {
            getWorkspace().cancelFileOpen()
        }
    }

    fun closeFile() {
        launch {
            val currentState = state.first()
            if (currentState.isModified) {
                // Show confirmation dialog
                _showCloseConfirmDialog.value = true
            } else {
                // Close directly if no unsaved changes
                performCloseFile()
            }
        }
    }

    private fun performCloseFile() {
        launch {
            try {
                getWorkspace().closeFile()  // Workspace handles loading state
            } catch (e: Exception) {
                log(tag, ERROR) { "Failed to close file - ${e.asLog()}" }
            }
        }
    }

    fun confirmCloseFile() {
        _showCloseConfirmDialog.value = false
        performCloseFile()
    }

    fun dismissCloseConfirmDialog() {
        _showCloseConfirmDialog.value = false
    }

    fun saveFile() {
        launch {
            getWorkspace().saveFile()  // Workspace handles loading state
        }
    }

    fun updateVisibleRange(startLine: Int, endLine: Int) = launch {
        getWorkspace().updateVisibleRange(startLine, endLine)
    }

    fun insertText(text: String) = launch {
        getWorkspace().insertText(text)
    }

    fun replaceText(start: TextPosition, end: TextPosition, text: String, caret: TextPosition) = launch {
        getWorkspace().replaceText(start, end, text, caret)
    }

    fun deleteSelection() = launch {
        getWorkspace().deleteSelection()
    }

    fun deleteAtCursor(count: Int) = launch {
        getWorkspace().deleteAtCursor(count)
    }

    fun deleteForward() = launch {
        log(tag) { "deleteForward() called" }
        getWorkspace().deleteForward()
    }

    fun moveCursor(direction: CursorDirection, extendSelection: Boolean) = launch {
        log(tag) { "moveCursor(direction=$direction, extendSelection=$extendSelection) called" }
        getWorkspace().moveCursor(direction, extendSelection)
    }

    fun copyToClipboard() = launch {
        val result = getWorkspace().copySelection()
        result.fold(
            onSuccess = { text ->
                clipboardHelper.copyToClipboard(text)
                _hasSystemClipboardContent.value = true
                log(tag) { "Copied ${text.length} characters to system clipboard" }
            },
            onFailure = { e ->
                log(tag, ERROR) { "Failed to copy selection - ${e.asLog()}" }
            }
        )
    }

    fun cutToClipboard() = launch {
        val workspace = getWorkspace()
        val copyResult = workspace.copySelection()
        copyResult.fold(
            onSuccess = { text ->
                clipboardHelper.copyToClipboard(text)
                _hasSystemClipboardContent.value = true
                workspace.deleteSelection()
                log(tag) { "Cut ${text.length} characters to system clipboard" }
            },
            onFailure = { e ->
                log(tag, ERROR) { "Failed to cut selection - ${e.asLog()}" }
            }
        )
    }

    /**
     * Copies selection to Butler clipboard only (for long-press action).
     */
    fun copyToButlerClipboard() = launch {
        val result = getWorkspace().copySelection()
        result.fold(
            onSuccess = { text ->
                addToButlerClipboard(text)
            },
            onFailure = { e ->
                log(tag, ERROR) { "Failed to copy selection - ${e.asLog()}" }
            }
        )
    }

    /**
     * Cuts selection to Butler clipboard only (for long-press action).
     */
    fun cutToButlerClipboard() = launch {
        val workspace = getWorkspace()
        val copyResult = workspace.copySelection()
        copyResult.fold(
            onSuccess = { text ->
                addToButlerClipboard(text)
                workspace.deleteSelection()
                log(tag) { "Cut ${text.length} characters to Butler clipboard" }
            },
            onFailure = { e ->
                log(tag, ERROR) { "Failed to cut selection - ${e.asLog()}" }
            }
        )
    }

    private suspend fun addToButlerClipboard(text: String) {
        // Check size limit
        if (text.toByteArray(Charsets.UTF_8).size > ClipboardClip.Text.MAX_SIZE_BYTES) {
            log(tag, WARN) { "Text too large for Butler clipboard: ${text.length} chars" }
            return
        }

        val currentFilePath = (state.first().contentSource as? ContentSource.File)?.path
        val clip = ClipboardClip.Text(
            origin = id,
            content = text,
            sourcePath = currentFilePath,
        )
        clipboardRepo.add(clip)
        log(tag, INFO) { "Added ${text.length} characters to Butler clipboard" }
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

    fun refreshClipboardState() {
        _hasSystemClipboardContent.value = clipboardHelper.hasClipboardContent()
    }

    /**
     * Clipboard entries that can be pasted into the editor (files only, not text).
     */
    val pasteableClipboard: Flow<List<ClipboardClip.Paths>> = clipboardRepo.state
        .map { state ->
            state.entries.filterIsInstance<ClipboardClip.Paths>()
                .filter { clip ->
                    clip.paths.any { path -> isLikelyTextFile(path) }
                }
        }

    private fun isLikelyTextFile(path: APath<*>): Boolean {
        val ext = path.name.substringAfterLast('.', "").lowercase()
        return ext in TEXT_EXTENSIONS
    }

    /**
     * Paste content from a file in the Butler clipboard into the editor.
     */
    fun pasteFromClipboardFile(path: APath<*>) = launch {
        log(tag) { "pasteFromClipboardFile($path)" }
        val result = getWorkspace().readFileContent(path)
        result.fold(
            onSuccess = { content ->
                getWorkspace().insertText(content)
                log(tag, INFO) { "Pasted ${content.length} characters from file: ${path.name}" }
            },
            onFailure = { e ->
                log(tag, ERROR) { "Failed to paste from file: ${e.asLog()}" }
            }
        )
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

    private fun buildSearchOptions() = SearchOptions(
        caseSensitive = _searchCaseSensitive.value,
        useRegex = _searchRegexEnabled.value,
        wholeWord = _searchWholeWord.value,
    )

    private fun search(query: String) = launch {
        val options = buildSearchOptions()
        val result = getWorkspace().search(query, options)
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

    fun updateSearchQuery(textFieldValue: TextFieldValue) {
        _searchQueryInput.value = textFieldValue
        val query = textFieldValue.text
        // Trigger search immediately (debouncing can be added later if needed)
        if (query.isNotEmpty()) {
            search(query)
        } else {
            // Clear results when query is empty
            launch {
                getWorkspace().search("")
                _currentSearchResultIndex.value = 0
            }
        }
    }

    fun nextSearchResult() = launch {
        val currentState = state.first()
        if (currentState.searchResults.isNotEmpty()) {
            val newIndex = (_currentSearchResultIndex.value + 1) % currentState.searchResults.size
            _currentSearchResultIndex.value = newIndex
            _scrollTrigger.value++
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
            _scrollTrigger.value++
            getWorkspace().setCursorPosition(currentState.searchResults[newIndex].position)
        }
    }

    fun toggleCaseSensitivity() {
        _searchCaseSensitive.value = !_searchCaseSensitive.value
        // Re-run search with new case sensitivity if there's a query
        val query = _searchQueryInput.value.text
        if (query.isNotEmpty()) {
            search(query)
        }
    }

    fun toggleRegexMode() {
        _searchRegexEnabled.value = !_searchRegexEnabled.value
        // Re-run search with new regex mode if there's a query
        val query = _searchQueryInput.value.text
        if (query.isNotEmpty()) {
            search(query)
        }
    }

    fun toggleWholeWord() {
        _searchWholeWord.value = !_searchWholeWord.value
        // Re-run search with new whole word mode if there's a query
        val query = _searchQueryInput.value.text
        if (query.isNotEmpty()) {
            search(query)
        }
    }

    fun closeSearch() {
        _searchQueryInput.value = TextFieldValue("")
        _currentSearchResultIndex.value = 0
        _showSearchDialog.value = false
        // Clear search results in workspace
        launch {
            getWorkspace().search("")
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
    fun executeAction(action: EditorActionBarItem) {
        when (action) {
            EditorActionBarItem.Copy -> copyToClipboard()
            EditorActionBarItem.Cut -> cutToClipboard()
            EditorActionBarItem.Paste -> pasteFromClipboard()
            EditorActionBarItem.Delete -> deleteSelection()
            EditorActionBarItem.SelectAll -> selectAll()
            EditorActionBarItem.GoToLine -> showGoToLineDialog()
            EditorActionBarItem.Search -> showSearchDialog()
        }
    }

    /**
     * Handles long-press on action bar buttons.
     * Copy/Cut long press copies/cuts to Butler clipboard.
     */
    fun executeActionLongClick(action: EditorActionBarItem) {
        when (action) {
            EditorActionBarItem.Copy -> copyToButlerClipboard()
            EditorActionBarItem.Cut -> cutToButlerClipboard()
            else -> { /* Other actions don't have long press behavior */
            }
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
            is EditorPageAction.File.Save -> saveFile()
            is EditorPageAction.File.Close -> closeFile()
            is EditorPageAction.File.CancelOpen -> cancelFileOpen()

            // Edit actions
            is EditorPageAction.Edit.InsertText -> insertText(action.text)
            is EditorPageAction.Edit.DeleteAtCursor -> deleteAtCursor(action.count)
            is EditorPageAction.Edit.ReplaceRange -> replaceText(action.start, action.end, action.text, action.caret)
            is EditorPageAction.Edit.ForwardDelete -> deleteForward()
            is EditorPageAction.Edit.Undo -> undo()
            is EditorPageAction.Edit.Redo -> redo()

            // Navigation actions
            is EditorPageAction.Navigation.MoveCursor -> moveCursor(action.direction, action.extendSelection)
            is EditorPageAction.Navigation.SetCursor -> setCursorPosition(action.position)
            is EditorPageAction.Navigation.SetSelection -> setSelection(action.start, action.end)
            is EditorPageAction.Navigation.ClearSelection -> setCursorPosition(action.cursorPosition)
            is EditorPageAction.Navigation.GoToLine -> goToLine(action.lineNumber)
            is EditorPageAction.Navigation.UpdateVisibleRange -> updateVisibleRange(action.startLine, action.endLine)

            // Search UI actions
            is EditorPageAction.Search.UpdateQuery -> updateSearchQuery(action.query)
            is EditorPageAction.Search.ToggleCaseSensitive -> toggleCaseSensitivity()
            is EditorPageAction.Search.ToggleRegex -> toggleRegexMode()
            is EditorPageAction.Search.ToggleWholeWord -> toggleWholeWord()
            is EditorPageAction.Search.NextResult -> nextSearchResult()
            is EditorPageAction.Search.PreviousResult -> previousSearchResult()
            is EditorPageAction.Search.Close -> closeSearch()

            // Dialog actions
            is EditorPageAction.Dialog.DismissGoToLine -> dismissGoToLineDialog()
            is EditorPageAction.Dialog.DismissSearch -> dismissSearchDialog()
            is EditorPageAction.Dialog.DismissCloseConfirm -> dismissCloseConfirmDialog()
            is EditorPageAction.Dialog.ConfirmClose -> confirmCloseFile()

            // Clipboard actions
            is EditorPageAction.Clipboard.Paste -> pasteFromClipboard(action.clip)
            is EditorPageAction.Clipboard.Remove -> removeClipboardEntry(action.clip)
            is EditorPageAction.Clipboard.ShowInfo -> showClipboardInfo(action.clip)
            is EditorPageAction.Clipboard.DismissInfo -> dismissClipboardInfo()
            is EditorPageAction.Clipboard.Clear -> clearAllClipboard()

            // Workspace actions
            is EditorPageAction.Workspace.ShareError -> { /* Handled globally by WorkspaceMapper */
            }
            is EditorPageAction.Workspace.Close -> closeWorkspace()

            // Error actions
            is EditorPageAction.Error.Clear -> clearError()
        }
    }

    fun closeWorkspace() = launch {
        workspaceRemote.execute(WorkspaceAction.Close(id))
    }

    data class State(
        val id: Workspace.Id,
        val contentSource: ContentSource = ContentSource.Memory(size = 0L),
        val title: CaString,
        val subTitle: CaString,
        val totalLines: Int = 0,
        val isModified: Boolean = false,
        val currentContent: String = "",
        val cursorPosition: TextPosition = TextPosition.ZERO,
        val selectionRange: Pair<TextPosition, TextPosition>? = null,
        val progress: Progress.Data? = null,
        val error: Throwable? = null,
        val searchQuery: String = "",
        val searchResults: List<SearchResult> = emptyList(),
        val visibleRange: IntRange = 0..50,
        val showLineNumbers: Boolean = true,
        val wordWrap: Boolean = false,
        val showGoToLineDialog: Boolean = false,
        val showSearchDialog: Boolean = false,
        val showCloseConfirmDialog: Boolean = false,
        val searchQueryInput: TextFieldValue = TextFieldValue(""),
        val currentSearchResultIndex: Int = 0,
        val searchCaseSensitive: Boolean = false,
        val searchRegexEnabled: Boolean = false,
        val searchWholeWord: Boolean = false,
        val scrollTrigger: Int = 0,
        val canUndo: Boolean = false,
        val canRedo: Boolean = false,
        val hasSystemClipboardContent: Boolean = false,
    ) {
        val isLoading: Boolean get() = progress != null
        val hasFile: Boolean get() = contentSource is ContentSource.File
        val hasContent: Boolean get() = contentSource.hasContent
        val isFileReady: Boolean get() = contentSource is ContentSource.File && progress == null
        val hasSelection: Boolean get() = selectionRange != null
        val hasSearchResults: Boolean get() = searchResults.isNotEmpty()
        val isSearchActive: Boolean get() = searchQuery.isNotEmpty()
        val hasError: Boolean get() = error != null
        val isSearchBarVisible: Boolean get() = showSearchDialog

        // Info bar properties
        val fileSize: Long get() = contentSource.size
        val totalCharacters: Int get() = fileSize.toInt()
        val fileEncoding: String
            get() = (contentSource as? ContentSource.File)?.detectedCharset?.name() ?: "UTF-8"
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
        val availableActions: List<EditorActionBarItem>
            get() = buildList {
                if (hasSelection) add(EditorActionBarItem.Copy)
                if (hasSelection) add(EditorActionBarItem.Cut)
                if (hasSelection) add(EditorActionBarItem.Delete)
                if (hasSystemClipboardContent) add(EditorActionBarItem.Paste)
                if (hasContent || currentContent.isNotEmpty()) add(EditorActionBarItem.SelectAll)
                if (hasContent) add(EditorActionBarItem.GoToLine)
                if (hasContent && !isSearchBarVisible) add(EditorActionBarItem.Search)
            }
    }

    val clipboard: Flow<ClipboardDisplayState> = clipboardRepo.state.map { state ->
        ClipboardDisplayState(entries = state.entries)
    }

    val clipboardInfoClip: Flow<ClipboardClip?> = _clipboardInfoClip

    fun showClipboardInfo(clip: ClipboardClip) {
        log(tag) { "showClipboardInfo($clip)" }
        _clipboardInfoClip.value = clip
    }

    fun dismissClipboardInfo() {
        _clipboardInfoClip.value = null
    }

    fun removeClipboardEntry(clip: ClipboardClip) = launch {
        log(tag) { "removeClipboardEntry(${clip.id})" }
        clipboardRepo.remove(clip.id)
    }

    fun clearAllClipboard() = launch {
        log(tag) { "clearAllClipboard()" }
        clipboardRepo.clear()
    }

    fun pasteFromClipboard(clip: ClipboardClip) = launch {
        log(tag) { "pasteFromClipboard($clip)" }
        when (clip) {
            is ClipboardClip.Text -> {
                getWorkspace().insertText(clip.content)
                log(tag, INFO) { "Pasted ${clip.content.length} characters from Butler clipboard" }
            }
            is ClipboardClip.Paths -> {
                // For file paths, read the first text file and paste its content
                val textFile = clip.paths.firstOrNull { isLikelyTextFile(it) }
                if (textFile != null) {
                    pasteFromClipboardFile(textFile)
                } else {
                    log(tag, WARN) { "No text files found in clipboard paths" }
                }
            }
        }
    }

    companion object {
        private val TEXT_EXTENSIONS = setOf(
            "txt", "md", "json", "xml", "html", "css", "js", "kt", "java", "py", "sh",
            "yml", "yaml", "csv", "log", "conf", "ini", "properties", "gradle", "toml",
            "c", "cpp", "h", "hpp", "rs", "go", "rb", "php", "sql", "ts", "tsx", "jsx",
        )
    }

    @AssistedFactory
    interface Factory {
        fun create(id: Workspace.Id): EditorWorkspaceViewModel
    }
}