package eu.darken.butler.editor.core

import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import eu.darken.butler.common.ca.CaString
import eu.darken.butler.common.ca.toCaString
import eu.darken.butler.common.debug.logging.Logging.Priority.*
import eu.darken.butler.common.debug.logging.asLog
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.debug.logging.logTag
import eu.darken.butler.common.files.APath
import eu.darken.butler.common.files.LocalPath
import eu.darken.butler.editor.core.engine.EditorEngine
import eu.darken.butler.editor.core.engine.FileInfo
import eu.darken.butler.editor.core.engine.SearchResult
import eu.darken.butler.editor.core.engine.TextPosition
import eu.darken.butler.workspace.core.Workspace
import eu.darken.butler.workspace.core.operations.Operation
import eu.darken.butler.workspace.core.operations.OperationsManager
import eu.darken.butler.workspace.core.operations.operationsForWorkspace
import eu.darken.butler.workspace.core.operations.withOnlyStateChanges
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.parcelize.Parcelize


class EditorWorkspace @AssistedInject constructor(
    @Assisted override val id: Workspace.Id,
    @Assisted private val arguments: Arguments?,
    private val editorEngineFactory: EditorEngine.Factory,
    private val editorSettings: EditorSettings,
    private val operationsManager: OperationsManager,
) : Workspace {

    private val tag = logTag("Editor", "Workspace", id.shortTag)

    private val workspaceScope = CoroutineScope(
        SupervisorJob() +
            CoroutineExceptionHandler { _, throwable ->
                log(tag, ERROR) { "Uncaught exception in workspace scope: ${throwable.asLog()}" }
                // Error handled by editorEngine.error StateFlow
            }
    )

    override val type: Workspace.Type = Workspace.Type.EDITOR

    private val _info = MutableStateFlow(
        Workspace.Info(
            id = id,
            type = type,
            title = generateTitle(),
        )
    )
    override val info: MutableStateFlow<Workspace.Info> = _info

    val filePath: APath<*>? get() = arguments?.filePath
    val isReadOnly: Boolean get() = arguments?.isReadOnly ?: false

    private val editorEngine = editorEngineFactory.create(id)

    // Expose editor state flows
    val currentContent: StateFlow<String> = editorEngine.currentContent
    val cursorPosition: StateFlow<TextPosition> = editorEngine.cursorPosition
    val selectionRange: StateFlow<Pair<TextPosition, TextPosition>?> = editorEngine.selectionRange
    val searchQuery: StateFlow<String> = editorEngine.searchQuery
    val searchResults: StateFlow<List<SearchResult>> = editorEngine.searchResults
    val visibleRange: StateFlow<IntRange> = editorEngine.visibleRange
    val totalLines: StateFlow<Int> = editorEngine.totalLines
    val error: StateFlow<Throwable?> = editorEngine.error
    val fileInfo: Flow<FileInfo?> = editorEngine.fileInfo
    val isModified: Flow<Boolean> = editorEngine.isModified

    // Combined editor state for UI
    val editorState: Flow<EditorState> = combine(
        fileInfo,
        totalLines,
        isModified,
        currentContent,
        cursorPosition,
        selectionRange,
        searchQuery,
        searchResults,
        visibleRange,
        error,
        editorSettings.showLineNumbers.flow,
        editorSettings.wordWrap.flow
    ) { values ->
        @Suppress("UNCHECKED_CAST")
        EditorState(
            fileInfo = values[0] as FileInfo?,
            totalLines = values[1] as Int,
            isModified = values[2] as Boolean,
            currentContent = values[3] as String,
            cursorPosition = values[4] as TextPosition,
            selectionRange = values[5] as Pair<TextPosition, TextPosition>?,
            searchQuery = values[6] as String,
            searchResults = values[7] as List<SearchResult>,
            visibleRange = values[8] as IntRange,
            error = values[9] as Throwable?,
            showLineNumbers = values[10] as Boolean,
            wordWrap = values[11] as Boolean
        )
    }

    init {
        log(tag, INFO) { "Initialized with file: ${filePath?.name ?: "No file"}" }

        // Track operation counts for this workspace
        operationsManager.operationsForWorkspace(id).withOnlyStateChanges()
            .onEach { operations ->
                var operationCount = 0
                var attentionCount = 0

                operations.forEach { operation ->
                    when (val state = operation.state.value) {
                        is Operation.State.Queued -> operationCount++
                        is Operation.State.Active -> operationCount++
                        is Operation.State.Waiting -> {
                            operationCount++
                            attentionCount++
                        }
                        is Operation.State.Completed -> {
                            if (state.error != null && state.error !is CancellationException) {
                                attentionCount++
                            }
                        }
                    }
                }

                _info.value = _info.value.copy(
                    operationCount = operationCount,
                    attentionCount = attentionCount
                )
                log(tag, VERBOSE) { "Updated operation counts: active=$operationCount, attention=$attentionCount" }
            }
            .launchIn(workspaceScope)

        // Initialize editor engine
        workspaceScope.launch {
            // FIXME for testing
            editorEngine.initialize(LocalPath.build("/sdcard/core.log"), isReadOnly)
        }

        // Update title based on file info
        workspaceScope.launch {
            fileInfo.collect { info ->
                updateFileInfo(info)
            }
        }
    }

    fun updateTitle(fileName: String? = null) {
        val newTitle = when {
            fileName != null -> fileName
            filePath != null -> filePath!!.name
            else -> "Editor ${id.shortTag}"
        }

        _info.value = _info.value.copy(title = newTitle.toCaString())
        log(tag, DEBUG) { "Updated title to: $newTitle" }
    }

    fun updateFileInfo(fileInfo: FileInfo?) {
        fileInfo?.let { info ->
            updateTitle(info.path.name)
        }
    }

    private fun generateTitle(): CaString {
        return when {
            arguments?.filePath != null -> arguments.filePath.name.toCaString()
            else -> "Editor ${id.shortTag}".toCaString()
        }
    }

    // Editor operations
    suspend fun openFile(filePath: APath<*>) = editorEngine.openFile(filePath)
    suspend fun closeFile() = editorEngine.closeFile()
    suspend fun saveFile() = editorEngine.saveFile()
    suspend fun search(query: String) = editorEngine.search(query)
    suspend fun goToLine(lineNumber: Int) = editorEngine.goToLine(lineNumber)
    suspend fun undo() = editorEngine.undo()
    suspend fun redo() = editorEngine.redo()
    suspend fun deleteSelection() = editorEngine.deleteSelection()

    fun insertText(text: String) = editorEngine.insertText(text)
    fun setCursorPosition(position: TextPosition) = editorEngine.setCursorPosition(position)
    fun setSelection(start: TextPosition, end: TextPosition) = editorEngine.setSelection(start, end)
    suspend fun updateVisibleRange(startLine: Int, endLine: Int) = editorEngine.updateVisibleRange(startLine, endLine)
    fun clearError() = editorEngine.clearError()
    fun canUndo() = editorEngine.canUndo()
    fun canRedo() = editorEngine.canRedo()

    // Cleanup when workspace is destroyed
    fun cleanup() {
        workspaceScope.launch {
            editorEngine.cleanup()
        }
        workspaceScope.cancel()
    }

    @Parcelize
    data class Arguments(
        val filePath: APath<*>? = null,
        val isReadOnly: Boolean = false,
        val goToLine: Int? = null,
        val searchQuery: String? = null
    ) : Workspace.Arguments {
        override val type: Workspace.Type
            get() = Workspace.Type.EDITOR
    }

    @AssistedFactory
    interface Factory {
        fun create(id: Workspace.Id, arguments: Arguments?): EditorWorkspace
    }

    data class EditorState(
        val fileInfo: FileInfo? = null,
        val totalLines: Int = 0,
        val isModified: Boolean = false,
        val currentContent: String = "",
        val cursorPosition: TextPosition = TextPosition.ZERO,
        val selectionRange: Pair<TextPosition, TextPosition>? = null,
        val searchQuery: String = "",
        val searchResults: List<SearchResult> = emptyList(),
        val visibleRange: IntRange = 0..50,
        val error: Throwable? = null,
        val showLineNumbers: Boolean = true,
        val wordWrap: Boolean = false
    ) {
        val hasFile: Boolean get() = fileInfo != null
        val fileName: String get() = fileInfo?.path?.name ?: "Untitled"
        val hasSelection: Boolean get() = selectionRange != null
        val hasSearchResults: Boolean get() = searchResults.isNotEmpty()
        val isSearchActive: Boolean get() = searchQuery.isNotEmpty()
        val hasError: Boolean get() = error != null
    }
}