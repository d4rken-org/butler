package eu.darken.butler.editor.core

import dagger.Module
import dagger.Provides
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoMap
import eu.darken.butler.common.ca.CaString
import eu.darken.butler.common.ca.toCaString
import eu.darken.butler.common.debug.logging.Logging.Priority.*
import eu.darken.butler.common.debug.logging.asLog
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.debug.logging.logTag
import eu.darken.butler.common.files.APath
import eu.darken.butler.common.files.GatewaySwitch
import eu.darken.butler.common.flow.combine
import eu.darken.butler.common.progress.Progress
import eu.darken.butler.editor.R
import eu.darken.butler.editor.core.engine.ContentSource
import eu.darken.butler.editor.core.engine.EditorEngine
import eu.darken.butler.editor.core.engine.SearchOptions
import eu.darken.butler.editor.core.engine.SearchResult
import eu.darken.butler.editor.core.engine.TextPosition
import eu.darken.butler.editor.ui.editor.text.CursorDirection
import eu.darken.butler.workspace.contracts.editor.EditorArguments
import eu.darken.butler.workspace.core.Workspace
import eu.darken.butler.workspace.core.WorkspaceFactory
import eu.darken.butler.workspace.core.WorkspaceTypeKey
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
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.KSerializer
import kotlinx.serialization.serializer
import okio.buffer
import okio.use
import java.nio.charset.Charset


class EditorWorkspace @AssistedInject constructor(
    @Assisted override val id: Workspace.Id,
    @Assisted private val creationArguments: EditorArguments,
    private val gatewaySwitch: GatewaySwitch,
    private val editorEngineFactory: EditorEngine.Factory,
    private val editorSettings: EditorSettings,
    private val operationsManager: OperationsManager,
    private val pasteFileReader: PasteFileReader,
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
            charsetOverride = charsetOverride?.name(),
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

    // Active charset override; restored from session arguments (validated against the allowlist
    // so an unknown persisted name degrades to auto-detection instead of breaking restore)
    private var charsetOverride: Charset? =
        (creationArguments as? EditorArguments.Default)?.charsetOverride?.let { name ->
            EditorCharsets.resolve(name).also {
                if (it == null) log(tag, WARN) { "Ignoring unknown persisted charset override: $name" }
            }
        }

    // Track engine being initialized to allow cancellation
    @Volatile
    private var pendingEngine: EditorEngine? = null

    private val engineMutex = Mutex()
    private val _engine = MutableStateFlow<EditorEngine?>(null)

    // Unified workspace state - emits Initializing immediately
    private val _state = MutableStateFlow<State>(State.Initializing)
    val state: StateFlow<State> = _state.asStateFlow()

    // Combined editor state for internal use
    private val editorStateInternal: Flow<EditorState> = _engine.flatMapLatest { engine ->
        if (engine == null) return@flatMapLatest emptyFlow()
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
            engine.progress,
            engine.canUndo,
            engine.canRedo,
            editorSettings.showLineNumbers.flow,
            editorSettings.wordWrap.flow,
        ) { contentSource, totalLines, isModified, currentContent, cursorPosition,
            selectionRange, searchQuery, searchResults, visibleRange, error,
            progress, canUndo, canRedo, showLineNumbers, wordWrap ->
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
                wordWrap = wordWrap,
                progress = progress,
                canUndo = canUndo,
                canRedo = canRedo,
            )
        }
    }

    init {
        log(tag, INFO) { "Initialized with file: ${filePath?.name ?: "No file"}" }

        // Collect editorState and emit as State.Ready
        workspaceScope.launch {
            try {
                editorStateInternal.collect { editorState ->
                    _state.value = State.Ready(editorState)
                }
            } catch (e: Exception) {
                if (e !is CancellationException) {
                    _state.value = State.Error(e)
                    log(tag, ERROR) { "Workspace error: ${e.asLog()}" }
                }
            }
        }

        // Update info.lifecycleState when state changes
        workspaceScope.launch {
            _state.collect { state ->
                val lifecycle = when (state) {
                    is State.Initializing -> Workspace.LifecycleState.Initializing
                    is State.Error -> Workspace.LifecycleState.Error(state.error)
                    is State.Ready -> Workspace.LifecycleState.Ready
                }
                val hasUnsavedChanges = (state as? State.Ready)?.editor?.isModified == true
                _info.update { it.copy(lifecycleState = lifecycle, hasUnsavedChanges = hasUnsavedChanges) }
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

                _info.update {
                    it.copy(
                        operationCount = operationCount,
                        attentionCount = attentionCount,
                    )
                }
                log(tag, VERBOSE) { "Updated operation counts: active=$operationCount, attention=$attentionCount" }
            }
            .launchIn(workspaceScope)

        // Update title based on content source
        workspaceScope.launch {
            _engine.flatMapLatest { engine ->
                engine?.contentSource ?: emptyFlow()
            }.collect { source ->
                updateContentSource(source)
            }
        }

        // Auto-save logic: debounce after changes; read-only files are skipped so an edited
        // read-only document doesn't produce a failing save on every interval
        combine(
            editorStateInternal.map { state ->
                state.isModified && (state.contentSource as? ContentSource.File)?.canWrite != false
            }.distinctUntilChanged(),
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

        // Initialize engine asynchronously - allows workspace to reach Ready state immediately
        // showing loading progress during file load instead of "Initializing tab"
        workspaceScope.launch {
            val args = creationArguments as? EditorArguments.Default
            val initialPath = args?.filePath
            val initialContent = args?.initialContent
            log(tag, INFO) { "Creating initial engine with: ${initialPath?.name ?: "scratch buffer"}" }

            val engine = editorEngineFactory.create(id, initialPath, initialContent, charsetOverride)
            _engine.value = engine
            pendingEngine = engine

            try {
                val result = engine.initialize()

                if (result.isFailure) {
                    val error = result.exceptionOrNull() ?: Exception("Engine initialization failed")
                    if (error is CancellationException) return@launch
                    log(tag, ERROR) { "Engine initialization failed: ${error.asLog()}" }
                    _state.value = State.Error(error)
                    return@launch
                }
            } finally {
                pendingEngine = null
            }

            // Restore cursor and scroll position from saved arguments
            // Only if file was loaded AND positions are within bounds
            if (initialPath != null) {
                val lines = engine.totalLines.value
                val cursorLine = args.cursorLine
                val cursorColumn = args.cursorColumn
                if (cursorLine != null && cursorColumn != null && cursorLine < lines) {
                    log(tag, INFO) { "Restoring cursor position: line=$cursorLine, column=$cursorColumn" }
                    engine.setCursorPosition(TextPosition(offset = 0, line = cursorLine, column = cursorColumn))
                }
                val scrollLine = args.scrollToLine
                if (scrollLine != null && scrollLine < lines) {
                    val windowSize = 50
                    log(tag, INFO) { "Restoring scroll position: line=$scrollLine" }
                    engine.updateVisibleRange(scrollLine, scrollLine + windowSize)
                }
            }
        }
    }

    fun updateTitle(fileName: String? = null) {
        val newTitle = when {
            fileName != null -> fileName
            filePath != null -> filePath!!.name
            else -> "Editor ${id.shortTag}"
        }

        _info.update { it.copy(title = newTitle.toCaString()) }
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

    private suspend fun switchEngine(newFilePath: APath<*>?, charset: Charset? = null) {
        log(tag, INFO) { "Switching engine to: ${newFilePath?.name ?: "scratch buffer"} (charset=$charset)" }

        val newEngine = editorEngineFactory.create(id, newFilePath, charsetOverride = charset)
        pendingEngine = newEngine

        // Swap engine before initialize so progress flows through editorStateInternal
        val oldEngine = engineMutex.withLock {
            val old = _engine.value
            _engine.value = newEngine
            old
        }

        try {
            val initResult = newEngine.initialize()

            if (initResult.isFailure) {
                val error = initResult.exceptionOrNull() ?: Exception("Failed to initialize engine")
                if (error is CancellationException) throw error
                log(tag, ERROR) { "Failed to switch engine: ${error.asLog()}" }
                throw error
            }

            // Release old engine after successful init
            oldEngine?.let { old ->
                try {
                    old.release()
                } catch (e: Exception) {
                    log(tag, ERROR) { "Failed to release old engine: ${e.asLog()}" }
                }
            }

            charsetOverride = charset
            log(tag, DEBUG) { "Engine switched successfully" }
        } catch (e: CancellationException) {
            log(tag, INFO) { "Engine switch cancelled" }
            // Restore old engine on cancellation
            engineMutex.withLock { _engine.value = oldEngine }
            try {
                newEngine.release()
            } catch (releaseError: Exception) {
                log(tag, ERROR) { "Failed to release cancelled engine: ${releaseError.asLog()}" }
            }
            throw e
        } catch (e: Exception) {
            // Restore old engine on failure
            engineMutex.withLock { _engine.value = oldEngine }
            try {
                newEngine.release()
            } catch (releaseError: Exception) {
                log(tag, ERROR) { "Failed to release failed engine: ${releaseError.asLog()}" }
            }
            throw e
        } finally {
            pendingEngine = null
        }
    }

    // Editor operations
    suspend fun openFile(filePath: APath<*>) {
        // Progress is emitted by EditorEngine during initialization
        switchEngine(filePath)
    }

    suspend fun closeFile() = switchEngine(null)

    /** Reopens the current file decoding it with [charsetName]; unsaved changes are discarded. */
    suspend fun reopenWithCharset(charsetName: String) {
        val charset = EditorCharsets.resolve(charsetName) ?: run {
            log(tag, WARN) { "Ignoring reopen with unknown charset: $charsetName" }
            return
        }
        val currentPath = ((_state.value as? State.Ready)?.editor?.contentSource as? ContentSource.File)?.path
            ?: run {
                log(tag, WARN) { "Cannot reopen with charset - no file open" }
                return
            }
        switchEngine(currentPath, charset)
    }

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

    private fun currentEngine(): EditorEngine =
        _engine.value ?: throw IllegalStateException("No engine available")

    suspend fun saveFile() {
        // TODO: Wrap as an Operation submitted via OperationsManager so editor saves appear in
        // the global Operation History (kind = SAVE, intendedPaths = [filePath]). Same applies to
        // auto-save call sites. Out of scope for History v1.
        // Progress is emitted by EditorEngine during save
        currentEngine().saveFile()
    }

    suspend fun saveFileAs(newFilePath: APath<*>): Result<Unit> {
        val engine = currentEngine()

        val currentPath = ((_state.value as? State.Ready)?.editor?.contentSource as? ContentSource.File)?.path
        if (currentPath == newFilePath) {
            // Streaming into the current file would truncate the very source the original byte
            // ranges are read from; the normal atomic save handles this case
            log(tag) { "Save-as targets the current file, using the atomic save path" }
            return engine.saveFile()
        }

        return try {
            log(tag) { "Saving as: ${newFilePath.name}" }

            // Engine streams content, Workspace handles file I/O
            gatewaySwitch.file(newFilePath, readWrite = true).use { handle ->
                handle.sink().buffer().use { sink ->
                    engine.writeContentTo(sink)
                }
            }

            log(tag) { "Content written to: ${newFilePath.name}" }

            // Switch to new engine with the new file path, keeping the active charset override
            switchEngine(newFilePath, charsetOverride)

            Result.success(Unit)
        } catch (e: Exception) {
            log(tag, ERROR) { "Failed to save as: ${newFilePath.name} - ${e.asLog()}" }
            Result.failure(e)
        }
    }

    suspend fun search(query: String, options: SearchOptions = SearchOptions()) =
        currentEngine().search(query, options)

    suspend fun goToLine(lineNumber: Long) = currentEngine().goToLine(lineNumber)
    suspend fun undo() = currentEngine().undo()
    suspend fun redo() = currentEngine().redo()
    suspend fun deleteSelection() = currentEngine().deleteSelection()
    suspend fun deleteAtCursor(count: Int) = currentEngine().deleteAtCursor(count)
    suspend fun copySelection() = currentEngine().copySelection()
    suspend fun selectAll() = currentEngine().selectAll()

    suspend fun insertText(text: String) = currentEngine().insertText(text)
    suspend fun replaceText(start: TextPosition, end: TextPosition, text: String, caret: TextPosition) =
        currentEngine().replaceText(start, end, text, caret)
    suspend fun setCursorPosition(position: TextPosition) = currentEngine().setCursorPosition(position)
    suspend fun setSelection(start: TextPosition, end: TextPosition) = currentEngine().setSelection(start, end)
    suspend fun updateVisibleRange(startLine: Long, endLine: Long) =
        currentEngine().updateVisibleRange(startLine, endLine)

    suspend fun moveCursor(direction: CursorDirection, extendSelection: Boolean) {
        log(tag) { "moveCursor(direction=$direction, extendSelection=$extendSelection)" }
        currentEngine().moveCursor(direction, extendSelection)
    }

    suspend fun deleteForward() {
        log(tag) { "deleteForward()" }
        currentEngine().deleteForward()
    }

    fun clearError() = _engine.value?.clearError()

    /** Reads file content for pasting from clipboard. */
    suspend fun readFileContent(path: APath<*>): Result<String> = pasteFileReader.read(path)

    override suspend fun release() {
        log(tag, INFO) { "release()" }
        workspaceScope.cancel()
        try {
            _engine.value?.release()
        } catch (e: Exception) {
            log(tag, ERROR) { "Failed to release engine: ${e.asLog()}" }
        }
    }

    data class EditorState(
        val contentSource: ContentSource = ContentSource.Memory(size = 0L),
        val totalLines: Long = 0,
        val isModified: Boolean = false,
        val currentContent: String = "",
        val cursorPosition: TextPosition = TextPosition.ZERO,
        val selectionRange: Pair<TextPosition, TextPosition>? = null,
        val searchQuery: String = "",
        val searchResults: List<SearchResult> = emptyList(),
        val visibleRange: LongRange = 0L..50L,
        val error: Throwable? = null,
        val showLineNumbers: Boolean = true,
        val wordWrap: Boolean = false,
        val progress: Progress.Data? = null,
        val canUndo: Boolean = false,
        val canRedo: Boolean = false,
    )

    sealed interface State {
        data object Initializing : State
        data class Ready(val editor: EditorState) : State {
            val progress: Progress.Data? get() = editor.progress
        }
        data class Error(val error: Throwable) : State
    }

    @AssistedFactory
    interface Factory : WorkspaceFactory<EditorArguments> {

        override fun create(id: Workspace.Id, arguments: EditorArguments): EditorWorkspace

        override val argumentsSerializer: KSerializer<EditorArguments> get() = serializer()
    }

    @Module
    @InstallIn(SingletonComponent::class)
    object FactoryModule {
        @Provides
        @IntoMap
        @WorkspaceTypeKey(Workspace.Type.EDITOR)
        fun factory(factory: Factory): WorkspaceFactory<*> = factory
    }
}
