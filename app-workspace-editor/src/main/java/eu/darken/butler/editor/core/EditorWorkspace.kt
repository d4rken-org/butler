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
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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

    private val engineMutex = Mutex()
    private val _engineFlow = MutableStateFlow<EditorEngine?>(null)
    private val engineFlow: StateFlow<EditorEngine?> = _engineFlow.asStateFlow()

    // Combined editor state for UI
    val editorState: Flow<EditorState> = engineFlow
        .filterNotNull()
        .flatMapLatest { engine ->
            combine(
                engine.fileInfo,
                engine.totalLines,
                engine.isModified,
                engine.currentContent,
                engine.cursorPosition,
                engine.selectionRange,
                engine.searchQuery,
                engine.searchResults,
                engine.visibleRange,
                engine.error,
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

        // Initialize editor engine with file from arguments or scratch buffer
        workspaceScope.launch {
            // FIXME just while developing
            val filePathToOpen = arguments?.filePath ?: LocalPath.build("/sdcard/core.log")
            switchEngine(filePathToOpen)
        }

        // Update title based on file info
        workspaceScope.launch {
            engineFlow.filterNotNull().flatMapLatest { engine ->
                engine.fileInfo
            }.collect { info ->
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

    private suspend fun switchEngine(newFilePath: APath<*>?) = engineMutex.withLock {
        val oldEngine = _engineFlow.value

        log(tag, INFO) { "Switching engine to: ${newFilePath?.name ?: "scratch buffer"}" }

        // Create and initialize new engine
        val newEngine = editorEngineFactory.create(id, newFilePath)
        val initResult = newEngine.initialize()

        if (initResult.isFailure) {
            newEngine.release()
            val error = initResult.exceptionOrNull() ?: Exception("Failed to initialize engine")
            log(tag, ERROR) { "Failed to switch engine: ${error.asLog()}" }
            throw error
        }

        // Atomic switch
        _engineFlow.value = newEngine
        log(tag, DEBUG) { "Engine switched successfully" }

        // Cleanup old engine
        if (oldEngine != null) {
            try {
                oldEngine.release()
                log(tag, DEBUG) { "Old engine released" }
            } catch (e: Exception) {
                log(tag, ERROR) { "Failed to release old engine: ${e.asLog()}" }
            }
        }
    }

    // Editor operations
    suspend fun openFile(filePath: APath<*>) = switchEngine(filePath)
    suspend fun closeFile() = switchEngine(null)
    suspend fun saveFile() = _engineFlow.value?.saveFile() ?: Result.failure(IllegalStateException("No engine available"))
    suspend fun saveFileAs(newFilePath: APath<*>): Result<Unit> {
        val engine = _engineFlow.value ?: return Result.failure(IllegalStateException("No engine available"))

        // Save content to new file
        val saveResult = engine.saveFileAs(newFilePath)
        if (saveResult.isFailure) {
            return saveResult
        }

        // Switch to new engine with the new file path
        return try {
            switchEngine(newFilePath)
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
    suspend fun search(query: String) = _engineFlow.value?.search(query) ?: Result.failure(IllegalStateException("No engine available"))
    suspend fun goToLine(lineNumber: Int) = _engineFlow.value?.goToLine(lineNumber) ?: Result.failure(IllegalStateException("No engine available"))
    suspend fun undo() = _engineFlow.value?.undo() ?: Result.failure(IllegalStateException("No engine available"))
    suspend fun redo() = _engineFlow.value?.redo() ?: Result.failure(IllegalStateException("No engine available"))
    suspend fun deleteSelection() = _engineFlow.value?.deleteSelection() ?: Result.failure(IllegalStateException("No engine available"))

    fun insertText(text: String) = _engineFlow.value?.insertText(text) ?: Unit
    fun setCursorPosition(position: TextPosition) = _engineFlow.value?.setCursorPosition(position) ?: Unit
    fun setSelection(start: TextPosition, end: TextPosition) = _engineFlow.value?.setSelection(start, end) ?: Unit
    suspend fun updateVisibleRange(startLine: Int, endLine: Int) = _engineFlow.value?.updateVisibleRange(startLine, endLine) ?: Unit
    fun clearError() = _engineFlow.value?.clearError() ?: Unit
    fun canUndo() = _engineFlow.value?.canUndo() ?: false
    fun canRedo() = _engineFlow.value?.canRedo() ?: false

    override suspend fun release() = engineMutex.withLock {
        log(tag, INFO) { "release()" }

        // Release current engine
        try {
            _engineFlow.value?.release()
            _engineFlow.value = null
        } catch (e: Exception) {
            log(tag, ERROR) { "Failed to release engine: ${e.asLog()}" }
        }

        workspaceScope.cancel()
    }

    @Parcelize
    data class Arguments(
        val filePath: APath<*>? = null,
        val goToLine: Int? = null,
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