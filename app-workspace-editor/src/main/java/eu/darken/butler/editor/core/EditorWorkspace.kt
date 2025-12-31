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
import eu.darken.butler.common.files.GatewaySwitch
import eu.darken.butler.common.flow.DynamicStateFlow
import eu.darken.butler.common.flow.combine
import eu.darken.butler.editor.R
import eu.darken.butler.editor.core.arguments.EditorArguments
import eu.darken.butler.editor.core.engine.ContentSource
import eu.darken.butler.editor.core.engine.EditorEngine
import eu.darken.butler.editor.core.engine.SearchOptions
import eu.darken.butler.editor.core.engine.SearchResult
import eu.darken.butler.editor.core.engine.TextPosition
import eu.darken.butler.editor.ui.editor.text.CursorDirection
import eu.darken.butler.workspace.core.Workspace
import eu.darken.butler.workspace.core.WorkspaceFactory
import eu.darken.butler.workspace.core.operations.Operation
import eu.darken.butler.workspace.core.operations.OperationsManager
import eu.darken.butler.workspace.core.operations.operationsForWorkspace
import eu.darken.butler.workspace.core.operations.withOnlyStateChanges
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import okio.buffer
import okio.use


class EditorWorkspace @AssistedInject constructor(
    @Assisted override val id: Workspace.Id,
    @Assisted private val creationArguments: EditorArguments,
    private val gatewaySwitch: GatewaySwitch,
    private val editorEngineFactory: EditorEngine.Factory,
    private val editorSettings: EditorSettings,
    private val operationsManager: OperationsManager,
) : Workspace<EditorArguments> {

    private val tag = logTag("Editor", "Workspace", id.shortTag)

    override suspend fun createArguments(): EditorArguments {
        val currentState = (_state.value as? State.Ready)?.editor ?: return EditorArguments.Default()
        val currentFilePath = (currentState.contentSource as? ContentSource.File)?.path
        return EditorArguments.Default(
            filePath = currentFilePath,
            cursorLine = currentState.cursorPosition.line,
            cursorColumn = currentState.cursorPosition.column,
            scrollToLine = currentState.visibleRange.first,
        )
    }

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

    val filePath: APath<*>? get() = (creationArguments as? EditorArguments.Default)?.filePath

    // Track engine being initialized to allow cancellation
    @Volatile
    private var pendingEngine: EditorEngine? = null

    private val engineHolder = DynamicStateFlow<EditorEngine>(
        loggingTag = tag,
        parentScope = workspaceScope,
        startValueProvider = {
            val args = creationArguments as? EditorArguments.Default
            val initialPath = args?.filePath
            val initialContent = args?.initialContent
            log(tag, INFO) { "Creating initial engine with: ${initialPath?.name ?: "scratch buffer"}" }
            editorEngineFactory.create(id, initialPath, initialContent).apply {
                initialize().getOrThrow()

                // Restore cursor and scroll position from saved arguments
                // Only if file was loaded AND positions are within bounds
                if (initialPath != null) {
                    val lines = totalLines.value
                    val cursorLine = args?.cursorLine
                    val cursorColumn = args?.cursorColumn
                    if (cursorLine != null && cursorColumn != null && cursorLine < lines) {
                        log(tag, INFO) { "Restoring cursor position: line=$cursorLine, column=$cursorColumn" }
                        setCursorPosition(TextPosition(offset = 0, line = cursorLine, column = cursorColumn))
                    }
                    val scrollLine = args?.scrollToLine
                    if (scrollLine != null && scrollLine < lines) {
                        val windowSize = 50
                        log(tag, INFO) { "Restoring scroll position: line=$scrollLine" }
                        updateVisibleRange(scrollLine, scrollLine + windowSize)
                    }
                }
            }
        },
        onRelease = { engine ->
            launch {
                try {
                    log(tag, VERBOSE) { "DynamicStateFlow releasing engine" }
                    engine.release()
                } catch (e: Exception) {
                    log(tag, ERROR) { "Failed to release engine in onRelease: ${e.asLog()}" }
                }
            }
        }
    )

    // Unified workspace state - emits Initializing immediately
    private val _state = MutableStateFlow<State>(State.Initializing)
    val state: StateFlow<State> = _state.asStateFlow()

    // Track loading for operations (openFile, saveFile, etc.)
    private val _isLoading = MutableStateFlow(false)

    // Combined editor state for internal use
    private val editorStateInternal: Flow<EditorState> = engineHolder.flow.flatMapLatest { engine ->
        combine(
            engine.contentSource,
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
            editorSettings.wordWrap.flow,
        ) { contentSource, totalLines, isModified, currentContent, cursorPosition,
            selectionRange, searchQuery, searchResults, visibleRange, error,
            showLineNumbers, wordWrap ->
            EditorState(
                contentSource = contentSource,
                totalLines = totalLines,
                isModified = isModified,
                currentContent = currentContent,
                cursorPosition = cursorPosition,
                selectionRange = selectionRange,
                searchQuery = searchQuery,
                searchResults = searchResults,
                visibleRange = visibleRange,
                error = error,
                showLineNumbers = showLineNumbers,
                wordWrap = wordWrap
            )
        }
    }

    init {
        log(tag, INFO) { "Initialized with file: ${filePath?.name ?: "No file"}" }

        // Collect editorState and emit as State.Ready
        workspaceScope.launch {
            try {
                editorStateInternal.collect { editorState ->
                    _state.value = State.Ready(editorState, isLoading = _isLoading.value)
                }
            } catch (e: Exception) {
                if (e !is CancellationException) {
                    _state.value = State.Error(e)
                    log(tag, ERROR) { "Workspace error: ${e.asLog()}" }
                }
            }
        }

        // Update state when isLoading changes
        workspaceScope.launch {
            _isLoading.collect { loading ->
                val current = _state.value
                if (current is State.Ready) {
                    _state.value = current.copy(isLoading = loading)
                }
            }
        }

        // Track operation counts for this workspace
        operationsManager.operationsForWorkspace(id).withOnlyStateChanges()
            .onEach { operations ->
                var operationCount = 0
                var attentionCount = 0

                operations.forEach { operation ->
                    when (val opState = operation.state.value) {
                        is Operation.State.Queued -> operationCount++
                        is Operation.State.Active -> operationCount++
                        is Operation.State.Waiting -> {
                            operationCount++
                            attentionCount++
                        }
                        is Operation.State.Completed -> {
                            if (opState.error != null && opState.error !is CancellationException) {
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

        // Update title based on content source
        workspaceScope.launch {
            engineHolder.flow.flatMapLatest { engine ->
                engine.contentSource
            }.collect { source ->
                updateContentSource(source)
            }
        }

        // Auto-save logic: debounce after changes
        combine(
            editorStateInternal.map { it.isModified }.distinctUntilChanged(),
            editorSettings.autoSaveEnabled.flow,
            editorSettings.autoSaveInterval.flow,
        ) { isModified, enabled, interval ->
            Triple(isModified, enabled, interval)
        }
            .flatMapLatest { (isModified, enabled, interval) ->
                if (isModified && enabled) {
                    // Debounce: wait for interval after last modification
                    flow {
                        delay(interval)
                        emit(Unit)
                    }
                } else {
                    emptyFlow()
                }
            }
            .onEach {
                log(tag, INFO) { "Auto-save triggered" }
                try {
                    saveFile()
                } catch (e: Exception) {
                    log(tag, WARN) { "Auto-save failed: ${e.asLog()}" }
                }
            }
            .launchIn(workspaceScope)
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

    fun updateContentSource(contentSource: ContentSource) {
        when (contentSource) {
            is ContentSource.File -> updateTitle(contentSource.path.name)
            is ContentSource.Memory -> updateTitle(contentSource.name)
        }
    }

    private fun generateTitle(): CaString {
        val args = creationArguments as? EditorArguments.Default
        val filePath = args?.filePath
        val suggestedTitle = args?.suggestedTitle
        return when {
            filePath != null -> filePath.name.toCaString()
            suggestedTitle != null -> suggestedTitle.toCaString()
            else -> R.string.editor_file_untitled.toCaString()
        }
    }

    private suspend fun switchEngine(newFilePath: APath<*>?) {
        log(tag, INFO) { "Switching engine to: ${newFilePath?.name ?: "scratch buffer"}" }

        // Create new engine outside updateBlocking so we can track it for cancellation
        val newEngine = editorEngineFactory.create(id, newFilePath)
        pendingEngine = newEngine

        try {
            engineHolder.updateBlocking {
                // 'this' is the old engine (receiver of extension function)
                val initResult = newEngine.initialize()

                if (initResult.isFailure) {
                    newEngine.release()
                    val error = initResult.exceptionOrNull() ?: Exception("Failed to initialize engine")
                    log(tag, ERROR) { "Failed to switch engine: ${error.asLog()}" }
                    throw error
                }

                log(tag, DEBUG) { "Engine switched successfully" }
                // Old engine (this) cleanup happens automatically via onRelease callback
                newEngine
            }
        } finally {
            pendingEngine = null
        }
    }

    // Editor operations
    suspend fun openFile(filePath: APath<*>) {
        _isLoading.value = true
        try {
            switchEngine(filePath)
        } finally {
            _isLoading.value = false
        }
    }

    suspend fun closeFile() = switchEngine(null)

    /**
     * Cancels an in-progress file open operation.
     * Safe to call even if no operation is running.
     */
    fun cancelFileOpen() {
        pendingEngine?.let { engine ->
            log(tag, INFO) { "Cancelling file open for pending engine" }
            engine.cancelInitialization()
        }
    }

    suspend fun saveFile() {
        _isLoading.value = true
        try {
            engineHolder.value().saveFile()
        } finally {
            _isLoading.value = false
        }
    }
    suspend fun saveFileAs(newFilePath: APath<*>): Result<Unit> {
        val engine = engineHolder.value()

        return try {
            log(tag) { "Saving as: ${newFilePath.name}" }

            // Get content stream from engine (Engine manages content)
            val source = engine.getContentStream()

            // Workspace handles file I/O operations
            source.use {
                gatewaySwitch.file(newFilePath, readWrite = true).use { handle ->
                    handle.sink().buffer().use { sink ->
                        sink.writeAll(source)
                    }
                }
            }

            log(tag) { "Content written to: ${newFilePath.name}" }

            // Switch to new engine with the new file path
            switchEngine(newFilePath)

            Result.success(Unit)
        } catch (e: Exception) {
            log(tag, ERROR) { "Failed to save as: ${newFilePath.name} - ${e.asLog()}" }
            Result.failure(e)
        }
    }

    suspend fun search(query: String, options: SearchOptions = SearchOptions()) =
        engineHolder.value().search(query, options)

    suspend fun goToLine(lineNumber: Int) = engineHolder.value().goToLine(lineNumber)
    suspend fun undo() = engineHolder.value().undo()
    suspend fun redo() = engineHolder.value().redo()
    suspend fun deleteSelection() = engineHolder.value().deleteSelection()
    suspend fun deleteAtCursor(count: Int) = engineHolder.value().deleteAtCursor(count)
    suspend fun copySelection() = engineHolder.value().copySelection()
    suspend fun selectAll() = engineHolder.value().selectAll()

    suspend fun insertText(text: String) = engineHolder.value().insertText(text)
    suspend fun setCursorPosition(position: TextPosition) = engineHolder.value().setCursorPosition(position)
    suspend fun setSelection(start: TextPosition, end: TextPosition) = engineHolder.value().setSelection(start, end)
    suspend fun updateVisibleRange(startLine: Int, endLine: Int) =
        engineHolder.value().updateVisibleRange(startLine, endLine)

    suspend fun moveCursor(direction: CursorDirection, extendSelection: Boolean) {
        log(tag) { "moveCursor(direction=$direction, extendSelection=$extendSelection)" }
        engineHolder.value().moveCursor(direction, extendSelection)
    }

    suspend fun deleteForward() {
        log(tag) { "deleteForward()" }
        engineHolder.value().deleteForward()
    }

    fun clearError() = runBlocking { engineHolder.value().clearError() }
    fun canUndo() = runBlocking { engineHolder.value().canUndo() }
    fun canRedo() = runBlocking { engineHolder.value().canRedo() }

    /**
     * Reads file content for pasting from clipboard.
     * @param path The file path to read
     * @return Result containing the file content as String, or an error
     */
    suspend fun readFileContent(path: APath<*>): Result<String> = try {
        gatewaySwitch.useRes {
            val lookup = gatewaySwitch.lookup(path, eu.darken.butler.common.files.LookupOptions())
            val size = lookup.size ?: 0L

            // Size limit: 1 MB
            if (size > MAX_PASTE_FILE_SIZE) {
                return@useRes Result.failure(
                    IllegalArgumentException("File too large to paste (max ${MAX_PASTE_FILE_SIZE / 1024 / 1024} MB)")
                )
            }

            val bytes = gatewaySwitch.openInputStream(path).use { inputStream ->
                inputStream.readBytes()
            }

            // Binary detection: check for null bytes
            if (bytes.any { it == 0.toByte() }) {
                return@useRes Result.failure(
                    IllegalArgumentException("Cannot paste binary file content")
                )
            }

            // Try UTF-8 first, fallback to ISO-8859-1
            val content = try {
                String(bytes, Charsets.UTF_8)
            } catch (e: Exception) {
                log(tag, WARN) { "UTF-8 decode failed, falling back to ISO-8859-1" }
                String(bytes, Charsets.ISO_8859_1)
            }

            Result.success(content)
        }
    } catch (e: Exception) {
        log(tag, ERROR) { "Failed to read file content: ${e.asLog()}" }
        Result.failure(e)
    }

    override suspend fun release() {
        log(tag, INFO) { "release()" }
        workspaceScope.cancel()
        // DynamicStateFlow's onRelease callback handles engine cleanup automatically
    }

    data class EditorState(
        val contentSource: ContentSource = ContentSource.Memory(size = 0L),
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
        val wordWrap: Boolean = false,
    )

    sealed interface State {
        data object Initializing : State
        data class Ready(val editor: EditorState, val isLoading: Boolean = false) : State
        data class Error(val error: Throwable) : State
    }

    companion object {
        const val MAX_PASTE_FILE_SIZE = 1024 * 1024L // 1 MB
    }

    @AssistedFactory
    interface Factory : WorkspaceFactory<EditorArguments> {

        override fun create(id: Workspace.Id, arguments: EditorArguments): EditorWorkspace

        override fun serialize(json: Json, arguments: EditorArguments): JsonElement {
            return json.encodeToJsonElement<EditorArguments>(arguments)
        }

        override fun deserialize(json: Json, element: JsonElement): EditorArguments {
            return json.decodeFromJsonElement<EditorArguments>(element)
        }
    }

}