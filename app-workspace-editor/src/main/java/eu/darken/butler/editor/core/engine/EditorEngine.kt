package eu.darken.butler.editor.core.engine

import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import eu.darken.butler.common.datastore.value
import eu.darken.butler.common.debug.logging.Logging.Priority.*
import eu.darken.butler.common.debug.logging.asLog
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.debug.logging.logTag
import eu.darken.butler.common.ca.toCaString
import eu.darken.butler.common.files.APath
import eu.darken.butler.common.files.GatewaySwitch
import eu.darken.butler.common.progress.Progress
import eu.darken.butler.editor.BuildConfig
import eu.darken.butler.editor.R
import eu.darken.butler.editor.core.EditorSettings
import eu.darken.butler.editor.core.engine.text.WindowedSearch
import eu.darken.butler.editor.core.sources.EditorDataSource
import eu.darken.butler.editor.core.sources.FileDataSource
import eu.darken.butler.editor.core.sources.InMemoryDataSource
import eu.darken.butler.editor.ui.editor.text.CursorDirection
import eu.darken.butler.workspace.core.Workspace
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okio.BufferedSink
import java.nio.charset.Charset
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.coroutineContext
import kotlin.math.abs

class EditorEngine @AssistedInject constructor(
    @Assisted private val workspaceId: Workspace.Id,
    // The engine's target file identity; stays authoritative even while contentSource still
    // reads Memory during load or after a failed initialization
    @Assisted val filePath: APath<*>?,
    @Assisted private val initialContent: String?,
    @Assisted private val charsetOverride: Charset? = null,
    private val gatewaySwitch: GatewaySwitch,
    private val editorSettings: EditorSettings,
    private val fileDataSourceFactory: FileDataSource.Factory,
    private val inMemoryDataSourceFactory: InMemoryDataSource.Factory,
    private val documentBufferFactory: DocumentBuffer.Factory,
) {
    private val tag = logTag("Editor", "Workspace", workspaceId.shortTag, "Engine")

    private val stateMutex = Mutex()
    private val _state = MutableStateFlow<EditorState>(EditorState.Empty)
    val state: StateFlow<EditorState> = _state.asStateFlow()

    private val _currentContent = MutableStateFlow("")
    val currentContent: StateFlow<String> = _currentContent.asStateFlow()

    private val _cursorPosition = MutableStateFlow(TextPosition.ZERO)
    val cursorPosition: StateFlow<TextPosition> = _cursorPosition.asStateFlow()

    private val _selectionRange = MutableStateFlow<Pair<TextPosition, TextPosition>?>(null)
    val selectionRange: StateFlow<Pair<TextPosition, TextPosition>?> = _selectionRange.asStateFlow()

    // Selection anchor for shift+arrow key selection
    private var selectionAnchor: TextPosition? = null

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    // Monotonic search request id (guarded by stateMutex): a scan publishes its results only
    // if it is still the latest request when it finishes
    private var searchRequestCounter = 0L

    // Structural version of the snapshot the current replace call expanded its regex against;
    // passed to replaceMatches so any intervening mutation aborts the apply
    private var regexSnapshotVersion: Long? = null

    private val _searchResults = MutableStateFlow<List<SearchResult>>(emptyList())
    val searchResults: StateFlow<List<SearchResult>> = _searchResults.asStateFlow()

    private val _visibleRange = MutableStateFlow(0L..50L)
    val visibleRange: StateFlow<LongRange> = _visibleRange.asStateFlow()

    private val _totalLines = MutableStateFlow(1L)
    val totalLines: StateFlow<Long> = _totalLines.asStateFlow()

    private val _error = MutableStateFlow<Throwable?>(null)
    val error: StateFlow<Throwable?> = _error.asStateFlow()

    private val _externalChange = MutableStateFlow<ExternalChange?>(null)

    /** Non-null while the open file is known to differ from what the buffer loaded. */
    val externalChange: StateFlow<ExternalChange?> = _externalChange.asStateFlow()

    // Monotonic per engine so a dismissed generation can never collide with a later detection
    private var externalChangeGeneration = 0

    private val _progress = MutableStateFlow<Progress.Data?>(null)
    val progress: StateFlow<Progress.Data?> = _progress.asStateFlow()

    private val isInitializing = AtomicBoolean(true)
    private var initializationJob: Job? = null

    val contentSource: Flow<ContentSource> = state.map { s ->
        when (s) {
            is EditorState.Loaded -> s.contentSource
            else -> ContentSource.Memory(size = 0L)
        }
    }

    val isModified: Flow<Boolean> = state.map { s ->
        when (s) {
            is EditorState.Loaded -> s.isModified
            else -> false
        }
    }

    val canUndo: Flow<Boolean> = state.flatMapLatest { s ->
        (s as? EditorState.Loaded)?.resources?.textBuffer?.canUndo ?: flowOf(false)
    }

    val canRedo: Flow<Boolean> = state.flatMapLatest { s ->
        (s as? EditorState.Loaded)?.resources?.textBuffer?.canRedo ?: flowOf(false)
    }

    val textBuffer: DocumentBuffer?
        get() = (state.value as? EditorState.Loaded)?.resources?.textBuffer

    /**
     * Backstop for read-only/binary sources: the UI disables input, but nothing may bypass it -
     * a mutation on an uneditable document is rejected here before touching the buffer.
     * Deliberately does NOT set [_error]: the read-only state is already visible in the UI and
     * a banner per swallowed keystroke would be noise.
     */
    private fun EditorState.Loaded.editabilityError(): ReadOnlyFileException? {
        val source = contentSource as? ContentSource.File ?: return null
        return when {
            source.isLikelyBinary -> ReadOnlyFileException("Binary file, editing is disabled: ${source.path}")
            !source.canWrite -> ReadOnlyFileException("File is read-only: ${source.path}")
            else -> null
        }
    }

    private suspend fun createResourcesForFile(filePath: APath<*>?): EditorResources {
        log(tag) { "Creating resources for file: ${filePath?.name ?: "in-memory"}" }

        // Create data source
        val dataSource = if (filePath != null) {
            fileDataSourceFactory.create(
                workspaceId = workspaceId,
                filePath = filePath,
                gatewaySwitch = gatewaySwitch,
                charsetOverride = charsetOverride,
            )
        } else {
            inMemoryDataSourceFactory.create(
                workspaceId = workspaceId,
                initialContent = initialContent ?: ""
            )
        }

        // Read undo settings
        val maxUndoStackSize = editorSettings.undoStackSize.value()
        val maxUndoMemoryBytes = editorSettings.undoMaxMemory.value()

        val textBuffer = documentBufferFactory.create(
            workspaceId = workspaceId,
            dataSource = dataSource,
            maxUndoStackSize = maxUndoStackSize,
            maxUndoMemoryBytes = maxUndoMemoryBytes,
            assertions = BuildConfig.DEBUG,
        )

        return EditorResources(
            dataSource = dataSource,
            textBuffer = textBuffer,
        )
    }

    private suspend fun disposeResources(resources: EditorResources, flush: Boolean = true) {
        log(tag) { "Disposing resources (flush=$flush)" }

        // Clean up in reverse order, don't abort on failures
        try {
            resources.textBuffer.release(flush)
        } catch (e: Exception) {
            log(tag, ERROR) { "Failed to release text buffer - ${e.asLog()}" }
        }

        try {
            resources.dataSource.close()
        } catch (e: Exception) {
            log(tag, ERROR) { "Failed to close data source - ${e.asLog()}" }
        }
    }

    suspend fun initialize(): Result<Unit> = stateMutex.withLock {
        // Capture the job for cancellation support
        initializationJob = currentCoroutineContext()[Job]

        return try {
            log(tag) { "Initializing engine with: ${filePath?.name ?: "in-memory editor"}" }

            // Transition to Loading state
            _state.value = if (filePath != null) {
                EditorState.Loading(filePath)
            } else {
                EditorState.Empty
            }

            // Create new resources
            val resources = createResourcesForFile(filePath)

            // Open data source
            resources.dataSource.open()
            currentCoroutineContext().ensureActive()

            // Initialize text buffer - progress updates flow to _progress StateFlow
            val bufferInitResult = resources.textBuffer.initialize { progressData ->
                _progress.value = progressData
            }
            currentCoroutineContext().ensureActive()
            if (bufferInitResult.isFailure) {
                val error = bufferInitResult.exceptionOrNull() ?: Exception("Unknown error")
                _state.value = EditorState.Error(error, _state.value)
                _error.value = error
                _progress.value = null
                disposeResources(resources)
                return bufferInitResult
            }

            // Update engine state from initialized buffer
            _totalLines.value = resources.textBuffer.totalLines.value

            // Load initial visible range content
            val endLine = minOf(50L, resources.textBuffer.totalLines.value - 1)
            if (endLine >= 0) {
                _visibleRange.value = 0L..endLine
                currentCoroutineContext().ensureActive()
                val contentResult = resources.textBuffer.getTextForRange(0, endLine)
                if (contentResult.isSuccess) {
                    _currentContent.value = contentResult.getOrNull() ?: ""
                }
            } else {
                _visibleRange.value = 0L..0L
                _currentContent.value = ""
            }

            // Transition to Loaded state
            val contentSourceValue = resources.textBuffer.contentSource.value
            val isModifiedValue = resources.textBuffer.isModified.value
            _state.value = EditorState.Loaded(
                filePath = filePath,
                resources = resources,
                contentSource = contentSourceValue,
                isModified = isModifiedValue,
            )

            log(tag) { "Successfully initialized engine with: ${filePath?.name ?: "in-memory editor"}" }
            isInitializing.set(false)
            initializationJob = null
            _progress.value = null
            Result.success(Unit)

        } catch (e: CancellationException) {
            log(tag, INFO) { "Engine initialization cancelled: ${filePath?.name}" }
            _state.value = EditorState.Empty
            initializationJob = null
            _progress.value = null
            Result.failure(e)
        } catch (e: Exception) {
            log(tag, ERROR) { "Failed to initialize engine: ${filePath?.name} - ${e.asLog()}" }
            _state.value = EditorState.Error(e, _state.value)
            _error.value = e
            initializationJob = null
            _progress.value = null
            Result.failure(e)
        }
    }

    /**
     * Cancels an in-progress file initialization.
     * Safe to call even if no initialization is running.
     */
    fun cancelInitialization() {
        initializationJob?.let { job ->
            log(tag, INFO) { "Cancelling initialization" }
            job.cancel()
            initializationJob = null
            _state.value = EditorState.Empty
            _progress.value = null
        }
    }

    suspend fun saveFile(): Result<Unit> = stateMutex.withLock {
        return when (val currentState = _state.value) {
            is EditorState.Loaded -> {
                try {
                    _progress.value = Progress.Data(
                        primary = R.string.editor_progress_saving.toCaString(),
                        count = Progress.Count.Indeterminate(),
                    )
                    log(tag) { "Saving file: ${currentState.filePath?.name ?: "in-memory"}" }
                    val result = currentState.resources.textBuffer.saveFile()
                    if (result.isFailure) {
                        _error.value = result.exceptionOrNull()
                        if (result.exceptionOrNull() is ExternalModificationException) {
                            flagSaveTimeExternalChange(currentState.resources.textBuffer)
                        }
                    } else {
                        // Update state with new isModified value
                        _state.value = currentState.copy(isModified = false)
                        // The rebase re-baselined against the on-disk state
                        _externalChange.value = null
                    }
                    result
                } catch (e: Exception) {
                    log(tag, ERROR) { "Failed to save file - ${e.asLog()}" }
                    _error.value = e
                    Result.failure(e)
                } finally {
                    _progress.value = null
                }
            }
            else -> {
                val error = IllegalStateException("Cannot save file - no file open")
                log(tag, WARN) { error.message ?: "Unknown error" }
                Result.failure(error)
            }
        }
    }

    /**
     * Streams the current document content (including unsaved edits) to [sink] - byte-identical
     * to what saving would write. Engine exposes content, Workspace handles file I/O operations.
     *
     * @throws IllegalStateException if no content is loaded
     */
    suspend fun writeContentTo(sink: BufferedSink): Unit = stateMutex.withLock {
        when (val currentState = _state.value) {
            is EditorState.Loaded -> {
                log(tag) { "Streaming current buffer content" }
                val result = currentState.resources.textBuffer.writeContentTo(sink)
                result.exceptionOrNull()?.let { e ->
                    if (e is ExternalModificationException) {
                        flagSaveTimeExternalChange(currentState.resources.textBuffer)
                    }
                    throw e
                }
            }
            else -> {
                throw IllegalStateException("Cannot stream content - no content available")
            }
        }
    }

    /**
     * Meta-only probe for on-disk changes of the open file; cheap enough to poll from the UI.
     * Serialized against saves via [stateMutex]. A re-observation with the same meta keeps the
     * current generation (a dismissed banner stays dismissed); a different meta re-arms it. A
     * probe matching the baseline again clears meta-based flags (the file was restored), but
     * never save-time digest flags (observedMeta == null, same-meta content changes) - and an
     * Unknown probe (deleted file, transient lookup failure) never clears anything.
     */
    suspend fun checkExternalChange(): Unit = stateMutex.withLock {
        val currentState = _state.value as? EditorState.Loaded ?: return@withLock
        if (currentState.contentSource !is ContentSource.File) return@withLock
        val probe = currentState.resources.textBuffer.checkExternalChange()
        val flagged = _externalChange.value
        when (probe) {
            is DocumentBuffer.ExternalChangeProbe.Unknown -> Unit
            is DocumentBuffer.ExternalChangeProbe.Unchanged -> if (flagged?.observedMeta != null) {
                log(tag, INFO) { "External change no longer observed, clearing" }
                _externalChange.value = null
            }
            is DocumentBuffer.ExternalChangeProbe.Changed -> when (flagged?.observedMeta) {
                // already flagged with this observation
                probe.meta -> Unit
                else -> {
                    log(tag, WARN) { "External change detected: ${probe.meta}" }
                    _externalChange.value = ExternalChange(++externalChangeGeneration, probe.meta)
                }
            }
        }
    }

    /**
     * A save/stream was refused because the file changed on disk; always re-arms the banner.
     * The cheap meta is captured when available so the next poll keeps the generation instead
     * of re-arming a dismissed banner for the same underlying change.
     */
    private suspend fun flagSaveTimeExternalChange(buffer: DocumentBuffer) {
        val observed = (buffer.checkExternalChange() as? DocumentBuffer.ExternalChangeProbe.Changed)?.meta
        _externalChange.value = ExternalChange(++externalChangeGeneration, observed)
        log(tag, WARN) { "External change flagged at save time: ${_externalChange.value}" }
    }

    /**
     * A detected on-disk change of the open file. [generation] is unique per detection event so
     * the UI can key dismissals; [observedMeta] is the differing lookup for poll detections and
     * null for save-time detections (which can have identical size and mtime).
     */
    data class ExternalChange(
        val generation: Int,
        val observedMeta: EditorDataSource.Meta?,
    )

    suspend fun insertText(text: String) = stateMutex.withLock {
        when (val currentState = _state.value) {
            is EditorState.Loaded -> {
                currentState.editabilityError()?.let {
                    log(tag, VERBOSE) { "insertText rejected: ${it.message}" }
                    return@withLock
                }
                // If there's a selection, delete it first (standard "replace selection" behavior)
                val (hadSelection, deleteResult) = deleteSelectionIfPresent(currentState)
                if (hadSelection && deleteResult?.isFailure == true) {
                    return@withLock // Selection delete failed, error already set
                }

                // Use current cursor position (will be at selection.first if selection was deleted)
                val cursorPos = _cursorPosition.value

                // Recalculate correct offset from line/column via the buffer
                // UI may send placeholder offset=0 with virtual scrolling
                val correctedOffset = currentState.resources.textBuffer.findOffset(
                    cursorPos.line,
                    cursorPos.column
                )

                val correctedPosition = TextPosition(
                    offset = correctedOffset,
                    line = cursorPos.line,
                    column = cursorPos.column
                )

                log(tag, VERBOSE) { "Inserting text at position $correctedPosition: ${text.take(50)}..." }

                val result = currentState.resources.textBuffer.insertText(correctedPosition, text)

                result.fold(
                    onSuccess = { newPosition ->
                        log(tag, VERBOSE) { "Text inserted successfully, new position: $newPosition" }

                        // Update cursor position from result
                        _cursorPosition.value = newPosition

                        // Mark as modified
                        _state.value = currentState.copy(isModified = true)

                        // Update total lines from text buffer
                        _totalLines.value = currentState.resources.textBuffer.totalLines.value

                        // Invalidate search results (positions are now stale)
                        invalidateSearchResults()

                        // Update visible content - use in-place update for small edits
                        if (text.length <= 10 && !text.contains('\n')) {
                            val cursorLine = correctedPosition.line
                            val visibleStart = _visibleRange.value.first
                            val lines = _currentContent.value.split('\n').toMutableList()
                            // Index into the loaded window; bounded by the window size, safe to narrow
                            val lineIndex = (cursorLine - visibleStart).toInt()
                            if (cursorLine in _visibleRange.value && lineIndex in lines.indices) {
                                val line = lines[lineIndex]
                                val col = correctedPosition.column.coerceAtMost(line.length)
                                lines[lineIndex] = line.substring(0, col) + text + line.substring(col)
                                _currentContent.value = lines.joinToString("\n")
                            } else {
                                refreshVisibleContent()
                            }
                        } else {
                            refreshVisibleContent()
                        }
                    },
                    onFailure = { e ->
                        log(tag, ERROR) { "Failed to insert text - ${e.asLog()}" }
                        _error.value = e
                    }
                )
            }
            else -> {
                log(tag, WARN) { "Cannot insert text - no file open" }
            }
        }
    }

    /**
     * Applies a single contiguous edit: replaces the [start]..[end] range (line/column positions from the
     * visible field, placeholder offsets resolved here) with [text], then places the cursor at [caret].
     *
     * This is the path all soft-keyboard input flows through. To keep one keystroke = one undo entry, pure
     * inserts and pure deletes are routed to the buffer's single-op methods; only genuine replacements
     * (e.g. autocorrect) use the composite delete+insert path.
     */
    suspend fun replaceText(
        start: TextPosition,
        end: TextPosition,
        text: String,
        caret: TextPosition,
    ) = stateMutex.withLock {
        val currentState = _state.value as? EditorState.Loaded
        if (currentState == null) {
            log(tag, WARN) { "Cannot replace text - no file open" }
            return@withLock
        }
        currentState.editabilityError()?.let {
            log(tag, VERBOSE) { "replaceText rejected: ${it.message}" }
            return@withLock
        }
        val buffer = currentState.resources.textBuffer

        // Resolve flat offsets from line/column - UI sends placeholder offset=0 with virtual scrolling.
        val startOffset = buffer.findOffset(start.line, start.column)
        val endOffset = buffer.findOffset(end.line, end.column)
        val lowPos: TextPosition
        val highPos: TextPosition
        if (startOffset <= endOffset) {
            lowPos = TextPosition(startOffset, start.line, start.column)
            highPos = TextPosition(endOffset, end.line, end.column)
        } else {
            lowPos = TextPosition(endOffset, end.line, end.column)
            highPos = TextPosition(startOffset, start.line, start.column)
        }

        val isEmptyRange = lowPos.offset == highPos.offset
        if (isEmptyRange && text.isEmpty()) {
            // Pure no-op: the field never emits this (computeTextEdit returns null when text is unchanged),
            // so there is nothing to edit and no cursor/selection state to disturb.
            return@withLock
        }

        log(tag, VERBOSE) { "replaceText $lowPos..$highPos -> ${text.take(50)} (caret=$caret)" }

        // Only keystroke-SIZED edits coalesce (<= 2 UTF-16 units covers surrogate-pair input):
        // platform paste and IME batch commits arrive through this same diff path as large pure
        // inserts and must neither join a typing run nor anchor one
        val keystrokeSized = text.length <= 2 && (highPos.offset - lowPos.offset) <= 2
        val result: Result<*> = when {
            // Keystroke-sized inserts/deletes merge into the current typing run (one undo
            // steps back over the run, not one character)
            isEmptyRange -> buffer.insertText(lowPos, text, coalesce = keystrokeSized) // pure insert
            text.isEmpty() -> buffer.deleteText(lowPos, highPos, coalesce = keystrokeSized) // pure delete
            else -> buffer.replaceText(lowPos, highPos, text) // genuine replace (delete + insert)
        }

        if (result.isSuccess) {
            _totalLines.value = buffer.totalLines.value
            updateCursorFromCaret(buffer, caret)
            _selectionRange.value = null
            selectionAnchor = null
            _state.value = currentState.copy(isModified = true)
            invalidateSearchResults()
            refreshVisibleContent()
        } else {
            val e = result.exceptionOrNull() ?: IllegalStateException("Unknown error replacing text")
            log(tag, ERROR) { "Failed to replace text - ${e.asLog()}" }
            _error.value = e
        }
    }

    /** Resolves [caret] (line/column from the visible field) to a buffer offset and sets it as the cursor. */
    private suspend fun updateCursorFromCaret(buffer: DocumentBuffer, caret: TextPosition) {
        val maxLine = (_totalLines.value - 1).coerceAtLeast(0)
        val safeLine = caret.line.coerceIn(0, maxLine)
        val caretOffset = try {
            buffer.findOffset(safeLine, caret.column)
        } catch (e: Exception) {
            buffer.totalLength.value
        }
        _cursorPosition.value = TextPosition(offset = caretOffset, line = safeLine, column = caret.column)
    }

    /**
     * Deletes the current selection if one exists.
     * Must be called within stateMutex.withLock.
     * @return Pair of (selection was deleted, deleted text result). If no selection, returns (false, null).
     */
    private suspend fun deleteSelectionIfPresent(
        currentState: EditorState.Loaded,
    ): Pair<Boolean, Result<String>?> {
        val selection = _selectionRange.value ?: return false to null

        val result = currentState.resources.textBuffer.deleteText(selection.first, selection.second)
        if (result.isSuccess) {
            _selectionRange.value = null
            _cursorPosition.value = selection.first
            _state.value = currentState.copy(isModified = true)
            _totalLines.value = currentState.resources.textBuffer.totalLines.value
            invalidateSearchResults()
            refreshVisibleContent()
        } else {
            _error.value = result.exceptionOrNull()
        }
        return true to result
    }

    suspend fun deleteSelection(): Result<String> = stateMutex.withLock {
        return when (val currentState = _state.value) {
            is EditorState.Loaded -> {
                currentState.editabilityError()?.let { return Result.failure(it) }
                val selection = _selectionRange.value ?: return Result.failure(
                    IllegalStateException("No selection to delete")
                )

                try {
                    val result = currentState.resources.textBuffer.deleteText(selection.first, selection.second)
                    if (result.isSuccess) {
                        _selectionRange.value = null
                        _cursorPosition.value = selection.first
                        _state.value = currentState.copy(isModified = true)
                        _totalLines.value = currentState.resources.textBuffer.totalLines.value
                        // Invalidate search results (positions are now stale)
                        invalidateSearchResults()
                        refreshVisibleContent()
                    } else {
                        _error.value = result.exceptionOrNull()
                    }
                    result
                } catch (e: Exception) {
                    log(tag, ERROR) { "Failed to delete selection - ${e.asLog()}" }
                    _error.value = e
                    Result.failure(e)
                }
            }
            else -> {
                val error = IllegalStateException("Cannot delete selection - no file open")
                log(tag, WARN) { error.message ?: "Unknown error" }
                Result.failure(error)
            }
        }
    }

    suspend fun deleteAtCursor(count: Int): Result<String> = stateMutex.withLock {
        return when (val currentState = _state.value) {
            is EditorState.Loaded -> {
                currentState.editabilityError()?.let { return Result.failure(it) }
                // If there's a selection, delete it instead of backspace (standard behavior)
                val (hadSelection, deleteResult) = deleteSelectionIfPresent(currentState)
                if (hadSelection) {
                    return deleteResult ?: Result.success("")
                }

                if (count <= 0) {
                    return Result.success("")
                }

                val cursorPos = _cursorPosition.value

                // Calculate start position, clamped to 0
                val startOffset = (cursorPos.offset - count).coerceAtLeast(0L)
                val actualCount = (cursorPos.offset - startOffset).toInt()

                if (actualCount <= 0) {
                    // Nothing to delete (cursor at start of document)
                    return Result.success("")
                }

                try {
                    // Find the line/column for start position
                    val startPosition = currentState.resources.textBuffer.findPosition(startOffset)
                    val endPosition = cursorPos

                    log(tag, VERBOSE) { "Deleting $actualCount characters at cursor: $startPosition to $endPosition" }

                    val result = currentState.resources.textBuffer.deleteText(startPosition, endPosition)
                    if (result.isSuccess) {
                        val deletedText = result.getOrNull() ?: ""
                        _cursorPosition.value = startPosition
                        _state.value = currentState.copy(isModified = true)
                        _totalLines.value = currentState.resources.textBuffer.totalLines.value
                        invalidateSearchResults()

                        // Update visible content - use in-place update for small single-line deletes
                        if (actualCount <= 10 && !deletedText.contains('\n') && startPosition.line == endPosition.line) {
                            val cursorLine = startPosition.line
                            val visibleStart = _visibleRange.value.first
                            val lines = _currentContent.value.split('\n').toMutableList()
                            // Index into the loaded window; bounded by the window size, safe to narrow
                            val lineIndex = (cursorLine - visibleStart).toInt()
                            if (cursorLine in _visibleRange.value && lineIndex in lines.indices) {
                                val line = lines[lineIndex]
                                val startCol = startPosition.column.coerceAtMost(line.length)
                                val endCol = endPosition.column.coerceAtMost(line.length)
                                lines[lineIndex] = line.substring(0, startCol) + line.substring(endCol)
                                _currentContent.value = lines.joinToString("\n")
                            } else {
                                refreshVisibleContent()
                            }
                        } else {
                            refreshVisibleContent()
                        }
                    } else {
                        _error.value = result.exceptionOrNull()
                    }
                    result
                } catch (e: Exception) {
                    log(tag, ERROR) { "Failed to delete at cursor - ${e.asLog()}" }
                    _error.value = e
                    Result.failure(e)
                }
            }
            else -> {
                val error = IllegalStateException("Cannot delete at cursor - no file open")
                log(tag, WARN) { error.message ?: "Unknown error" }
                Result.failure(error)
            }
        }
    }

    suspend fun copySelection(): Result<String> = stateMutex.withLock {
        return when (val currentState = _state.value) {
            is EditorState.Loaded -> {
                val selection = _selectionRange.value ?: return Result.failure(
                    IllegalStateException("No selection to copy")
                )

                try {
                    log(tag) { "Copying selection: ${selection.first} to ${selection.second}" }
                    currentState.resources.textBuffer.getText(selection.first.offset, selection.second.offset)
                } catch (e: Exception) {
                    log(tag, ERROR) { "Failed to copy selection - ${e.asLog()}" }
                    _error.value = e
                    Result.failure(e)
                }
            }
            else -> {
                val error = IllegalStateException("Cannot copy selection - no file open")
                log(tag, WARN) { error.message ?: "Unknown error" }
                Result.failure(error)
            }
        }
    }

    suspend fun selectAll(): Result<Pair<TextPosition, TextPosition>> = stateMutex.withLock {
        return when (val currentState = _state.value) {
            is EditorState.Loaded -> {
                try {
                    val startPosition = TextPosition(offset = 0, line = 0, column = 0)

                    val totalLength = currentState.resources.textBuffer.totalLength.value
                    val totalLines = _totalLines.value

                    // Get the last line to calculate its length for the column
                    val lastLineNumber = (totalLines - 1).coerceAtLeast(0)
                    val lastLineResult = currentState.resources.textBuffer.getTextForLine(lastLineNumber)
                    val lastLineLength = lastLineResult.getOrNull()?.length ?: 0

                    val endPosition = TextPosition(
                        offset = totalLength,
                        line = lastLineNumber,
                        column = lastLineLength
                    )

                    log(tag) { "Selecting all text: $startPosition to $endPosition" }

                    val selection = startPosition to endPosition
                    _selectionRange.value = selection

                    Result.success(selection)
                } catch (e: Exception) {
                    log(tag, ERROR) { "Failed to select all - ${e.asLog()}" }
                    _error.value = e
                    Result.failure(e)
                }
            }
            else -> {
                val error = IllegalStateException("Cannot select all - no file open")
                log(tag, WARN) { error.message ?: "Unknown error" }
                Result.failure(error)
            }
        }
    }

    suspend fun setCursorPosition(position: TextPosition) = stateMutex.withLock {
        textBuffer?.breakUndoRun()
        val correctedPosition = when (val currentState = _state.value) {
            is EditorState.Loaded -> TextPosition(
                offset = currentState.resources.textBuffer.findOffset(position.line, position.column),
                line = position.line,
                column = position.column
            )
            else -> position
        }
        _cursorPosition.value = correctedPosition
        _selectionRange.value = null
    }

    suspend fun setSelection(start: TextPosition, end: TextPosition) = stateMutex.withLock {
        textBuffer?.breakUndoRun()
        when (val currentState = _state.value) {
            is EditorState.Loaded -> {
                // Recalculate actual offsets from line/column positions
                // UI may send placeholder offset=0 with virtual scrolling
                val correctedStart = TextPosition(
                    offset = currentState.resources.textBuffer.findOffset(start.line, start.column),
                    line = start.line,
                    column = start.column
                )
                val correctedEnd = TextPosition(
                    offset = currentState.resources.textBuffer.findOffset(end.line, end.column),
                    line = end.line,
                    column = end.column
                )
                _selectionRange.value = correctedStart to correctedEnd
                _cursorPosition.value = correctedEnd
            }
            else -> {
                // No file loaded, store as-is
                _selectionRange.value = start to end
                _cursorPosition.value = end
            }
        }
    }

    suspend fun moveCursor(direction: CursorDirection, extendSelection: Boolean) = stateMutex.withLock {
        log(tag) { "moveCursor(direction=$direction, extendSelection=$extendSelection)" }
        val currentState = _state.value as? EditorState.Loaded
        if (currentState == null) {
            log(tag, WARN) { "moveCursor: No file loaded, ignoring" }
            return
        }
        val currentPos = _cursorPosition.value
        log(tag) { "moveCursor: currentPos=$currentPos" }
        currentState.resources.textBuffer.breakUndoRun()

        // Set anchor if starting selection
        if (extendSelection && selectionAnchor == null) {
            selectionAnchor = currentPos
            log(tag) { "moveCursor: Set selection anchor to $currentPos" }
        }

        // Calculate new position based on direction
        val newPos = when (direction) {
            CursorDirection.LEFT -> moveCursorLeft(currentPos, currentState)
            CursorDirection.RIGHT -> moveCursorRight(currentPos, currentState)
            CursorDirection.UP -> moveCursorUp(currentPos, currentState)
            CursorDirection.DOWN -> moveCursorDown(currentPos, currentState)
            CursorDirection.WORD_LEFT -> moveCursorWordLeft(currentPos, currentState)
            CursorDirection.WORD_RIGHT -> moveCursorWordRight(currentPos, currentState)
            CursorDirection.LINE_START -> moveCursorToLineStart(currentPos, currentState)
            CursorDirection.LINE_END -> moveCursorToLineEnd(currentPos, currentState)
        }

        log(tag) { "moveCursor: newPos=$newPos (was $currentPos)" }
        _cursorPosition.value = newPos

        if (extendSelection) {
            // Update selection from anchor to cursor
            val anchor = selectionAnchor!!
            _selectionRange.value = if (anchor.offset <= newPos.offset) {
                anchor to newPos
            } else {
                newPos to anchor
            }
            log(tag) { "moveCursor: Selection updated to ${_selectionRange.value}" }
        } else {
            // Clear selection and anchor
            selectionAnchor = null
            _selectionRange.value = null
        }
    }

    private suspend fun moveCursorLeft(pos: TextPosition, state: EditorState.Loaded): TextPosition {
        return if (pos.column > 0) {
            // Move left within line
            val newOffset = state.resources.textBuffer.findOffset(pos.line, pos.column - 1)
            TextPosition(offset = newOffset, line = pos.line, column = pos.column - 1)
        } else if (pos.line > 0) {
            // Move to end of previous line
            val prevLineLength = getLineLength(pos.line - 1, state)
            val newOffset = state.resources.textBuffer.findOffset(pos.line - 1, prevLineLength)
            TextPosition(offset = newOffset, line = pos.line - 1, column = prevLineLength)
        } else {
            // Already at start of document
            pos
        }
    }

    private suspend fun moveCursorRight(pos: TextPosition, state: EditorState.Loaded): TextPosition {
        val lineLength = getLineLength(pos.line, state)
        val totalLines = _totalLines.value

        return if (pos.column < lineLength) {
            // Move right within line
            val newOffset = state.resources.textBuffer.findOffset(pos.line, pos.column + 1)
            TextPosition(offset = newOffset, line = pos.line, column = pos.column + 1)
        } else if (pos.line < totalLines - 1) {
            // Move to start of next line
            val newOffset = state.resources.textBuffer.findOffset(pos.line + 1, 0)
            TextPosition(offset = newOffset, line = pos.line + 1, column = 0)
        } else {
            // Already at end of document
            pos
        }
    }

    private suspend fun moveCursorUp(pos: TextPosition, state: EditorState.Loaded): TextPosition {
        return if (pos.line > 0) {
            val newLine = pos.line - 1
            val prevLineLength = getLineLength(newLine, state)
            val newColumn = minOf(pos.column, prevLineLength)
            val newOffset = state.resources.textBuffer.findOffset(newLine, newColumn)
            TextPosition(offset = newOffset, line = newLine, column = newColumn)
        } else {
            // Already on first line
            pos
        }
    }

    private suspend fun moveCursorDown(pos: TextPosition, state: EditorState.Loaded): TextPosition {
        val totalLines = _totalLines.value
        return if (pos.line < totalLines - 1) {
            val newLine = pos.line + 1
            val nextLineLength = getLineLength(newLine, state)
            val newColumn = minOf(pos.column, nextLineLength)
            val newOffset = state.resources.textBuffer.findOffset(newLine, newColumn)
            TextPosition(offset = newOffset, line = newLine, column = newColumn)
        } else {
            // Already on last line
            pos
        }
    }

    private suspend fun moveCursorWordLeft(pos: TextPosition, state: EditorState.Loaded): TextPosition {
        val lineContent = getLineContent(pos.line, state)
        var column = pos.column
        var line = pos.line

        // Skip whitespace backwards
        while (column > 0 && lineContent.getOrNull(column - 1)?.isWhitespace() == true) {
            column--
        }

        // If at start of line, move to end of previous line
        if (column == 0 && line > 0) {
            line--
            val prevLineContent = getLineContent(line, state)
            column = prevLineContent.length
            // Skip whitespace at end of previous line
            while (column > 0 && prevLineContent.getOrNull(column - 1)?.isWhitespace() == true) {
                column--
            }
            // Skip word chars
            while (column > 0 && prevLineContent.getOrNull(column - 1)?.isWordChar() == true) {
                column--
            }
        } else {
            // Skip word characters backwards
            while (column > 0 && lineContent.getOrNull(column - 1)?.isWordChar() == true) {
                column--
            }
        }

        val newOffset = state.resources.textBuffer.findOffset(line, column)
        return TextPosition(offset = newOffset, line = line, column = column)
    }

    private suspend fun moveCursorWordRight(pos: TextPosition, state: EditorState.Loaded): TextPosition {
        val lineContent = getLineContent(pos.line, state)
        var column = pos.column
        var line = pos.line
        val totalLines = _totalLines.value

        // Skip word chars forwards
        while (column < lineContent.length && lineContent.getOrNull(column)?.isWordChar() == true) {
            column++
        }

        // Skip whitespace forwards
        while (column < lineContent.length && lineContent.getOrNull(column)?.isWhitespace() == true) {
            column++
        }

        // If at end of line, move to start of next line
        if (column >= lineContent.length && line < totalLines - 1) {
            line++
            column = 0
            val nextLineContent = getLineContent(line, state)
            // Skip leading whitespace
            while (column < nextLineContent.length && nextLineContent.getOrNull(column)?.isWhitespace() == true) {
                column++
            }
        }

        val newOffset = state.resources.textBuffer.findOffset(line, column)
        return TextPosition(offset = newOffset, line = line, column = column)
    }

    private suspend fun moveCursorToLineStart(pos: TextPosition, state: EditorState.Loaded): TextPosition {
        val newOffset = state.resources.textBuffer.findOffset(pos.line, 0)
        return TextPosition(offset = newOffset, line = pos.line, column = 0)
    }

    private suspend fun moveCursorToLineEnd(pos: TextPosition, state: EditorState.Loaded): TextPosition {
        val lineLength = getLineLength(pos.line, state)
        val newOffset = state.resources.textBuffer.findOffset(pos.line, lineLength)
        return TextPosition(offset = newOffset, line = pos.line, column = lineLength)
    }

    private suspend fun getLineLength(lineNumber: Long, state: EditorState.Loaded): Int {
        val result = state.resources.textBuffer.getTextForLine(lineNumber)
        return result.getOrNull()?.length ?: 0
    }

    private suspend fun getLineContent(lineNumber: Long, state: EditorState.Loaded): String {
        val result = state.resources.textBuffer.getTextForLine(lineNumber)
        return result.getOrNull() ?: ""
    }

    private fun Char.isWordChar(): Boolean {
        return this.isLetterOrDigit() || this == '_'
    }

    suspend fun deleteForward(): Result<String> = stateMutex.withLock {
        val currentState = _state.value as? EditorState.Loaded
            ?: return Result.failure(IllegalStateException("Cannot delete forward - no file open"))

        currentState.editabilityError()?.let { return Result.failure(it) }
        // If there's a selection, delete it instead of forward-delete (standard behavior)
        val (hadSelection, deleteResult) = deleteSelectionIfPresent(currentState)
        if (hadSelection) {
            return deleteResult ?: Result.success("")
        }

        val cursorPos = _cursorPosition.value
        val totalLength = currentState.resources.textBuffer.totalLength.value

        if (cursorPos.offset >= totalLength) {
            return Result.success("") // Nothing to delete at end
        }

        // Delete 1 character forward (from cursor to cursor+1)
        val endPosition = currentState.resources.textBuffer.findPosition(cursorPos.offset + 1)

        log(tag, VERBOSE) { "Forward delete at $cursorPos to $endPosition" }

        val result = currentState.resources.textBuffer.deleteText(cursorPos, endPosition)
        if (result.isSuccess) {
            _state.value = currentState.copy(isModified = true)
            _totalLines.value = currentState.resources.textBuffer.totalLines.value
            invalidateSearchResults()
            refreshVisibleContent()
        } else {
            _error.value = result.exceptionOrNull()
        }
        return result
    }

    /**
     * The scan itself runs OUTSIDE [stateMutex] so typing never queues behind a whole-document
     * search. Results are published only if this is still the LATEST search request (a newer
     * query/options change supersedes it) AND no edit invalidated the query in the meantime.
     */
    suspend fun search(query: String, options: SearchOptions = SearchOptions()): Result<List<SearchResult>> {
        val (buffer, requestId) = stateMutex.withLock {
            _searchQuery.value = query
            val id = ++searchRequestCounter

            if (query.isEmpty()) {
                _searchResults.value = emptyList()
                return Result.success(emptyList())
            }
            val loaded = _state.value as? EditorState.Loaded ?: run {
                val error = IllegalStateException("Cannot search - no file open")
                log(tag, WARN) { error.message ?: "Unknown error" }
                return Result.failure(error)
            }
            loaded.resources.textBuffer to id
        }

        val result = buffer.search(query, options)

        stateMutex.withLock {
            val isLatest = requestId == searchRequestCounter && _searchQuery.value == query
            result.fold(
                onSuccess = { if (isLatest) _searchResults.value = it },
                onFailure = { e ->
                    // Never leave stale positions highlighted under the failed (latest) query
                    if (isLatest) _searchResults.value = emptyList()
                    if (e !is SearchInvalidatedException) {
                        log(tag, ERROR) { "Failed to search - ${e.asLog()}" }
                        _error.value = e
                    }
                },
            )
        }
        return result
    }

    data class ReplaceOutcome(
        val results: List<SearchResult>,
        /** Index of the first remaining match after the replacement, for cursor advancement. */
        val nextIndex: Int,
    )

    data class ReplaceAllOutcome(
        val count: Int,
        /** False when the composite undo entry was evicted by the memory cap. */
        val undoable: Boolean,
    )

    /**
     * Replaces the single [match] with [replacement] as one undo step, then re-runs the search
     * and reports where the cursor should land next. The match is re-verified against the live
     * document; a divergence fails with [StaleMatchException] and nothing changes.
     */
    suspend fun replaceCurrent(
        query: String,
        options: SearchOptions,
        match: SearchResult,
        replacement: String,
    ): Result<ReplaceOutcome> {
        val buffer = stateMutex.withLock {
            val loaded = _state.value as? EditorState.Loaded
                ?: return Result.failure(IllegalStateException("Cannot replace - no file open"))
            loaded.editabilityError()?.let { return Result.failure(it) }
            loaded.resources.textBuffer
        }

        val newText = if (options.useRegex) {
            expandRegexReplacementAt(buffer, query, options, match, replacement)
                .getOrElse { return Result.failure(it) }
        } else {
            replacement
        }

        buffer.replaceMatches(
            listOf(DocumentBuffer.MatchReplacement(match.position.offset, match.matchText, newText)),
            expectedVersion = regexSnapshotVersion.takeIf { options.useRegex },
        ).getOrElse { return Result.failure(it) }
        regexSnapshotVersion = null

        val replacementEnd = match.position.offset + newText.length
        refreshAfterMutation(cursorOffset = replacementEnd)

        val results = search(query, options).getOrElse { emptyList() }
        val nextIndex = results.indexOfFirst { it.position.offset >= replacementEnd }
            .let { if (it == -1) 0 else it }
        return Result.success(ReplaceOutcome(results, nextIndex))
    }

    /**
     * Replaces EVERY match of [query] with [replacement] as one undo step. The search is re-run
     * internally (UI-held results are never trusted). Regex mode supports `$1` group references
     * and `\$` literal dollars with Kotlin `Regex.replace` semantics, is precomputed before any
     * mutation, and only works under the full-scan cap - above it windowed regex results are
     * unreliable and replacing based on them could corrupt the document.
     */
    suspend fun replaceAll(
        query: String,
        options: SearchOptions,
        replacement: String,
    ): Result<ReplaceAllOutcome> {
        val buffer = stateMutex.withLock {
            val loaded = _state.value as? EditorState.Loaded
                ?: return Result.failure(IllegalStateException("Cannot replace - no file open"))
            loaded.editabilityError()?.let { return Result.failure(it) }
            loaded.resources.textBuffer
        }

        val replacements: List<DocumentBuffer.MatchReplacement> = if (options.useRegex) {
            if (buffer.totalLength.value > WindowedSearch.REGEX_FULL_SCAN_CAP) {
                return Result.failure(
                    IllegalStateException(
                        "Replace all with regex is unavailable for documents over " +
                            "${WindowedSearch.REGEX_FULL_SCAN_CAP} characters",
                    ),
                )
            }
            val (text, snapshotVersion) = buffer.getFullTextWithVersion().getOrElse { return Result.failure(it) }
            val regexOptions = buildSet { if (!options.caseSensitive) add(RegexOption.IGNORE_CASE) }
            val regex = try {
                Regex(query, regexOptions)
            } catch (e: Exception) {
                return Result.failure(IllegalArgumentException("Invalid regex pattern: ${e.message}", e))
            }
            regexSnapshotVersion = snapshotVersion
            try {
                regex.findAll(text)
                    .filter { it.value.isNotEmpty() }
                    .map { m ->
                        DocumentBuffer.MatchReplacement(
                            startOffset = m.range.first.toLong(),
                            oldText = m.value,
                            newText = expandReplacementTemplate(replacement, m),
                        )
                    }
                    .toList()
            } catch (e: IllegalArgumentException) {
                // Bad group reference: precomputation failed, the document is untouched
                return Result.failure(e)
            }
        } else {
            val matches = buffer.search(query, options).getOrElse { return Result.failure(it) }
            matches.map { DocumentBuffer.MatchReplacement(it.position.offset, it.matchText, replacement) }
        }

        if (replacements.isEmpty()) return Result.success(ReplaceAllOutcome(0, undoable = true))

        val stats = buffer.replaceMatches(replacements, expectedVersion = regexSnapshotVersion.takeIf { options.useRegex })
            .getOrElse { return Result.failure(it) }
        regexSnapshotVersion = null

        refreshAfterMutation(cursorOffset = null)
        search(query, options)

        return Result.success(ReplaceAllOutcome(stats.count, stats.undoable))
    }

    /** Post-mutation UI refresh shared by the replace operations; one lock, no interleaving. */
    private suspend fun refreshAfterMutation(cursorOffset: Long?) {
        stateMutex.withLock {
            val currentState = _state.value as? EditorState.Loaded ?: return@withLock
            val buffer = currentState.resources.textBuffer
            _totalLines.value = buffer.totalLines.value
            // Read the flag from the buffer instead of assuming true: an undo/redo/save that
            // interleaved between the buffer mutation and this refresh must not be overwritten
            _state.value = currentState.copy(isModified = buffer.isModified.value)
            _selectionRange.value = null
            selectionAnchor = null
            cursorOffset?.let { _cursorPosition.value = buffer.findPosition(it) }
            invalidateSearchResults()
            refreshVisibleContent()
        }
    }

    /**
     * Resolves the regex match at the SearchResult's offset so group references can expand.
     * Only valid under the full-scan cap - the same boundary the search side documents.
     */
    private suspend fun expandRegexReplacementAt(
        buffer: DocumentBuffer,
        query: String,
        options: SearchOptions,
        match: SearchResult,
        replacement: String,
    ): Result<String> {
        if (buffer.totalLength.value > WindowedSearch.REGEX_FULL_SCAN_CAP) {
            return Result.failure(
                IllegalStateException(
                    "Replace with regex is unavailable for documents over " +
                        "${WindowedSearch.REGEX_FULL_SCAN_CAP} characters",
                ),
            )
        }
        val (text, snapshotVersion) = buffer.getFullTextWithVersion().getOrElse { return Result.failure(it) }
        val regexOptions = buildSet { if (!options.caseSensitive) add(RegexOption.IGNORE_CASE) }
        val regex = try {
            Regex(query, regexOptions)
        } catch (e: Exception) {
            return Result.failure(IllegalArgumentException("Invalid regex pattern: ${e.message}", e))
        }
        regexSnapshotVersion = snapshotVersion
        val liveMatch = regex.findAll(text).firstOrNull { it.range.first.toLong() == match.position.offset }
            ?: return Result.failure(StaleMatchException())
        return try {
            Result.success(expandReplacementTemplate(replacement, liveMatch))
        } catch (e: IllegalArgumentException) {
            Result.failure(e)
        }
    }

    suspend fun goToLine(lineNumber: Long): Result<Unit> {
        return try {
            val currentState = _state.value as? EditorState.Loaded
                ?: return Result.failure(IllegalStateException("Cannot go to line - no file open"))

            val totalLines = _totalLines.value
            if (lineNumber !in 0L..<totalLines) {
                return Result.failure(
                    IllegalArgumentException("Line $lineNumber out of range (0..$totalLines)")
                )
            }

            // Use textBuffer to find correct offset (works for any line, not just visible range)
            currentState.resources.textBuffer.breakUndoRun()
            val offset = currentState.resources.textBuffer.findOffset(lineNumber, 0)

            val position = TextPosition(
                offset = offset,
                line = lineNumber,
                column = 0
            )
            _cursorPosition.value = position

            // Update visible range to include this line
            val visibleStart = (lineNumber - 25).coerceAtLeast(0)
            val visibleEnd = (lineNumber + 25).coerceAtMost(totalLines - 1)
            updateVisibleRange(visibleStart, visibleEnd)

            Result.success(Unit)
        } catch (e: Exception) {
            log(tag, ERROR) { "Failed to go to line: $lineNumber - ${e.asLog()}" }
            _error.value = e
            Result.failure(e)
        }
    }

    private fun invalidateSearchResults() {
        _searchResults.value = emptyList()
        _searchQuery.value = ""
    }

    private suspend fun refreshVisibleContent() {
        val currentState = _state.value as? EditorState.Loaded ?: return
        val maxLine = (_totalLines.value - 1).coerceAtLeast(0)
        val currentRange = _visibleRange.value
        val first = currentRange.first.coerceIn(0, maxLine)
        // Ensure the loaded window covers the cursor line. An edit that adds lines (e.g. splitting a line
        // with a newline) must render the new lines even in a document too short to scroll — otherwise the
        // range never grows (updateVisibleRange ignores sub-3-line changes) and the new lines render blank.
        val cursorLine = _cursorPosition.value.line.coerceIn(0, maxLine)
        val last = maxOf(currentRange.last, cursorLine).coerceIn(first, maxLine)
        val range = first..last
        _visibleRange.value = range

        try {
            currentCoroutineContext().ensureActive()
            val contentResult = currentState.resources.textBuffer.getTextForRange(range.first, range.last)
            if (contentResult.isSuccess) {
                _currentContent.value = contentResult.getOrNull() ?: ""
                log(tag) { "Refreshed visible content for range: ${range.first}..${range.last}" }
            } else {
                log(tag, WARN) { "Failed to refresh content: ${contentResult.exceptionOrNull()?.asLog()}" }
            }
        } catch (e: Exception) {
            log(tag, ERROR) { "Error refreshing visible content - ${e.asLog()}" }
        }
    }

    suspend fun updateVisibleRange(startLine: Long, endLine: Long) = stateMutex.withLock {
        if (isInitializing.get()) {
            log(tag) { "Ignoring visible range update during initialization: $startLine..$endLine" }
            return
        }

        val currentState = _state.value
        if (currentState !is EditorState.Loaded) {
            log(tag) { "Ignoring visible range update - no file loaded" }
            return
        }

        val totalLines = _totalLines.value
        if (totalLines <= 0) return

        val constrainedStart = startLine.coerceIn(0, totalLines - 1)
        val constrainedEnd = endLine.coerceIn(constrainedStart, totalLines - 1)
        val newRange = constrainedStart..constrainedEnd

        val currentRange = _visibleRange.value
        val rangeChanged = currentRange != newRange
        // Only reload if range shifted by at least 3 lines to reduce load frequency during scroll
        val significantChange = abs(currentRange.first - newRange.first) >= 3 ||
            abs(currentRange.last - newRange.last) >= 3

        if (rangeChanged && significantChange) {
            _visibleRange.value = newRange

            // Load content for the new visible range
            try {
                val contentResult = currentState.resources.textBuffer.getTextForRange(constrainedStart, constrainedEnd)
                if (contentResult.isSuccess) {
                    _currentContent.value = contentResult.getOrNull() ?: ""
                    log(tag) { "Loaded content for range: $constrainedStart..$constrainedEnd" }
                } else {
                    log(tag, WARN) { "Failed to load content for range: ${contentResult.exceptionOrNull()?.asLog()}" }
                }
            } catch (e: Exception) {
                log(tag, ERROR) { "Error loading content for visible range - ${e.asLog()}" }
            }
        }
    }

    suspend fun undo(): Result<EditOperation?> = stateMutex.withLock {
        return when (val currentState = _state.value) {
            is EditorState.Loaded -> {
                currentState.editabilityError()?.let { return Result.failure(it) }
                try {
                    val result = currentState.resources.textBuffer.undo()
                    if (result.isSuccess) {
                        val buffer = currentState.resources.textBuffer
                        _totalLines.value = buffer.totalLines.value
                        // The Loaded state's isModified is a snapshot; undo can cross the save
                        // point in either direction, so it must be re-read from the buffer or
                        // auto-save and the unsaved-changes close warning act on stale state
                        _state.value = currentState.copy(isModified = buffer.isModified.value)
                        invalidateSearchResults()
                        refreshVisibleContent()

                        // Update cursor position based on undone operation
                        result.getOrNull()?.let { operation ->
                            val newCursorPosition = when (operation) {
                                is EditOperation.Insert -> operation.position
                                is EditOperation.Delete -> computeEndPosition(operation.position, operation.deletedText)
                                is EditOperation.Replace -> computeEndPosition(operation.position, operation.oldText)
                            }
                            _cursorPosition.value = newCursorPosition
                            _selectionRange.value = null
                        }
                    }
                    result
                } catch (e: Exception) {
                    log(tag, ERROR) { "Failed to undo - ${e.asLog()}" }
                    _error.value = e
                    Result.failure(e)
                }
            }
            else -> {
                val error = IllegalStateException("Cannot undo - no file open")
                log(tag, WARN) { error.message ?: "Unknown error" }
                Result.failure(error)
            }
        }
    }

    suspend fun redo(): Result<EditOperation?> = stateMutex.withLock {
        return when (val currentState = _state.value) {
            is EditorState.Loaded -> {
                currentState.editabilityError()?.let { return Result.failure(it) }
                try {
                    val result = currentState.resources.textBuffer.redo()
                    if (result.isSuccess) {
                        val buffer = currentState.resources.textBuffer
                        _totalLines.value = buffer.totalLines.value
                        // See undo(): the isModified snapshot must follow the buffer across
                        // save-point crossings
                        _state.value = currentState.copy(isModified = buffer.isModified.value)
                        invalidateSearchResults()
                        refreshVisibleContent()

                        // Update cursor position based on redone operation
                        result.getOrNull()?.let { operation ->
                            val newCursorPosition = when (operation) {
                                is EditOperation.Insert -> computeEndPosition(operation.position, operation.text)
                                is EditOperation.Delete -> operation.position
                                is EditOperation.Replace -> computeEndPosition(operation.position, operation.newText)
                            }
                            _cursorPosition.value = newCursorPosition
                            _selectionRange.value = null
                        }
                    }
                    result
                } catch (e: Exception) {
                    log(tag, ERROR) { "Failed to redo - ${e.asLog()}" }
                    _error.value = e
                    Result.failure(e)
                }
            }
            else -> {
                val error = IllegalStateException("Cannot redo - no file open")
                log(tag, WARN) { error.message ?: "Unknown error" }
                Result.failure(error)
            }
        }
    }

    private fun computeEndPosition(start: TextPosition, text: String): TextPosition {
        val newlineCount = text.count { it == '\n' }
        return TextPosition(
            offset = start.offset + text.length,
            line = start.line + newlineCount,
            column = if (newlineCount > 0) {
                text.length - text.lastIndexOf('\n') - 1
            } else {
                start.column + text.length
            },
        )
    }

    fun canUndo(): Boolean {
        val currentState = _state.value
        return (currentState as? EditorState.Loaded)?.resources?.textBuffer?.canUndo() ?: false
    }

    fun canRedo(): Boolean {
        val currentState = _state.value
        return (currentState as? EditorState.Loaded)?.resources?.textBuffer?.canRedo() ?: false
    }

    fun clearError() {
        _error.value = null
    }

    /** Releases the engine; [flush] false discards unsaved changes (explicit user choice). */
    suspend fun release(flush: Boolean = true) = stateMutex.withLock {
        log(tag, INFO) { "release(flush=$flush)" }
        val currentState = _state.value
        if (currentState is EditorState.Loaded) {
            try {
                disposeResources(currentState.resources, flush)
                _state.value = EditorState.Empty
            } catch (e: Exception) {
                log(tag, ERROR) { "Failed to dispose resources: ${e.asLog()}" }
            }
        }
    }

    @AssistedFactory
    interface Factory {
        fun create(
            workspaceId: Workspace.Id,
            filePath: APath<*>?,
            initialContent: String? = null,
            charsetOverride: Charset? = null,
        ): EditorEngine
    }

    companion object {
        /**
         * Expands `$N` group references with Kotlin `Regex.replace` semantics: `\$` is a
         * literal dollar, a `$` must be followed by digits, unknown groups are an error.
         * Throws [IllegalArgumentException] so callers can fail BEFORE mutating anything.
         */
        internal fun expandReplacementTemplate(template: String, match: MatchResult): String {
            val sb = StringBuilder(template.length)
            var i = 0
            while (i < template.length) {
                val c = template[i]
                when {
                    c == '\\' && i + 1 < template.length && template[i + 1] == '$' -> {
                        sb.append('$')
                        i += 2
                    }
                    c == '$' -> {
                        // JVM semantics: consume the LONGEST digit run that still names an
                        // existing group ("$12" with one group = group 1 + literal '2')
                        var j = i + 1
                        var group = -1
                        while (j < template.length && template[j].isDigit()) {
                            val candidate = group.coerceAtLeast(0) * 10 + (template[j] - '0')
                            if (candidate >= match.groupValues.size) break
                            group = candidate
                            j++
                        }
                        require(group >= 0) {
                            if (j < template.length && template[j].isDigit()) {
                                "Group \$${template[j]} is not in the pattern"
                            } else {
                                "Lone '$' at index $i - use \\$ for a literal dollar"
                            }
                        }
                        sb.append(match.groupValues[group])
                        i = j
                    }
                    else -> {
                        sb.append(c)
                        i++
                    }
                }
            }
            return sb.toString()
        }
    }
}