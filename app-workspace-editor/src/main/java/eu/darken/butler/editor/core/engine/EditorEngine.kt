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
import eu.darken.butler.common.coroutine.DispatcherProvider
import eu.darken.butler.common.files.APath
import eu.darken.butler.common.files.GatewaySwitch
import eu.darken.butler.common.progress.Progress
import eu.darken.butler.editor.BuildConfig
import eu.darken.butler.editor.R
import eu.darken.butler.editor.core.EditorSettings
import eu.darken.butler.editor.core.engine.text.WindowedSearch
import eu.darken.butler.editor.core.syntax.EditorHighlighter
import eu.darken.butler.editor.core.syntax.Language
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
import java.io.IOException
import java.nio.charset.Charset
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.coroutineContext
import kotlin.math.abs
import kotlin.uuid.Uuid

class EditorEngine @AssistedInject constructor(
    @Assisted private val workspaceId: Workspace.Id,
    // The engine's target file identity; stays authoritative even while contentSource still
    // reads Memory during load or after a failed initialization
    @Assisted val filePath: APath<*>?,
    @Assisted private val initialContent: String?,
    @Assisted private val charsetOverride: Charset? = null,
    private val gatewaySwitch: GatewaySwitch,
    private val editorSettings: EditorSettings,
    private val dispatcherProvider: DispatcherProvider,
    private val fileDataSourceFactory: FileDataSource.Factory,
    private val inMemoryDataSourceFactory: InMemoryDataSource.Factory,
    private val documentBufferFactory: DocumentBuffer.Factory,
) {
    private val tag = logTag("Editor", "Workspace", workspaceId.shortTag, "Engine")

    private val stateMutex = Mutex()
    private val _state = MutableStateFlow<EditorState>(EditorState.Empty)
    val state: StateFlow<EditorState> = _state.asStateFlow()

    /**
     * Identity of a document state: [structuralVersion] restarts whenever the buffer is replaced
     * (reload, Save-As, encoding switch), so [engineEpoch] pins it to ONE engine instance - a
     * request queued against one document can never be accepted against another.
     */
    data class DocumentToken(
        val engineEpoch: Uuid,
        val structuralVersion: Long,
    )

    private val engineEpoch: Uuid = Uuid.random()

    /**
     * The visible window's display text plus its per-line hidden-char maps as ONE value: independent
     * flows could pair capped text with a stale map mid-combine (SearchState precedent). Both maps are
     * keyed by ABSOLUTE line number, non-zero entries only. [truncatedLines] is the trailing-hidden
     * count per line; [startColumns] is the leading-hidden count (the window's anchor column).
     *
     * [rangeStart] (the window's first absolute line) and [token] belong to the SAME value for the
     * same reason: the input session maps its edits through this triple, and pairing the text with a
     * separately published range or version would map an edit onto the wrong line or document.
     * [token] is null only before the first window has been read.
     */
    data class VisibleContent(
        val text: String = "",
        val truncatedLines: Map<Long, Long> = emptyMap(),
        val startColumns: Map<Long, Long> = emptyMap(),
        val rangeStart: Long = 0L,
        val token: DocumentToken? = null,
    )

    private val _visibleContent = MutableStateFlow(VisibleContent())
    val visibleContent: StateFlow<VisibleContent> = _visibleContent.asStateFlow()

    private val _cursorPosition = MutableStateFlow(TextPosition.ZERO)
    val cursorPosition: StateFlow<TextPosition> = _cursorPosition.asStateFlow()

    private val _selectionRange = MutableStateFlow<Pair<TextPosition, TextPosition>?>(null)
    val selectionRange: StateFlow<Pair<TextPosition, TextPosition>?> = _selectionRange.asStateFlow()

    // Selection anchor for shift+arrow key selection
    private var selectionAnchor: TextPosition? = null

    /**
     * Raw column the display window starts at for long lines (horizontal chunking). A SINGLE shared
     * viewport anchor (matches the one shared horizontal scrollbar): cursor movement slides it via
     * [ensureColumnVisible] so the caret is always in the loaded slice; lines shorter than the cap
     * ignore it (their slice clamps to column 0). Mutated only under [stateMutex].
     */
    private var viewportColumnAnchor: Long = 0L

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    // Monotonic search request id (guarded by stateMutex): a scan publishes its results only
    // if it is still the latest request when it finishes
    private var searchRequestCounter = 0L

    /**
     * The published results and their truncation flag as ONE value: two independent flows
     * could pair capped results with a stale flag mid-combine. When [truncated], [results]
     * are the first [WindowedSearch.MAX_RESULTS] matches of the document - navigation
     * operates within them; matches beyond the cap are reachable by narrowing the query.
     */
    data class SearchState(
        val results: List<SearchResult> = emptyList(),
        val truncated: Boolean = false,
    )

    private val _searchState = MutableStateFlow(SearchState())
    val searchState: StateFlow<SearchState> = _searchState.asStateFlow()

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

    // Sourced from the buffer (not the Loaded snapshot) so post-save rebase refreshes -
    // e.g. re-detected line endings - reach the UI
    val contentSource: Flow<ContentSource> = state.flatMapLatest { s ->
        (s as? EditorState.Loaded)?.resources?.textBuffer?.contentSource
            ?: flowOf(ContentSource.Memory(size = 0L))
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

    /** Size above which a delete/replace is applied non-undoably; null until a file is loaded. */
    val maxUndoableEditChars: Flow<Long?> = state.flatMapLatest { s ->
        flowOf((s as? EditorState.Loaded)?.resources?.textBuffer?.maxUndoableEditChars)
    }

    /** True while an unrecorded (non-undoable) edit is pending a manual save / next recorded edit. */
    val nonUndoableEditPending: Flow<Boolean> = state.flatMapLatest { s ->
        (s as? EditorState.Loaded)?.resources?.textBuffer?.nonUndoableEditPending ?: flowOf(false)
    }

    val textBuffer: DocumentBuffer?
        get() = (state.value as? EditorState.Loaded)?.resources?.textBuffer

    /**
     * Per-line syntax tokens for the visible window, computed off the display path: text never
     * waits for highlighting (see [EditorHighlighter]). Language is fixed per engine lifetime -
     * open/save-as create a new engine via the existing switch machinery.
     */
    val highlighter = EditorHighlighter(
        language = Language.fromFileName(filePath?.name),
        enabled = editorSettings.syntaxHighlighting.flow,
        visibleContent = visibleContent,
        visibleRange = visibleRange,
        // Recomputes on every mutation (edits above the window don't change visibleContent) and
        // on load (Empty -> Loaded swaps in the buffer's flow, so highlighting starts without
        // waiting for a scroll/edit).
        structuralVersion = state.flatMapLatest { s ->
            (s as? EditorState.Loaded)?.resources?.textBuffer?.structuralVersionFlow ?: flowOf(-1L)
        },
        bufferProvider = { textBuffer },
        dispatcherProvider = dispatcherProvider,
    )

    /**
     * Matches inserted text to the document's line ending so editing doesn't turn a uniform
     * file MIXED on save: CRLF documents turn every break ('\n' from the IME diff path, lone
     * '\r' or "\r\n" from foreign clipboards) into "\r\n"; LF documents turn pasted "\r\n"/'\r'
     * into '\n'. Applied at the mutation entry points BEFORE any offset math - the buffer, undo
     * ops, and cursor/replacement-end calculations must all see the same string. CR documents
     * are excluded (a bare '\r' is handled end-to-end, e.g. cursor line math via [endPositionOf]);
     * MIXED has no ending to conform to.
     */
    private fun matchDocumentLineEnding(text: String, buffer: DocumentBuffer): String {
        val target = when (buffer.lineEnding.value) {
            LineEnding.LF -> "\n"
            LineEnding.CRLF -> "\r\n"
            else -> return text
        }
        val needsWork = text.contains('\r') || (target == "\r\n" && text.contains('\n'))
        if (!needsWork) return text
        return text.replace(LINE_BREAK_REGEX, target)
    }

    /**
     * Backstop for read-only/binary sources: the UI disables input, but nothing may bypass it -
     * a mutation on an uneditable document is rejected here before touching the buffer.
     * Deliberately does NOT set [_error]: the read-only state is already visible in the UI and
     * a banner per swallowed keystroke would be noise.
     */
    private fun EditorState.Loaded.editabilityError(): IOException? {
        // Read live: the backing file can vanish mid-session, long after this snapshot's canWrite
        // was captured at open. Refusing edits here keeps the field and buffer from desyncing.
        if (resources.textBuffer.isBackingLost.value) {
            return BackingUnavailableException("Backing file is no longer available")
        }
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
                val contentResult = resources.textBuffer.getDisplayRangeWithVersion(0, endLine)
                contentResult.getOrNull()?.let { (window, version) ->
                    _visibleContent.value = window.toVisibleContent(rangeStart = 0L, version = version)
                }
            } else {
                _visibleRange.value = 0L..0L
                _visibleContent.value = VisibleContent()
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
                currentState.editabilityError()?.let {
                    log(tag, VERBOSE) { "saveFile rejected: ${it.message}" }
                    return@withLock Result.failure(it)
                }
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
                        // The rebase bumped the buffer version: without republishing the window
                        // (and its token) every later field delta would conflict forever
                        refreshVisibleContent()
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
                // Save-As of a read-only/binary file is allowed (it exports the original bytes),
                // but not once the backing file is gone - its original ranges can't be read.
                if (currentState.resources.textBuffer.isBackingLost.value) {
                    throw BackingUnavailableException("Backing file is no longer available")
                }
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
     * Converts the document's line endings to [target] and saves (unsaved edits included).
     * Undo history is cleared by the buffer - char content intentionally changes. Search
     * results and the visible window are refreshed because char offsets shift.
     */
    suspend fun convertLineEndings(target: LineEnding): Result<Unit> = stateMutex.withLock {
        val currentState = _state.value as? EditorState.Loaded
            ?: return@withLock Result.failure(IllegalStateException("Cannot convert - no file open"))
        currentState.editabilityError()?.let { return@withLock Result.failure(it) }
        try {
            _progress.value = Progress.Data(
                primary = R.string.editor_progress_saving.toCaString(),
                count = Progress.Count.Indeterminate(),
            )
            log(tag, INFO) { "Converting line endings to $target" }
            val result = currentState.resources.textBuffer.convertLineEndings(target)
            if (result.isFailure) {
                _error.value = result.exceptionOrNull()
                if (result.exceptionOrNull() is ExternalModificationException) {
                    flagSaveTimeExternalChange(currentState.resources.textBuffer)
                }
            } else {
                _state.value = currentState.copy(isModified = false)
                _externalChange.value = null
                _totalLines.value = currentState.resources.textBuffer.totalLines.value
                // Char offsets shifted document-wide; line/column stay valid (line count and
                // line content are unchanged), so re-derive the cursor's offset and drop the
                // selection - raw offsets feed backspace/forward-delete and search
                val cursor = _cursorPosition.value
                val correctedOffset = currentState.resources.textBuffer.findOffset(cursor.line, cursor.column)
                _cursorPosition.value = cursor.copy(offset = correctedOffset)
                _selectionRange.value = null
                selectionAnchor = null
                invalidateSearchResults()
                refreshVisibleContent()
            }
            result
        } finally {
            _progress.value = null
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

    /**
     * A single contiguous edit computed by the hidden input field against the window identified by
     * [token]: it replaces [start]..[end] (line/column, placeholder offsets resolved here) - which
     * must still hold [oldText] - with [newText], then places the cursor at [caret].
     */
    data class FieldDelta(
        val token: DocumentToken,
        val start: TextPosition,
        val end: TextPosition,
        val oldText: String,
        val newText: String,
        val caret: TextPosition,
    )

    /** Window, cursor and selection captured together; what a field rebuilds itself from. */
    data class WindowSnapshot(
        val content: VisibleContent,
        val cursor: TextPosition,
        val selection: Pair<TextPosition, TextPosition>?,
    )

    /**
     * Outcome of a verified mutation. [Conflict] is an expected synchronization result (the
     * document moved between the snapshot and the edit), NOT an error: it carries the authoritative
     * state to rebuild from and must never raise the error banner.
     */
    sealed interface MutationResult {
        data class Applied(val token: DocumentToken) : MutationResult
        data class Conflict(val snapshot: WindowSnapshot) : MutationResult
        data class Failed(val error: Throwable) : MutationResult
    }

    /** Reads the current window, cursor and selection as one value. */
    suspend fun captureWindowSnapshot(): WindowSnapshot = stateMutex.withLock { captureWindowSnapshotLocked() }

    private suspend fun captureWindowSnapshotLocked(): WindowSnapshot {
        // Re-read rather than trusting the last publication: this is what a conflicted field
        // rebuilds from, so it must describe the document as it is right now.
        refreshVisibleContent()
        return WindowSnapshot(_visibleContent.value, _cursorPosition.value, _selectionRange.value)
    }

    /**
     * Applies a field-originated edit as a verified transaction: the delta's token must still match
     * the document, and the range it claims to replace must still hold exactly its old text.
     *
     * Old-text verification is modulo line-break FORM: the field joins its window lines with '\n'
     * regardless of what the document holds ("\r\n", or a lone '\r' in CR/mixed documents), so the
     * comparison canonicalizes every break on both sides. What is then handed to the buffer is the
     * document's own slice, so its check stays byte-exact and atomic with the version check - an
     * edit interleaving between the read and the mutation fails the version check.
     *
     * This is the path all soft-keyboard input flows through. A divergence (foreign mutation, stale
     * field positions, moved window) returns [MutationResult.Conflict] with a fresh snapshot and
     * mutates nothing - the field rebuilds from it. Rejections never raise the error banner.
     */
    suspend fun applyFieldDelta(delta: FieldDelta): MutationResult = stateMutex.withLock {
        val currentState = _state.value as? EditorState.Loaded
        if (currentState == null) {
            log(tag, WARN) { "Cannot apply field delta - no file open" }
            return@withLock MutationResult.Failed(IllegalStateException("Cannot apply field delta - no file open"))
        }
        currentState.editabilityError()?.let {
            // Banner-less by design: the read-only state is already visible in the UI and a banner
            // per swallowed keystroke would be noise.
            log(tag, VERBOSE) { "applyFieldDelta rejected: ${it.message}" }
            return@withLock MutationResult.Failed(it)
        }
        if (delta.token.engineEpoch != engineEpoch) {
            log(tag, WARN) { "applyFieldDelta: delta belongs to a different document" }
            return@withLock MutationResult.Conflict(captureWindowSnapshotLocked())
        }
        val buffer = currentState.resources.textBuffer
        // Inserted text conforms to the document's ending so editing can't turn a uniform file
        // MIXED; the removed text is verified against the document's own slice below instead.
        val newText = matchDocumentLineEnding(delta.newText, buffer)

        val startOffset: Long
        val endOffset: Long
        try {
            val first = buffer.findOffset(delta.start.line, delta.start.column)
            val second = buffer.findOffset(delta.end.line, delta.end.column)
            startOffset = minOf(first, second)
            endOffset = maxOf(first, second)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log(tag, VERBOSE) { "applyFieldDelta: position resolve failed - ${e.message}" }
            return@withLock MutationResult.Conflict(captureWindowSnapshotLocked())
        }
        // findOffset CLAMPS an out-of-range column, so a stale field position would otherwise edit
        // silently at the line end. The document's slice must be the text the field claims to
        // replace (breaks canonicalized); anything else means the field and the document disagree.
        val documentSlice = buffer.getText(startOffset, endOffset).getOrElse { e ->
            log(tag, VERBOSE) { "applyFieldDelta: reading the replaced range failed - ${e.message}" }
            return@withLock MutationResult.Conflict(captureWindowSnapshotLocked())
        }
        if (documentSlice.canonicalBreaks() != delta.oldText.canonicalBreaks()) {
            log(tag, WARN) { "applyFieldDelta: the replaced range diverged from the field" }
            return@withLock MutationResult.Conflict(captureWindowSnapshotLocked())
        }
        if (startOffset == endOffset && newText.isEmpty()) {
            // Pure no-op: nothing to edit and no cursor/selection state to disturb.
            return@withLock MutationResult.Applied(delta.token)
        }

        // Only keystroke-SIZED edits coalesce (<= 2 UTF-16 units covers surrogate-pair input):
        // platform paste and IME batch commits arrive through this same diff path as large pure
        // inserts and must neither join a typing run nor anchor one
        val keystrokeSized = newText.length <= 2 && (endOffset - startOffset) <= 2

        log(tag, VERBOSE) { "applyFieldDelta $startOffset..$endOffset -> ${newText.take(50)} (caret=${delta.caret})" }
        val outcome = buffer.applyMutation(
            expectedVersion = delta.token.structuralVersion,
            patches = listOf(DocumentBuffer.VerifiedPatch(startOffset, endOffset, documentSlice, newText)),
            undoPolicy = if (keystrokeSized) DocumentBuffer.UndoPolicy.COALESCE else DocumentBuffer.UndoPolicy.SEPARATE,
        ).getOrElse { e ->
            if (e is StaleMatchException) {
                log(tag) { "applyFieldDelta conflicted, the document moved on" }
                return@withLock MutationResult.Conflict(captureWindowSnapshotLocked())
            }
            log(tag, ERROR) { "Failed to apply field delta - ${e.asLog()}" }
            _error.value = e
            return@withLock MutationResult.Failed(e)
        }

        _totalLines.value = buffer.totalLines.value
        updateCursorFromCaret(buffer, delta.caret)
        _selectionRange.value = null
        selectionAnchor = null
        _state.value = currentState.copy(isModified = true)
        invalidateSearchResults()
        refreshVisibleContent()
        // The post-commit version read INSIDE the buffer lock: a foreign mutation landing between
        // the commit and this refresh surfaces as a Conflict on the NEXT delta instead of silently
        // being adopted.
        MutationResult.Applied(DocumentToken(engineEpoch, outcome.newVersion))
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
     * An immutable, fully resolved replacement that is too large to apply undoably. Stashed while
     * the confirmation dialog is up and submitted via [submitPrepared]; its [token] is re-verified
     * there, so a document that moved in the meantime is never mutated.
     */
    data class PreparedMutation(
        val token: DocumentToken,
        val startOffset: Long,
        val endOffset: Long,
        val replacement: String,
    )

    /**
     * What a non-field edit MEANS, as data. The engine resolves it against the live cursor and
     * selection inside its own lock, so the intent stays valid however long it waited in the edit
     * queue - unlike the old text-and-count APIs, which described a target the caller had measured
     * earlier and could no longer vouch for.
     */
    sealed interface EditIntent {
        data class InsertAtCursor(val text: String) : EditIntent
        data object DeleteSelection : EditIntent
        data object DeleteForward : EditIntent
    }

    /** Outcome of a mutation that can be gated behind the oversized-edit confirmation. */
    sealed interface EditOutcome {
        /** The mutation ran (or was a legitimate no-op); [removedText] is empty when not materialized. */
        data class Applied(val removedText: String = "") : EditOutcome

        /** Too large to apply undoably: nothing was mutated, the user has to confirm [prepared]. */
        data class RequiresConfirmation(val prepared: PreparedMutation) : EditOutcome

        data class Failed(val error: Throwable) : EditOutcome
    }

    /**
     * Applies a [prepared] oversized replacement the user confirmed. The token is re-checked inside
     * the buffer lock: a document that moved on yields [MutationResult.Conflict] and mutates
     * nothing.
     */
    suspend fun submitPrepared(prepared: PreparedMutation): MutationResult = stateMutex.withLock {
        val currentState = _state.value as? EditorState.Loaded
            ?: return@withLock MutationResult.Failed(IllegalStateException("Cannot apply edit - no file open"))
        currentState.editabilityError()?.let { return@withLock MutationResult.Failed(it) }
        if (prepared.token.engineEpoch != engineEpoch) {
            log(tag, WARN) { "submitPrepared: prepared edit belongs to a different document" }
            return@withLock MutationResult.Conflict(captureWindowSnapshotLocked())
        }
        val buffer = currentState.resources.textBuffer
        buffer.applyOversizedReplace(
            expectedVersion = prepared.token.structuralVersion,
            startOffset = prepared.startOffset,
            endOffset = prepared.endOffset,
            newText = prepared.replacement,
        ).fold(
            onSuccess = { outcome ->
                _selectionRange.value = null
                selectionAnchor = null
                _cursorPosition.value = buffer.findPosition(prepared.startOffset + prepared.replacement.length)
                _state.value = currentState.copy(isModified = true)
                _totalLines.value = buffer.totalLines.value
                invalidateSearchResults()
                refreshVisibleContent()
                MutationResult.Applied(DocumentToken(engineEpoch, outcome.newVersion))
            },
            onFailure = { e ->
                if (e is StaleMatchException) {
                    log(tag, WARN) { "Confirmed edit rejected, the document moved on" }
                    MutationResult.Conflict(captureWindowSnapshotLocked())
                } else {
                    log(tag, ERROR) { "Failed to apply confirmed edit - ${e.asLog()}" }
                    _error.value = e
                    MutationResult.Failed(e)
                }
            },
        )
    }

    /** [setSelection] stores the received order; buffer ranges and cursor placement need start <= end. */
    private fun Pair<TextPosition, TextPosition>.normalized(): Pair<TextPosition, TextPosition> =
        if (first.offset <= second.offset) this else second to first

    /**
     * Applies an [intent] whose target this engine resolves ITSELF, under [stateMutex] and against
     * the live cursor/selection - the request carries no coordinates that could have gone stale
     * while it waited in the edit queue. [expectedEpoch] pins it to ONE document: an edit enqueued
     * before a file switch is dropped instead of landing in whatever is open now.
     *
     * Rejections are deliberately banner-less: a stale epoch is the normal consequence of switching
     * files, and an uneditable document is already visible as such in the UI.
     */
    suspend fun performEdit(intent: EditIntent, expectedEpoch: Uuid): EditOutcome = stateMutex.withLock {
        val currentState = _state.value as? EditorState.Loaded
        if (currentState == null) {
            val error = IllegalStateException("Cannot edit - no file open")
            log(tag, WARN) { error.message ?: "Unknown error" }
            return@withLock EditOutcome.Failed(error)
        }
        if (expectedEpoch != engineEpoch) {
            log(tag, INFO) { "performEdit dropped, $intent belongs to a different document" }
            return@withLock EditOutcome.Failed(StaleMatchException())
        }
        currentState.editabilityError()?.let {
            log(tag, VERBOSE) { "performEdit rejected: ${it.message}" }
            return@withLock EditOutcome.Failed(it)
        }
        try {
            performEditLocked(currentState, intent)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log(tag, ERROR) { "Failed to perform $intent - ${e.asLog()}" }
            _error.value = e
            EditOutcome.Failed(e)
        }
    }

    /**
     * Resolve, gate, apply. Resolution and application are re-run on a version conflict: search
     * replacements commit OUTSIDE [stateMutex], so one can land between the version read and the
     * splice - retrying resolves against the fresh document instead of dropping the user's edit.
     */
    private suspend fun performEditLocked(currentState: EditorState.Loaded, intent: EditIntent): EditOutcome {
        val buffer = currentState.resources.textBuffer
        repeat(MAX_EDIT_ATTEMPTS) {
            val selection = _selectionRange.value?.normalized()
            val startOffset: Long
            val endOffset: Long
            if (selection != null) {
                // Every intent consumes the whole selection first - standard editor behavior
                startOffset = selection.first.offset
                endOffset = selection.second.offset
            } else when (intent) {
                is EditIntent.InsertAtCursor -> {
                    // Re-resolved from line/column: the field sends a placeholder offset with
                    // virtual scrolling, so the raw cursor offset is not trustworthy here
                    val cursor = _cursorPosition.value
                    startOffset = buffer.findOffset(cursor.line, cursor.column)
                    endOffset = startOffset
                }
                EditIntent.DeleteForward -> {
                    val cursor = _cursorPosition.value
                    if (cursor.offset >= buffer.totalLength.value) {
                        // Accepted, just with nothing to remove - it still ends a shift-selection
                        selectionAnchor = null
                        return EditOutcome.Applied()
                    }
                    startOffset = cursor.offset
                    endOffset = cursor.offset + 1
                }
                EditIntent.DeleteSelection ->
                    return EditOutcome.Failed(IllegalStateException("No selection to delete"))
            }
            val replacement = when (intent) {
                is EditIntent.InsertAtCursor -> matchDocumentLineEnding(intent.text, buffer)
                else -> ""
            }
            val version = buffer.getStructuralVersion()

            if (endOffset - startOffset > buffer.maxUndoableEditChars) {
                // Applying this would clear undo history (materializing the removed span for undo
                // would OOM), so it is resolved into an immutable request the user confirms - and
                // nothing mutates until then. Only selection-consuming edits can get this large.
                log(tag, INFO) { "Edit over ${endOffset - startOffset} chars needs confirmation (not undoable)" }
                return EditOutcome.RequiresConfirmation(
                    PreparedMutation(
                        token = DocumentToken(engineEpoch, version),
                        startOffset = startOffset,
                        endOffset = endOffset,
                        replacement = replacement,
                    ),
                )
            }

            val removedText = buffer.applyVersionedReplace(version, startOffset, endOffset, replacement).fold(
                onSuccess = { (_, removed) -> removed },
                onFailure = { e ->
                    if (e !is StaleMatchException) throw e
                    log(tag) { "performEdit conflicted with a concurrent mutation, resolving again" }
                    return@repeat
                },
            )

            _totalLines.value = buffer.totalLines.value
            _cursorPosition.value = when {
                intent is EditIntent.InsertAtCursor -> buffer.findPosition(startOffset + replacement.length)
                // A consumed selection collapses to its start; a plain forward-delete stays put
                selection != null -> selection.first
                else -> _cursorPosition.value
            }
            if (selection != null) _selectionRange.value = null
            // Dropped for EVERY accepted intent, not just the ones consuming a selection: the anchor
            // belongs to the shift-selection the user was building, and an edit ends it. A surviving
            // anchor would make the next Shift+Arrow extend from wherever that selection started.
            selectionAnchor = null
            _state.value = currentState.copy(isModified = true)
            invalidateSearchResults()
            // Always a full re-read: an in-place window patch would publish new text under the
            // PREVIOUS token, and every field delta mapped against it would be rejected as stale.
            refreshVisibleContent()
            return EditOutcome.Applied(if (intent is EditIntent.InsertAtCursor) "" else removedText)
        }
        log(tag, WARN) { "Gave up on $intent after $MAX_EDIT_ATTEMPTS attempts, the document kept moving" }
        return EditOutcome.Failed(StaleMatchException())
    }

    /**
     * A selection captured for a cut: the copied [text], the offset it starts at, and the document
     * identity it was read from. [applyCut] re-verifies both halves of the [token], so the deletion
     * can only ever remove the range this snapshot was taken from, in the document it came from.
     */
    data class CutSnapshot(
        val text: String,
        val startOffset: Long,
        val token: DocumentToken,
    )

    /**
     * Extracts the selection, refusing BEFORE materialization when it exceeds [maxChars] (UTF-16
     * units). The check shares [stateMutex] with the read, so it can never race a selection
     * change. Callers pick char caps that numerically approximate their clipboard's byte
     * capacity - the refusal's [ClipboardCapacityException.limitBytes] is displayed as a size.
     */
    suspend fun copySelection(maxChars: Long? = null): Result<String> = prepareCut(maxChars).map { it.text }

    /**
     * [copySelection] plus the range and document version the text came from, all captured under
     * one [stateMutex] hold. Cut needs that identity: its deletion runs later (behind the ordered
     * edit queue), by which time the selection may have moved somewhere else entirely.
     */
    suspend fun prepareCut(maxChars: Long? = null): Result<CutSnapshot> = stateMutex.withLock {
        return when (val currentState = _state.value) {
            is EditorState.Loaded -> {
                val selection = _selectionRange.value ?: return Result.failure(
                    IllegalStateException("No selection to copy")
                )

                try {
                    // setSelection can store a reversed range; normalize before measuring/reading
                    val start = minOf(selection.first.offset, selection.second.offset)
                    val end = maxOf(selection.first.offset, selection.second.offset)
                    if (maxChars != null && end - start > maxChars) {
                        log(tag, WARN) { "Selection too large to copy: ${end - start} chars (cap $maxChars)" }
                        return Result.failure(ClipboardCapacityException(limitBytes = maxChars))
                    }
                    log(tag) { "Copying selection: ${selection.first} to ${selection.second}" }
                    val buffer = currentState.resources.textBuffer
                    // Text and version in ONE buffer hold: read separately, a replace-all landing
                    // between them would stamp the new text with the old version, and the cut's
                    // deletion would then be rejected forever
                    buffer.getTextWithVersion(start, end).map { (text, version) ->
                        CutSnapshot(text, start, DocumentToken(engineEpoch, version))
                    }
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

    /**
     * Deletes exactly the range [snapshot] was copied from, as a verified patch: both the document
     * version and the text at the offset are re-checked, and a divergence fails with
     * [StaleMatchException] without mutating anything. That conflict is returned only - it must not
     * raise the error banner, because a cut whose document moved legitimately deletes nothing and
     * its clipboard write already succeeded. Any OTHER failure (e.g. the backing file became
     * unreadable) does raise the banner: the clipboard already changed, so a silent no-delete would
     * leave the user with no sign that the cut half-executed.
     *
     * The epoch is checked FIRST, before the document guards: a cut prepared on another engine must
     * mutate nothing whatever this engine is currently doing (loading, empty, read-only) - structural
     * versions restart per buffer, so its range could otherwise match here by coincidence.
     */
    suspend fun applyCut(snapshot: CutSnapshot): Result<String> {
        if (snapshot.token.engineEpoch != engineEpoch) {
            log(tag, WARN) { "Cut deletion rejected, it belongs to a different document" }
            return Result.failure(StaleMatchException())
        }
        val buffer = stateMutex.withLock {
            val loaded = _state.value as? EditorState.Loaded
                ?: return Result.failure(IllegalStateException("Cannot delete selection - no file open"))
            loaded.editabilityError()?.let { return Result.failure(it) }
            loaded.resources.textBuffer
        }

        buffer.replaceMatches(
            listOf(DocumentBuffer.MatchReplacement(snapshot.startOffset, snapshot.text, "")),
            expectedVersion = snapshot.token.structuralVersion,
        ).getOrElse {
            when (it) {
                is CancellationException -> throw it
                is StaleMatchException -> log(tag, WARN) {
                    "Cut deletion rejected, the document moved on: ${it.asLog()}"
                }
                else -> {
                    stateMutex.withLock { _error.value = it }
                    log(tag, ERROR) { "Cut deletion failed - ${it.asLog()}" }
                }
            }
            return Result.failure(it)
        }

        refreshAfterMutation(cursorOffset = snapshot.startOffset)
        return Result.success(snapshot.text)
    }

    suspend fun selectAll(): Result<Pair<TextPosition, TextPosition>> = stateMutex.withLock {
        return when (val currentState = _state.value) {
            is EditorState.Loaded -> {
                try {
                    val startPosition = TextPosition(offset = 0, line = 0, column = 0)

                    val totalLength = currentState.resources.textBuffer.totalLength.value
                    val totalLines = _totalLines.value

                    // Length-only read: materializing the whole last line just for its length
                    // would allocate the full 100MB for a single-giant-line file
                    val lastLineNumber = (totalLines - 1).coerceAtLeast(0)
                    val lastLineLength = currentState.resources.textBuffer.getLineLength(lastLineNumber)
                        .getOrNull() ?: 0L

                    val endPosition = TextPosition(
                        offset = totalLength,
                        line = lastLineNumber,
                        // Saturated at the UI edge; the OFFSET stays exact for buffer ranges
                        column = lastLineLength.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
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
            is EditorState.Loaded -> {
                val buffer = currentState.resources.textBuffer
                try {
                    // No display fence: the caret may sit anywhere on the real line; the window follows
                    // it. findOffset can still throw on a stale line/column or an unreadable backing
                    // file - keep the current cursor rather than crash a tap that can't be resolved.
                    TextPosition(
                        offset = buffer.findOffset(position.line, position.column),
                        line = position.line,
                        column = position.column,
                    )
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    log(tag, VERBOSE) { "setCursorPosition: resolve failed, ignoring - ${e.message}" }
                    return@withLock
                }
            }
            else -> position
        }
        _cursorPosition.value = correctedPosition
        _selectionRange.value = null
        refreshVisibleContent()
    }

    suspend fun setSelection(start: TextPosition, end: TextPosition) = stateMutex.withLock {
        textBuffer?.breakUndoRun()
        when (val currentState = _state.value) {
            is EditorState.Loaded -> {
                val buffer = currentState.resources.textBuffer
                // Recalculate actual offsets from line/column positions (UI may send placeholder
                // offset=0 with virtual scrolling). A stale position or unreadable backing must
                // not crash selection - leave the current selection/cursor untouched.
                val corrected = try {
                    TextPosition(
                        offset = buffer.findOffset(start.line, start.column),
                        line = start.line,
                        column = start.column,
                    ) to TextPosition(
                        offset = buffer.findOffset(end.line, end.column),
                        line = end.line,
                        column = end.column,
                    )
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    log(tag, VERBOSE) { "setSelection: resolve failed, ignoring - ${e.message}" }
                    return@withLock
                }
                _selectionRange.value = corrected.first to corrected.second
                _cursorPosition.value = corrected.second
            }
            else -> {
                // No file loaded, store as-is
                _selectionRange.value = start to end
                _cursorPosition.value = end
            }
        }
        refreshVisibleContent()
    }

    suspend fun moveCursor(direction: CursorDirection, extendSelection: Boolean): Unit = stateMutex.withLock {
        log(tag) { "moveCursor(direction=$direction, extendSelection=$extendSelection)" }
        val currentState = _state.value as? EditorState.Loaded
        if (currentState == null) {
            log(tag, WARN) { "moveCursor: No file loaded, ignoring" }
            return
        }
        val buffer = currentState.resources.textBuffer
        val rawPos = _cursorPosition.value
        // No display fence: the caret can start anywhere on the real line; the window follows it.
        // findOffset and the line reads inside the move helpers can still throw when the position is
        // stale or the backing file is unreadable - a movement that can't be resolved is dropped.
        val currentPos = rawPos
        val newPos: TextPosition
        try {
            newPos = when (direction) {
                CursorDirection.LEFT -> moveCursorLeft(currentPos, currentState)
                CursorDirection.RIGHT -> moveCursorRight(currentPos, currentState)
                CursorDirection.UP -> moveCursorUp(currentPos, currentState)
                CursorDirection.DOWN -> moveCursorDown(currentPos, currentState)
                CursorDirection.WORD_LEFT -> moveCursorWordLeft(currentPos, currentState)
                CursorDirection.WORD_RIGHT -> moveCursorWordRight(currentPos, currentState)
                CursorDirection.LINE_START -> moveCursorToLineStart(currentPos, currentState)
                CursorDirection.LINE_END -> moveCursorToLineEnd(currentPos, currentState)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log(tag, VERBOSE) { "moveCursor: resolve failed, ignoring - ${e.message}" }
            return@withLock
        }
        buffer.breakUndoRun()

        // Set anchor if starting selection
        if (extendSelection && selectionAnchor == null) {
            selectionAnchor = currentPos
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

        // Slide the horizontal window to follow the caret (reveals content past the display cap).
        refreshVisibleContent()
    }

    private suspend fun moveCursorLeft(pos: TextPosition, state: EditorState.Loaded): TextPosition {
        return if (pos.column > 0) {
            // Move left within line
            val newOffset = state.resources.textBuffer.findOffset(pos.line, pos.column - 1)
            TextPosition(offset = newOffset, line = pos.line, column = pos.column - 1)
        } else if (pos.line > 0) {
            // Move to end of previous line
            val prevLineLength = getDisplayLineLength(pos.line - 1, state)
            val newOffset = state.resources.textBuffer.findOffset(pos.line - 1, prevLineLength)
            TextPosition(offset = newOffset, line = pos.line - 1, column = prevLineLength)
        } else {
            // Already at start of document
            pos
        }
    }

    private suspend fun moveCursorRight(pos: TextPosition, state: EditorState.Loaded): TextPosition {
        val lineLength = getDisplayLineLength(pos.line, state)
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
            val prevLineLength = getDisplayLineLength(newLine, state)
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
            val nextLineLength = getDisplayLineLength(newLine, state)
            val newColumn = minOf(pos.column, nextLineLength)
            val newOffset = state.resources.textBuffer.findOffset(newLine, newColumn)
            TextPosition(offset = newOffset, line = newLine, column = newColumn)
        } else {
            // Already on last line
            pos
        }
    }

    private suspend fun moveCursorWordLeft(pos: TextPosition, state: EditorState.Loaded): TextPosition {
        var line = pos.line
        var slice = getDisplayLineWindow(line, state)
        var lineContent = slice?.text ?: ""
        var startCol = (slice?.startColumn ?: 0L).toInt()
        var column = (pos.column - startCol).coerceAtLeast(0) // local index into the window

        // Skip whitespace backwards
        while (column > 0 && lineContent.getOrNull(column - 1)?.isWhitespace() == true) column--

        if (column == 0 && startCol > 0) {
            // Window's left edge with content hidden before it: stop here; the next keystroke slides
            // the window left (ensureColumnVisible) and word-nav continues into the revealed text.
            val col = startCol
            return TextPosition(state.resources.textBuffer.findOffset(line, col), line, col)
        }

        if (column == 0 && line > 0) {
            // Real start of the line: move to the end of the previous line's window.
            line--
            slice = getDisplayLineWindow(line, state)
            lineContent = slice?.text ?: ""
            startCol = (slice?.startColumn ?: 0L).toInt()
            column = lineContent.length
            while (column > 0 && lineContent.getOrNull(column - 1)?.isWhitespace() == true) column--
            while (column > 0 && lineContent.getOrNull(column - 1)?.isWordChar() == true) column--
        } else {
            while (column > 0 && lineContent.getOrNull(column - 1)?.isWordChar() == true) column--
        }

        val col = startCol + column
        return TextPosition(state.resources.textBuffer.findOffset(line, col), line, col)
    }

    private suspend fun moveCursorWordRight(pos: TextPosition, state: EditorState.Loaded): TextPosition {
        var line = pos.line
        var slice = getDisplayLineWindow(line, state)
        var lineContent = slice?.text ?: ""
        var startCol = (slice?.startColumn ?: 0L).toInt()
        val hiddenAfter = (slice?.hiddenChars ?: 0L) > 0L
        var column = (pos.column - startCol).coerceIn(0, lineContent.length) // local index into the window
        val totalLines = _totalLines.value

        // Skip word chars forwards
        while (column < lineContent.length && lineContent.getOrNull(column)?.isWordChar() == true) column++
        // Skip whitespace forwards
        while (column < lineContent.length && lineContent.getOrNull(column)?.isWhitespace() == true) column++

        if (column >= lineContent.length && hiddenAfter) {
            // Window's right edge with content hidden after it: stop here; the next keystroke slides
            // the window right and word-nav continues into the revealed text.
            val col = startCol + lineContent.length
            return TextPosition(state.resources.textBuffer.findOffset(line, col), line, col)
        }

        if (column >= lineContent.length && line < totalLines - 1) {
            // Real end of the line: move to the start of the next line's window.
            line++
            slice = getDisplayLineWindow(line, state)
            lineContent = slice?.text ?: ""
            startCol = (slice?.startColumn ?: 0L).toInt()
            column = 0
            while (column < lineContent.length && lineContent.getOrNull(column)?.isWhitespace() == true) column++
        }

        val col = startCol + column
        return TextPosition(state.resources.textBuffer.findOffset(line, col), line, col)
    }

    private suspend fun moveCursorToLineStart(pos: TextPosition, state: EditorState.Loaded): TextPosition {
        val newOffset = state.resources.textBuffer.findOffset(pos.line, 0)
        return TextPosition(offset = newOffset, line = pos.line, column = 0)
    }

    private suspend fun moveCursorToLineEnd(pos: TextPosition, state: EditorState.Loaded): TextPosition {
        val lineLength = getDisplayLineLength(pos.line, state)
        val newOffset = state.resources.textBuffer.findOffset(pos.line, lineLength)
        return TextPosition(offset = newOffset, line = pos.line, column = lineLength)
    }

    /**
     * Real line length (content chars, breaks excluded). Navigation (End, RIGHT, up/down column
     * clamping) now reaches the true line end because the display window follows the caret. Saturated
     * to Int for column math; a line longer than Int.MAX_VALUE chars (>4 GB) is not addressable.
     */
    private suspend fun getDisplayLineLength(lineNumber: Long, state: EditorState.Loaded): Int {
        val length = state.resources.textBuffer.getLineLength(lineNumber).getOrDefault(0L)
        return length.coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()
    }

    /** The line's display window at the current [viewportColumnAnchor]; word-nav scans it locally. */
    private suspend fun getDisplayLineWindow(lineNumber: Long, state: EditorState.Loaded): DocumentBuffer.LineSlice? {
        return state.resources.textBuffer.getLineSlice(lineNumber, viewportColumnAnchor).getOrNull()
    }

    private fun Char.isWordChar(): Boolean {
        return this.isLetterOrDigit() || this == '_'
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
                _searchState.value = SearchState()
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
                onSuccess = { outcome ->
                    if (isLatest) _searchState.value = SearchState(outcome.results, outcome.truncated)
                },
                onFailure = { e ->
                    // Never leave stale positions highlighted under the failed (latest) query
                    if (isLatest) _searchState.value = SearchState()
                    if (e !is SearchInvalidatedException) {
                        log(tag, ERROR) { "Failed to search - ${e.asLog()}" }
                        _error.value = e
                    }
                },
            )
        }
        return result.map { it.results }
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

        val expandedVersion: Long?
        val newText: String
        if (options.useRegex) {
            val (expanded, snapshotVersion) = expandRegexReplacementAt(buffer, query, options, match, replacement)
                .getOrElse { return Result.failure(it) }
            expandedVersion = snapshotVersion
            newText = matchDocumentLineEnding(expanded, buffer)
        } else {
            expandedVersion = null
            newText = matchDocumentLineEnding(replacement, buffer)
        }

        val replacementEnd = match.position.offset + newText.length
        // Commit and state update under ONE hold: see [replaceAll]
        stateMutex.withLock {
            buffer.replaceMatches(
                listOf(DocumentBuffer.MatchReplacement(match.position.offset, match.matchText, newText)),
                expectedVersion = expandedVersion,
            ).getOrElse { return Result.failure(it) }
            refreshAfterMutationLocked(cursorOffset = replacementEnd)
        }

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
     *
     * Documents with more matches than [WindowedSearch.MAX_RESULTS] (or match/replacement text
     * beyond [WindowedSearch.MAX_TOTAL_MATCH_CHARS]) are REFUSED with [TooManyMatchesException]
     * before anything mutates - a partial replace would silently corrupt user expectations and
     * materializing millions of replacements would exhaust the heap.
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

        var expectedVersion: Long? = null
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
            expectedVersion = snapshotVersion
            try {
                // Manual iteration so the caps refuse BEFORE further replacements materialize -
                // a dense pattern over a large document must not build millions of objects
                // first, and refused matches never get their text or expansion allocated.
                // Mirrors the search-side bounds: the first replacement is exempt from the
                // char bound so a single oversized match stays replaceable.
                val collected = mutableListOf<DocumentBuffer.MatchReplacement>()
                var accumulatedChars = 0L
                for (m in regex.findAll(text)) {
                    if (m.range.isEmpty()) continue
                    if (collected.size >= WindowedSearch.MAX_RESULTS) {
                        return Result.failure(TooManyMatchesException(WindowedSearch.MAX_RESULTS))
                    }
                    val oldLength = m.range.last - m.range.first + 1
                    if (collected.isNotEmpty() &&
                        accumulatedChars + oldLength > WindowedSearch.MAX_TOTAL_MATCH_CHARS
                    ) {
                        return Result.failure(TooManyMatchesException(WindowedSearch.MAX_RESULTS))
                    }
                    val newText = matchDocumentLineEnding(expandReplacementTemplate(replacement, m), buffer)
                    if (collected.isNotEmpty() &&
                        accumulatedChars + oldLength + newText.length > WindowedSearch.MAX_TOTAL_MATCH_CHARS
                    ) {
                        return Result.failure(TooManyMatchesException(WindowedSearch.MAX_RESULTS))
                    }
                    accumulatedChars += oldLength + newText.length
                    collected += DocumentBuffer.MatchReplacement(
                        startOffset = m.range.first.toLong(),
                        oldText = m.value,
                        newText = newText,
                    )
                }
                collected
            } catch (e: IllegalArgumentException) {
                // Bad group reference: precomputation failed, the document is untouched
                return Result.failure(e)
            }
        } else {
            val translated = matchDocumentLineEnding(replacement, buffer)
            val outcome = buffer.search(query, options).getOrElse { return Result.failure(it) }
            if (outcome.truncated) {
                return Result.failure(TooManyMatchesException(WindowedSearch.MAX_RESULTS))
            }
            outcome.results.map { DocumentBuffer.MatchReplacement(it.position.offset, it.matchText, translated) }
        }

        if (replacements.isEmpty()) return Result.success(ReplaceAllOutcome(0, undoable = true))

        // The COMMIT and the state update it implies (cleared selection, moved cursor, refreshed
        // window) are one transaction under [stateMutex]. Committing outside it and updating state
        // after would let the mutation land while an edit holds the lock: that edit's target
        // offsets were resolved against the pre-replace document, and the selection it re-reads on
        // a version conflict is exactly the one this update has not been able to clear yet - so a
        // length-shifting replacement would make it edit unrelated text. The scan/precompute above
        // deliberately stays OUTSIDE the lock so typing never queues behind a whole-document pass.
        val stats = stateMutex.withLock {
            buffer.replaceMatches(replacements, expectedVersion = expectedVersion)
                .getOrElse { return Result.failure(it) }
                .also { refreshAfterMutationLocked(cursorOffset = null) }
        }
        search(query, options)

        return Result.success(ReplaceAllOutcome(stats.count, stats.undoable))
    }

    /** Post-mutation UI refresh; takes [stateMutex] itself. Callers already holding it use the Locked variant. */
    private suspend fun refreshAfterMutation(cursorOffset: Long?) {
        stateMutex.withLock { refreshAfterMutationLocked(cursorOffset) }
    }

    /** Body of [refreshAfterMutation]; [stateMutex] must be held (convention like [refreshVisibleContent]). */
    private suspend fun refreshAfterMutationLocked(cursorOffset: Long?) {
        val currentState = _state.value as? EditorState.Loaded ?: return
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

    /**
     * Resolves the regex match at the SearchResult's offset so group references can expand.
     * Only valid under the full-scan cap - the same boundary the search side documents.
     * Returns the expanded text plus the structural version of the snapshot it was computed
     * from; callers pass that version to replaceMatches so any intervening mutation aborts.
     */
    private suspend fun expandRegexReplacementAt(
        buffer: DocumentBuffer,
        query: String,
        options: SearchOptions,
        match: SearchResult,
        replacement: String,
    ): Result<Pair<String, Long>> {
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
        val liveMatch = regex.findAll(text).firstOrNull { it.range.first.toLong() == match.position.offset }
            ?: return Result.failure(StaleMatchException())
        return try {
            Result.success(expandReplacementTemplate(replacement, liveMatch) to snapshotVersion)
        } catch (e: IllegalArgumentException) {
            Result.failure(e)
        }
    }

    suspend fun goToLine(lineNumber: Long): Result<Unit> = stateMutex.withLock {
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
            updateVisibleRangeLocked(visibleStart, visibleEnd)
            // Settle the horizontal window on the target line (jumped to column 0 -> show the start).
            refreshVisibleContent()

            Result.success(Unit)
        } catch (e: Exception) {
            log(tag, ERROR) { "Failed to go to line: $lineNumber - ${e.asLog()}" }
            _error.value = e
            Result.failure(e)
        }
    }

    private fun invalidateSearchResults() {
        _searchState.value = SearchState()
        _searchQuery.value = ""
    }

    /**
     * Slides [viewportColumnAnchor] so [column] on [line] stays inside the display window. Cursor-driven:
     * a line that fits within the cap keeps the current anchor (its slice clamps to 0 anyway), so moving
     * onto a short line never disturbs a horizontal scroll position. Pure - only updates the field; the
     * caller re-fetches. A hysteresis margin avoids re-slicing on every keystroke near the window centre.
     */
    private suspend fun ensureColumnVisible(line: Long, column: Int, buffer: DocumentBuffer) {
        val cap = buffer.maxDisplayLineChars.toLong()
        val lineLength = buffer.getLineLength(line).getOrDefault(0L)
        if (lineLength <= cap) return
        val maxAnchor = lineLength - cap
        val margin = cap / 4
        val col = column.toLong()
        val current = viewportColumnAnchor
        viewportColumnAnchor = when {
            col < current + margin -> col - margin
            col > current + cap - margin -> col - cap + margin
            else -> current
        }.coerceIn(0L, maxAnchor)
    }

    /** Per-line column anchors for a display range: the shared [viewportColumnAnchor] for every line, or
     * empty when unscrolled. [DocumentBuffer.getLineSliceInternal] clamps it per line, so short lines stay at 0. */
    private fun columnAnchorsFor(range: LongRange): Map<Long, Long> {
        val anchor = viewportColumnAnchor
        if (anchor <= 0L) return emptyMap()
        return range.associateWith { anchor }
    }

    /**
     * Scroll-driven horizontal reveal: pan the shared window by half a cap in [forward] direction so the
     * user can browse a long line past the display cap WITHOUT moving the caret. Bounded by the longest
     * visible line's furthest full-window start; a no-op at the bound. Does NOT re-centre on the caret
     * (that would fight the scroll) - [refreshVisibleContent] is called with ensureCursor=false.
     */
    suspend fun revealMoreColumns(forward: Boolean): Unit = stateMutex.withLock {
        val currentState = _state.value as? EditorState.Loaded ?: return
        val buffer = currentState.resources.textBuffer
        val cap = buffer.maxDisplayLineChars.toLong()
        val page = (cap / 2).coerceAtLeast(1L)
        var maxAnchor = 0L
        for (line in _visibleRange.value) {
            val len = buffer.getLineLength(line).getOrDefault(0L)
            val lineMax = (len - cap).coerceAtLeast(0L)
            if (lineMax > maxAnchor) maxAnchor = lineMax
        }
        // Normalize a stale anchor first: a prior reveal may have left it past what the CURRENT visible
        // lines can start at (e.g. after vertically scrolling from a very long line to shorter ones),
        // and those lines already render clamped - so page from the clamped value, not the raw one.
        val current = viewportColumnAnchor.coerceIn(0L, maxAnchor)
        val newAnchor = (current + if (forward) page else -page).coerceIn(0L, maxAnchor)
        if (newAnchor == viewportColumnAnchor) return
        viewportColumnAnchor = newAnchor
        refreshVisibleContent(ensureCursor = false)
    }

    private suspend fun refreshVisibleContent(ensureCursor: Boolean = true) {
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

        // Slide the horizontal window so the caret stays inside the loaded slice on long lines - unless a
        // scroll-driven reveal is positioning the anchor itself (re-centring would undo the user's scroll).
        if (ensureCursor) {
            ensureColumnVisible(cursorLine, _cursorPosition.value.column, currentState.resources.textBuffer)
        }

        try {
            currentCoroutineContext().ensureActive()
            val contentResult = currentState.resources.textBuffer
                .getDisplayRangeWithVersion(range.first, range.last, columnAnchorsFor(range))
            contentResult.fold(
                onSuccess = { (window, version) ->
                    _visibleContent.value = window.toVisibleContent(range.first, version)
                    log(tag) { "Refreshed visible content for range: ${range.first}..${range.last}" }
                },
                onFailure = { e ->
                    log(tag, WARN) { "Failed to refresh content: ${e.asLog()}" }
                },
            )
        } catch (e: Exception) {
            log(tag, ERROR) { "Error refreshing visible content - ${e.asLog()}" }
        }
    }

    /** Pairs a freshly read window with the line it starts at and the version it was read at (D1). */
    private fun DocumentBuffer.DisplayWindow.toVisibleContent(rangeStart: Long, version: Long) = VisibleContent(
        text = text,
        truncatedLines = truncatedLines,
        startColumns = startColumns,
        rangeStart = rangeStart,
        token = DocumentToken(engineEpoch, version),
    )

    suspend fun updateVisibleRange(startLine: Long, endLine: Long) = stateMutex.withLock {
        updateVisibleRangeLocked(startLine, endLine)
    }

    /** Body of [updateVisibleRange]; [stateMutex] must be held (convention like [refreshVisibleContent]). */
    private suspend fun updateVisibleRangeLocked(startLine: Long, endLine: Long) {
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

            // Load content for the new visible range (keep the current horizontal anchor).
            try {
                val contentResult = currentState.resources.textBuffer
                    .getDisplayRangeWithVersion(constrainedStart, constrainedEnd, columnAnchorsFor(newRange))
                contentResult.fold(
                    onSuccess = { (window, version) ->
                        _visibleContent.value = window.toVisibleContent(constrainedStart, version)
                        log(tag) { "Loaded content for range: $constrainedStart..$constrainedEnd" }
                    },
                    onFailure = { e ->
                        log(tag, WARN) { "Failed to load content for range: ${e.asLog()}" }
                    },
                )
            } catch (e: Exception) {
                log(tag, ERROR) { "Error loading content for visible range - ${e.asLog()}" }
            }
        }
    }

    /**
     * Steps back one committed transaction. [expectedEpoch] pins the request to ONE document: an
     * undo queued before a file switch must not revert the document that replaced it. A mismatch is
     * a banner-less no-op, like every other epoch rejection. Mandatory by design - an "unstamped"
     * undo would be exactly the request that cannot tell those two documents apart.
     */
    suspend fun undo(expectedEpoch: Uuid): Result<EditOperation?> = stateMutex.withLock {
        return when (val currentState = _state.value) {
            is EditorState.Loaded -> {
                if (expectedEpoch != engineEpoch) {
                    log(tag, INFO) { "undo dropped, it belongs to a different document" }
                    return Result.success(null)
                }
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

                        // Cursor BEFORE the refresh: refreshVisibleContent(ensureCursor = true) grows
                        // the loaded window to cover the cursor line, so a stale cursor would leave
                        // the new line unloaded and the field diverged from the engine.
                        result.getOrNull()?.let { operation ->
                            val newCursorPosition = when (operation) {
                                is EditOperation.Insert -> operation.position
                                is EditOperation.Delete -> computeEndPosition(operation.position, operation.deletedText)
                                is EditOperation.Replace -> computeEndPosition(operation.position, operation.oldText)
                            }
                            _cursorPosition.value = newCursorPosition
                            _selectionRange.value = null
                        }
                        refreshVisibleContent()
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

    /** See [undo]: [expectedEpoch] keeps a queued redo from re-applying into another document. */
    suspend fun redo(expectedEpoch: Uuid): Result<EditOperation?> = stateMutex.withLock {
        return when (val currentState = _state.value) {
            is EditorState.Loaded -> {
                if (expectedEpoch != engineEpoch) {
                    log(tag, INFO) { "redo dropped, it belongs to a different document" }
                    return Result.success(null)
                }
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

                        // See undo(): the cursor must be published before the window refresh
                        result.getOrNull()?.let { operation ->
                            val newCursorPosition = when (operation) {
                                is EditOperation.Insert -> computeEndPosition(operation.position, operation.text)
                                is EditOperation.Delete -> operation.position
                                is EditOperation.Replace -> computeEndPosition(operation.position, operation.newText)
                            }
                            _cursorPosition.value = newCursorPosition
                            _selectionRange.value = null
                        }
                        refreshVisibleContent()
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

    private fun computeEndPosition(start: TextPosition, text: String): TextPosition =
        endPositionOf(start, text, endOffset = start.offset + text.length)

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
         * How often [performEdit] re-resolves against a document that moved under it. A conflict
         * needs a search replacement to commit in exactly that window, so three attempts is a
         * generous bound - the cap only exists so a pathological replace-all loop cannot spin.
         */
        private const val MAX_EDIT_ATTEMPTS = 3

        // Idempotent break normalizer: "\r\n" matches before its parts, so already-conforming
        // text (e.g. regex group captures of CRLF document content) is never double-converted
        private val LINE_BREAK_REGEX = Regex("\r\n|\r|\n")

        /**
         * Every break form reduced to '\n'. Comparing a field delta's old text to the document's
         * slice has to ignore break FORM: the display window joins lines with '\n' whatever the
         * document holds, so a CR or mixed document would otherwise reject every edit spanning a
         * line break.
         */
        private fun String.canonicalBreaks(): String =
            if (contains('\r')) LINE_BREAK_REGEX.replace(this, "\n") else this

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