package eu.darken.butler.editor.core.engine

import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import eu.darken.butler.common.ca.toCaString
import eu.darken.butler.common.debug.logging.Logging.Priority.*
import eu.darken.butler.common.debug.logging.asLog
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.debug.logging.logTag
import eu.darken.butler.common.files.errors.PathNotFoundException
import eu.darken.butler.common.files.errors.PathPermissionDeniedException
import eu.darken.butler.common.files.errors.ReadException
import eu.darken.butler.common.files.errors.ServiceConnectionLostException
import eu.darken.butler.common.files.saf.MissingUriPermissionException
import eu.darken.butler.common.progress.Progress
import eu.darken.butler.common.files.write.FileCommitContext
import eu.darken.butler.editor.R
import eu.darken.butler.editor.core.engine.text.BlockIndex
import eu.darken.butler.editor.core.engine.text.BlockIndexBuilder
import eu.darken.butler.editor.core.engine.text.BlockOriginalDocument
import eu.darken.butler.editor.core.engine.text.LineBreakTransformer
import eu.darken.butler.editor.core.engine.text.Piece
import eu.darken.butler.editor.core.engine.text.PieceTable
import eu.darken.butler.editor.core.engine.text.WindowedSearch
import eu.darken.butler.editor.core.sources.CommitIntegrityException
import eu.darken.butler.editor.core.sources.EditorDataSource
import eu.darken.butler.workspace.core.Workspace
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okio.BufferedSink
import okio.Source
import okio.buffer
import okio.use
import java.io.FileNotFoundException
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.nio.file.NoSuchFileException
import java.security.MessageDigest
import java.util.LinkedList
import kotlin.coroutines.coroutineContext
import kotlin.random.Random
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeMark
import kotlin.time.TimeSource

/**
 * Piece-table document buffer: same public surface as the previous ChunkedTextBuffer, backed by
 * an immutable original document plus an add buffer, so cached original blocks are never
 * authoritative and eviction cannot corrupt content.
 *
 * All public offsets are char offsets (UTF-16 code units) as Long. One mutex serializes every
 * operation; undo/redo apply inverse edits inside the same lock via the piece table directly.
 */
class DocumentBuffer @AssistedInject constructor(
    @Assisted private val workspaceId: Workspace.Id,
    @Assisted private val dataSource: EditorDataSource,
    @Assisted("maxUndoStackSize") private val maxUndoStackSize: Int,
    @Assisted private val maxUndoMemoryBytes: Long,
    @Assisted("blockSize") private val blockSize: Int,
    @Assisted private val assertions: Boolean,
    @Assisted private val staleSampleRandom: Random = Random.Default,
    @Assisted private val timeSource: TimeSource = TimeSource.Monotonic,
    @Assisted("maxDisplayLineChars") val maxDisplayLineChars: Int = MAX_DISPLAY_LINE_CHARS,
) {

    private val tag = logTag("Editor", "Workspace", workspaceId.shortTag, "Engine", "DocumentBuffer")

    private val _contentSource = MutableStateFlow<ContentSource>(ContentSource.Memory(size = 0L))
    val contentSource: StateFlow<ContentSource> = _contentSource.asStateFlow()

    private val _lineEnding = MutableStateFlow(LineEnding.LF)
    val lineEnding: StateFlow<LineEnding> = _lineEnding.asStateFlow()

    private val _totalLines = MutableStateFlow(0L)
    val totalLines: StateFlow<Long> = _totalLines.asStateFlow()

    private val _totalLength = MutableStateFlow(0L)
    val totalLength: StateFlow<Long> = _totalLength.asStateFlow()

    private val _isModified = MutableStateFlow(false)
    val isModified: StateFlow<Boolean> = _isModified.asStateFlow()

    private val _canUndo = MutableStateFlow(false)
    val canUndo: StateFlow<Boolean> = _canUndo.asStateFlow()

    private val _canRedo = MutableStateFlow(false)
    val canRedo: StateFlow<Boolean> = _canRedo.asStateFlow()

    /**
     * A delete/replace whose removed span exceeds this many chars is applied WITHOUT reading the
     * removed text into memory and WITHOUT an undo entry (history is cleared instead) - the
     * transient String for a 100MB single-line delete would OOM. Half the undo memory budget (a
     * JVM String is 2 bytes/char, so this is "the delete would blow the undo budget"), floored so
     * cut stays undoable and a tiny budget doesn't over-trigger, and ceiled so a misconfigured huge
     * budget can't reintroduce the OOM (and stays under [PieceTable.read]'s Int.MAX_VALUE cap).
     */
    val maxUndoableEditChars: Long = (maxUndoMemoryBytes / 2).coerceIn(
        MIN_UNDOABLE_EDIT_CHARS,
        MAX_UNDOABLE_EDIT_CHARS,
    )

    private val _nonUndoableEditPending = MutableStateFlow(false)

    /**
     * True after a non-undoable delete/replace until the next recorded edit or a save. The periodic
     * auto-save loop pauses while set, so an accidental oversized replace isn't silently persisted
     * before the user can discard it (undo can't recover it).
     */
    val nonUndoableEditPending: StateFlow<Boolean> = _nonUndoableEditPending.asStateFlow()

    // Latches once the backing file becomes unreadable mid-session (deleted / permission lost):
    // original bytes can no longer be materialized, so the document goes read-only. Cleared only
    // by a successful initialize().
    private val _isBackingLost = MutableStateFlow(false)
    val isBackingLost: StateFlow<Boolean> = _isBackingLost.asStateFlow()
    private var backingLostCause: Throwable? = null

    private val bufferMutex = Mutex()
    private var pieceTable: PieceTable? = null
    private var originalDocument: BlockOriginalDocument? = null
    private var blockIndex: BlockIndex? = null
    private var detectedCharset: Charset = Charsets.UTF_8
    private var bomSize: Int = 0

    // Best-effort staleness baseline, captured at open and after each rebase
    private var lastKnownMeta: EditorDataSource.Meta? = null

    // Set when a commit succeeded but the rebase failed: pieces are stale, the buffer needs reload
    private var saveError: Throwable? = null

    private val undoStack = LinkedList<UndoEntry>()
    private val redoStack = LinkedList<UndoEntry>()
    private var currentUndoMemoryBytes = 0L
    private var currentRedoMemoryBytes = 0L

    // Typing-run coalescing: consecutive keystroke edits merge into the top undo entry so undo
    // steps back word-wise instead of one keystroke at a time. Only edits flagged with the
    // coalesce hint participate; any other action breaks the run.
    private var coalesceAnchor: UndoEntry? = null
    private var coalesceDeadline: TimeMark? = null

    // Save checkpoint: every edit gets a monotonic generation; isModified compares against the
    // generation recorded at save time (undo back to the saved state clears the flag)
    private var generationCounter = 0L
    private var currentGeneration = 0L
    private var savedGeneration = 0L
    private var savedGenerationValid = true

    // Bumped on EVERY piece-table mutation (edits, undo/redo, rebase, initialize). Searches
    // validate it per window so a long scan aborts instead of holding the lock across the
    // whole document while typing queues behind it. Mutated ONLY via [bumpStructuralVersion]
    // so the observable mirror below can never drift from it.
    private var structuralVersion = 0L

    private val _structuralVersionFlow = MutableStateFlow(0L)

    /**
     * Observable mirror of the mutation counter: lets derived reactive consumers (syntax
     * highlighting) recompute after mutations that don't change their other inputs - e.g. a
     * replace-all above the visible window leaves the window's text (and thus [contentSource]/
     * display flows) identical while the document before it changed.
     */
    val structuralVersionFlow: StateFlow<Long> = _structuralVersionFlow.asStateFlow()

    private fun bumpStructuralVersion() {
        structuralVersion++
        _structuralVersionFlow.value = structuralVersion
    }

    // Test seam: lets tests shrink windows and gate reads outside the lock
    internal var windowedSearchFactory: (suspend (Long, Long) -> String) -> WindowedSearch =
        { readText -> WindowedSearch(readText = readText) }

    suspend fun initialize(
        onProgress: ((Progress.Data) -> Unit)? = null,
    ): Result<Unit> = bufferMutex.withLock {
        try {
            val source = dataSource.contentSource.value
            _contentSource.value = source
            when (source) {
                is ContentSource.File -> {
                    log(tag) { "Initializing document buffer with file: ${source.path}" }
                    detectedCharset = source.detectedCharset
                    bomSize = source.bomBytes?.size ?: 0
                }
                is ContentSource.Memory -> {
                    log(tag) { "Initializing document buffer with in-memory content" }
                    detectedCharset = Charsets.UTF_8
                    bomSize = 0
                }
            }

            val physicalSize = dataSource.getMeta().size
            val logicalSize = (physicalSize - bomSize).coerceAtLeast(0L)

            val index = dataSource.openByteSource(bomSize.toLong()).buffer().use { byteSource ->
                BlockIndexBuilder(blockSize).build(byteSource, detectedCharset) { bytesIndexed ->
                    onProgress?.invoke(
                        Progress.Data(
                            primary = R.string.editor_progress_opening.toCaString(),
                            count = Progress.Count.Size(bytesIndexed, logicalSize),
                        ),
                    )
                }
            }

            val bomOffset = bomSize.toLong()
            val original = BlockOriginalDocument(index, detectedCharset) { byteStart, byteLen ->
                readOriginalBytes(bomOffset + byteStart, byteLen)
            }
            originalDocument = original
            blockIndex = index
            pieceTable = PieceTable.create(original, assertions)
            bumpStructuralVersion()

            _lineEnding.value = index.lineEnding
            (_contentSource.value as? ContentSource.File)?.let {
                _contentSource.value = it.copy(
                    lineEnding = index.lineEnding,
                    hasLongLines = index.maxLineLength > maxDisplayLineChars,
                )
            }
            saveError = null
            // A fresh load is the only recovery from backing loss: reopening re-reads the file, so
            // the read-only latch is cleared here (never on rebase, which reads before it succeeds)
            backingLostCause = null
            _isBackingLost.value = false
            lastKnownMeta = dataSource.getMeta()

            undoStack.clear()
            redoStack.clear()
            currentUndoMemoryBytes = 0L
            currentRedoMemoryBytes = 0L
            breakUndoRunLocked()
            generationCounter = 0L
            currentGeneration = 0L
            savedGeneration = 0L
            savedGenerationValid = true
            _nonUndoableEditPending.value = false
            refreshStats()
            updateModified()
            refreshUndoRedo()

            log(tag) { "Initialized (${_totalLength.value} chars, ${_totalLines.value} lines, ${index.lineEnding})" }
            Result.success(Unit)
        } catch (e: Exception) {
            log(tag, ERROR) { "Failed to initialize document buffer - ${e.asLog()}" }
            Result.failure(e)
        }
    }

    /**
     * Releases the buffer. [flush] (default) writes unsaved changes to disk as a safety net for
     * unprompted teardown; explicit discard flows (Save-As, close-with-discard, reopen-with-
     * encoding) pass false - the user chose to drop these changes, silently persisting them
     * would betray that choice.
     */
    suspend fun release(flush: Boolean = true): Result<Unit> = bufferMutex.withLock {
        try {
            if (_isModified.value) {
                if (flush) {
                    // Parity with the old engine: flush on close, but never block closing on failure
                    saveFileInternal().onFailure {
                        log(tag, WARN) { "Failed to flush changes on release - ${it.asLog()}" }
                    }
                } else {
                    log(tag, INFO) { "Releasing with unsaved changes explicitly discarded" }
                }
            }
            dataSource.close()
            pieceTable = null
            originalDocument = null
            undoStack.clear()
            redoStack.clear()
            currentUndoMemoryBytes = 0L
            currentRedoMemoryBytes = 0L
            _contentSource.value = ContentSource.Memory(size = 0L)
            _totalLines.value = 0
            _totalLength.value = 0L
            _isModified.value = false
            _nonUndoableEditPending.value = false
            refreshUndoRedo()
            Result.success(Unit)
        } catch (e: Exception) {
            log(tag, ERROR) { "Failed to close document buffer - ${e.asLog()}" }
            Result.failure(e)
        }
    }

    suspend fun getText(startOffset: Long, endOffset: Long): Result<String> = bufferMutex.withLock {
        try {
            Result.success(table().read(startOffset, endOffset))
        } catch (e: Exception) {
            log(tag, ERROR) { "Failed to get text for range: $startOffset-$endOffset - ${e.asLog()}" }
            Result.failure(e)
        }
    }

    /**
     * [getText] plus the [structuralVersion] the slice was read at, both under ONE lock hold: a
     * caller that pairs a separately read version with the text can hand out a snapshot describing
     * a document state that never existed (mutations bypassing the engine's stateMutex - e.g.
     * search-and-replace - can land between two separate reads).
     */
    suspend fun getTextWithVersion(startOffset: Long, endOffset: Long): Result<Pair<String, Long>> =
        bufferMutex.withLock {
            try {
                Result.success(table().read(startOffset, endOffset) to structuralVersion)
            } catch (e: Exception) {
                log(tag, ERROR) { "Failed to get text for range: $startOffset-$endOffset - ${e.asLog()}" }
                Result.failure(e)
            }
        }

    suspend fun getFullText(): Result<String> = bufferMutex.withLock {
        try {
            Result.success(table().readAll())
        } catch (e: Exception) {
            log(tag, ERROR) { "Failed to assemble full text - ${e.asLog()}" }
            Result.failure(e)
        }
    }

    /**
     * Full text plus the structural version it corresponds to: callers computing
     * context-sensitive work (regex expansion) from the text pass the version back to
     * [replaceMatches] so ANY intervening mutation aborts the replace.
     */
    suspend fun getFullTextWithVersion(): Result<Pair<String, Long>> = bufferMutex.withLock {
        try {
            Result.success(table().readAll() to structuralVersion)
        } catch (e: Exception) {
            log(tag, ERROR) { "Failed to assemble full text - ${e.asLog()}" }
            Result.failure(e)
        }
    }

    suspend fun getTextForLine(lineNumber: Long): Result<String> = bufferMutex.withLock {
        getTextForLineInternal(lineNumber)
    }

    suspend fun getTextForRange(startLine: Long, endLine: Long): Result<String> = bufferMutex.withLock {
        if (startLine < 0 || endLine >= _totalLines.value || startLine > endLine) {
            return@withLock Result.failure(IndexOutOfBoundsException("Invalid line range: $startLine-$endLine"))
        }
        val result = StringBuilder()
        for (lineNumber in startLine..endLine) {
            // Separator per line PAIR, not per non-empty builder: gating on isNotEmpty()
            // silently swallowed the separators after leading EMPTY lines, shifting every
            // subsequent line of the window up by one
            if (lineNumber > startLine) result.append('\n')
            val line = getTextForLineInternal(lineNumber).getOrElse { return@withLock Result.failure(it) }
            result.append(line)
        }
        Result.success(result.toString())
    }

    /**
     * A line's display slice: a window of at most [maxDisplayLineChars] chars of the line's content,
     * anchored at raw column [startColumn]. [startColumn] counts the content chars hidden BEFORE the
     * window (0 when the window starts at the line's beginning) and [hiddenChars] counts the content
     * chars hidden AFTER it. Total hidden on the line is [startColumn] + [hiddenChars].
     */
    data class LineSlice(
        val text: String,
        val startColumn: Long = 0L,
        val hiddenChars: Long,
    )

    /**
     * A display window: line range joined by '\n' with each line capped at
     * [maxDisplayLineChars], plus per-line hidden char counts. Both maps carry non-zero
     * entries only, keyed by ABSOLUTE line number. [startColumns] is the leading-hidden count
     * (window anchor) per line; [truncatedLines] is the trailing-hidden count.
     */
    data class DisplayWindow(
        val text: String,
        val truncatedLines: Map<Long, Long> = emptyMap(),
        val startColumns: Map<Long, Long> = emptyMap(),
    )

    /**
     * Like [getTextForRange], but every line is capped at [maxDisplayLineChars] for display.
     * [columnAnchors] optionally anchors a line's window at a non-zero raw column (keyed by
     * absolute line number); lines absent from the map anchor at column 0.
     */
    suspend fun getDisplayRange(
        startLine: Long,
        endLine: Long,
        columnAnchors: Map<Long, Long> = emptyMap(),
    ): Result<DisplayWindow> = getDisplayRangeWithVersion(startLine, endLine, columnAnchors).map { it.first }

    /**
     * [getDisplayRange] plus the [structuralVersion] the window was read at, both under ONE lock
     * hold: the input session maps its edits against this exact pairing, and a window paired with
     * a version read separately could map an edit onto a document that already moved.
     */
    suspend fun getDisplayRangeWithVersion(
        startLine: Long,
        endLine: Long,
        columnAnchors: Map<Long, Long> = emptyMap(),
    ): Result<Pair<DisplayWindow, Long>> = bufferMutex.withLock {
        if (startLine < 0 || endLine >= _totalLines.value || startLine > endLine) {
            return@withLock Result.failure(IndexOutOfBoundsException("Invalid line range: $startLine-$endLine"))
        }
        val result = StringBuilder()
        val truncatedLines = mutableMapOf<Long, Long>()
        val startColumns = mutableMapOf<Long, Long>()
        for (lineNumber in startLine..endLine) {
            // Separator per line PAIR (see getTextForRange): leading empty lines must keep theirs
            if (lineNumber > startLine) result.append('\n')
            val anchor = columnAnchors[lineNumber] ?: 0L
            val slice = getLineSliceInternal(lineNumber, anchor).getOrElse { return@withLock Result.failure(it) }
            result.append(slice.text)
            if (slice.hiddenChars > 0) truncatedLines[lineNumber] = slice.hiddenChars
            if (slice.startColumn > 0) startColumns[lineNumber] = slice.startColumn
        }
        Result.success(DisplayWindow(result.toString(), truncatedLines, startColumns) to structuralVersion)
    }

    suspend fun getLineSlice(lineNumber: Long, anchorColumn: Long = 0L): Result<LineSlice> = bufferMutex.withLock {
        getLineSliceInternal(lineNumber, anchorColumn)
    }

    /**
     * Current mutation counter for consistency checks by readers scanning outside the lock
     * (same invalidation idea as [search]): read before and after, discard work on mismatch.
     */
    suspend fun getStructuralVersion(): Long = bufferMutex.withLock { structuralVersion }

    /** EXACT content length of a line (breaks excluded) via pure offset math, no materialization. */
    suspend fun getLineLength(lineNumber: Long): Result<Long> = bufferMutex.withLock {
        if (lineNumber < 0 || lineNumber >= _totalLines.value) {
            return@withLock Result.failure(IndexOutOfBoundsException("Line number $lineNumber is out of bounds"))
        }
        try {
            val table = table()
            val start = table.lineStartOffset(lineNumber)
            val end = lineContentEnd(table, lineNumber)
            Result.success(end - start)
        } catch (e: Exception) {
            log(tag, ERROR) { "Failed to get line length for $lineNumber - ${e.asLog()}" }
            Result.failure(e)
        }
    }

    private suspend fun getLineSliceInternal(lineNumber: Long, anchorColumn: Long = 0L): Result<LineSlice> {
        if (lineNumber < 0 || lineNumber >= _totalLines.value) {
            return Result.failure(IndexOutOfBoundsException("Line number $lineNumber is out of bounds"))
        }
        return try {
            val table = table()
            val lineStart = table.lineStartOffset(lineNumber)
            val lineEnd = lineContentEnd(table, lineNumber)
            val realLength = lineEnd - lineStart
            // Clamp the anchor so a full-width window never hangs past the line end (a window near the
            // end still shows maxDisplayLineChars, just with nothing hidden after it).
            val maxStart = (realLength - maxDisplayLineChars).coerceAtLeast(0)
            var windowStart = lineStart + anchorColumn.coerceIn(0, maxStart)
            var sliceEnd = minOf(lineEnd, windowStart + maxDisplayLineChars)
            var text = table.read(windowStart, sliceEnd)
            // Leading edge: a non-zero window start can land on the low half of a surrogate pair.
            // Advance one unit so the slice never begins mid-pair (only reachable when anchored past 0).
            if (windowStart > lineStart && text.isNotEmpty() && text.first().isLowSurrogate()) {
                windowStart += 1
                text = table.read(windowStart, sliceEnd)
            }
            // Trailing edge: the cap must not split a surrogate pair (precedent: computeTextEdit).
            if (sliceEnd < lineEnd && text.isNotEmpty() && text.last().isHighSurrogate()) {
                text = text.dropLast(1)
                sliceEnd -= 1
            }
            Result.success(
                LineSlice(
                    text = text,
                    startColumn = windowStart - lineStart,
                    hiddenChars = lineEnd - sliceEnd,
                ),
            )
        } catch (e: Exception) {
            log(tag, ERROR) { "Failed to get line slice for $lineNumber - ${e.asLog()}" }
            Result.failure(e)
        }
    }

    /** [coalesce] marks a keystroke-sized edit eligible to merge into the current typing run. */
    suspend fun insertText(
        position: TextPosition,
        text: String,
        coalesce: Boolean = false,
    ): Result<TextPosition> = bufferMutex.withLock {
        try {
            val table = table()
            if (position.offset < 0 || position.offset > table.totalCharLength) {
                log(tag, ERROR) {
                    "insertText: offset ${position.offset} out of bounds, totalLength=${table.totalCharLength}"
                }
                return@withLock Result.failure(IllegalArgumentException("Position is out of bounds"))
            }
            if (text.isNotEmpty()) {
                table.insert(position.offset, text)
                commitNewEdit(EditOperation.Insert(position, text), coalesce)
            }
            Result.success(insertEndPosition(position, text))
        } catch (e: Exception) {
            log(tag, ERROR) { "Failed to insert text at position: $position - ${e.asLog()}" }
            Result.failure(e)
        }
    }

    /**
     * [coalesce] marks a keystroke-sized edit eligible to merge into the current typing run.
     *
     * A removed span larger than [maxUndoableEditChars] is deleted WITHOUT reading it into memory
     * (which would OOM on a giant single line) and WITHOUT an undo entry - undo/redo history is
     * cleared instead, [nonUndoableEditPending] is raised, and the returned deleted text is EMPTY
     * (not materialized). [table.delete]'s own range check still guards against an invalid range.
     */
    suspend fun deleteText(
        startPosition: TextPosition,
        endPosition: TextPosition,
        coalesce: Boolean = false,
    ): Result<String> =
        bufferMutex.withLock {
            try {
                val table = table()
                if (endPosition.offset - startPosition.offset > maxUndoableEditChars) {
                    table.delete(startPosition.offset, endPosition.offset)
                    discardHistoryForUnrecordedEdit()
                    return@withLock Result.success("")
                }
                val deletedText = table.read(startPosition.offset, endPosition.offset)
                if (deletedText.isNotEmpty()) {
                    table.delete(startPosition.offset, endPosition.offset)
                    commitNewEdit(
                        EditOperation.Delete(
                            startPosition,
                            (endPosition.offset - startPosition.offset).toInt(),
                            deletedText,
                        ),
                        coalesce,
                    )
                }
                Result.success(deletedText)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log(tag, ERROR) { "Failed to delete text from $startPosition to $endPosition - ${e.asLog()}" }
                Result.failure(e)
            }
        }

    /**
     * Atomic delete+insert under a single lock acquisition: no other operation can observe the
     * intermediate deleted-only state. Piece-table ops run first; bookkeeping (two undo entries,
     * parity with separate delete+insert) only happens after both succeeded, so a failed insert
     * rolls back by re-inserting the deleted text without having touched undo/redo state.
     */
    suspend fun replaceText(
        startPosition: TextPosition,
        endPosition: TextPosition,
        newText: String,
    ): Result<TextPosition> = bufferMutex.withLock {
        val table = try {
            table()
        } catch (e: Exception) {
            log(tag, ERROR) { "Failed to replace text from $startPosition to $endPosition - ${e.asLog()}" }
            return@withLock Result.failure(e)
        }
        // Oversized replace runs BEFORE taking a checkpoint: checkpoint() snapshots the whole add
        // buffer, so routing a huge replace through it would allocate a full duplicate - the very
        // OOM this guard exists to prevent. Self-contained insert-before-delete rollback needs no
        // snapshot. The removed text is never materialized; no undo entry, history is cleared.
        if (endPosition.offset - startPosition.offset > maxUndoableEditChars) {
            if (startPosition.offset < 0 ||
                startPosition.offset > endPosition.offset ||
                endPosition.offset > table.totalCharLength
            ) {
                return@withLock Result.failure(
                    IllegalArgumentException("Replace range out of bounds: $startPosition-$endPosition"),
                )
            }
            return@withLock try {
                // Insert BEFORE delete so a failed insert leaves the original range intact; then
                // delete the old range shifted right by the inserted length under NonCancellable.
                if (newText.isNotEmpty()) table.insert(startPosition.offset, newText)
                try {
                    withContext(NonCancellable) {
                        table.delete(
                            startPosition.offset + newText.length,
                            endPosition.offset + newText.length,
                        )
                    }
                } catch (e: Exception) {
                    // Delete failed after the insert ran; roll the insert back (deletes only the
                    // just-inserted Added piece - clean boundaries, no original reads).
                    if (newText.isNotEmpty()) {
                        withContext(NonCancellable) {
                            table.delete(startPosition.offset, startPosition.offset + newText.length)
                        }
                    }
                    throw e
                }
                discardHistoryForUnrecordedEdit()
                Result.success(insertEndPosition(startPosition, newText))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log(tag, ERROR) { "Failed oversized replace from $startPosition to $endPosition - ${e.asLog()}" }
                Result.failure(e)
            }
        }
        val checkpoint = table.checkpoint()
        try {
            val deletedText = table.read(startPosition.offset, endPosition.offset)
            if (deletedText.isNotEmpty()) {
                table.delete(startPosition.offset, endPosition.offset)
            }
            if (newText.isNotEmpty()) {
                table.insert(startPosition.offset, newText)
            }
            if (deletedText.isNotEmpty()) {
                commitNewEdit(
                    EditOperation.Delete(
                        startPosition,
                        (endPosition.offset - startPosition.offset).toInt(),
                        deletedText,
                    ),
                )
            }
            if (newText.isNotEmpty()) {
                commitNewEdit(EditOperation.Insert(startPosition, newText))
            }
            Result.success(insertEndPosition(startPosition, newText))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Roll back the partial delete/insert. Restore is purely in-memory, so it holds even
            // when the failure IS the backing file vanishing mid-splice (unlike re-inserting the
            // deleted text, which would re-read the gone original and double-fault).
            table.restore(checkpoint)
            log(tag, ERROR) { "Failed to replace text from $startPosition to $endPosition, rolled back - ${e.asLog()}" }
            Result.failure(e)
        }
    }

    suspend fun findPosition(offset: Long): TextPosition = bufferMutex.withLock {
        val table = tableOrNull() ?: return@withLock TextPosition(offset, 0, 0)
        val clamped = offset.coerceIn(0L, table.totalCharLength)
        val line = table.lineOfOffset(clamped)
        val lineStart = table.lineStartOffset(line)
        TextPosition(offset, line, (clamped - lineStart).toInt())
    }

    suspend fun findOffset(line: Long, column: Int): Long = bufferMutex.withLock {
        if (line < 0 || line >= _totalLines.value) {
            throw IndexOutOfBoundsException("Line $line is out of bounds (total lines: ${_totalLines.value})")
        }
        val table = table()
        val start = table.lineStartOffset(line)
        val end = lineContentEnd(table, line)
        start + column.coerceIn(0, (end - start).coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
    }

    /** Search results plus whether the scan stopped at the result caps before covering the document. */
    data class SearchOutcome(
        val results: List<SearchResult>,
        val truncated: Boolean,
    )

    /**
     * Scans the whole document WITHOUT holding [bufferMutex] across the scan: the lock is taken
     * per window read, and [structuralVersion] is validated each time. A concurrent edit makes
     * the scan fail with [SearchInvalidatedException] (distinguishable from no-matches) instead
     * of stalling the edit for the scan's duration. Edits can still wait for at most one
     * window's decode (~64KB) - bounded and acceptable.
     */
    suspend fun search(query: String, options: SearchOptions): Result<SearchOutcome> {
        // tableOrNull() throws when the buffer requires a reload - that must surface as a
        // Result too, the engine no longer wraps this call
        val (table, version, totalLength) = try {
            bufferMutex.withLock {
                val t = tableOrNull()
                    ?: return Result.success(SearchOutcome(emptyList(), truncated = false))
                Triple(t, structuralVersion, t.totalCharLength)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return Result.failure(e)
        }
        return try {
            val windowedSearch = windowedSearchFactory { start, end ->
                bufferMutex.withLock {
                    if (structuralVersion != version) throw SearchInvalidatedException()
                    table.read(start, end)
                }
            }
            val outcome = windowedSearch.search(totalLength, query, options)
            // An edit landing between the last window read and completion invalidates like a
            // mid-scan edit - the buffer API must not return matches known to be stale
            bufferMutex.withLock {
                if (structuralVersion != version) throw SearchInvalidatedException()
            }
            val matches = outcome.matches.map { match ->
                SearchResult(
                    position = TextPosition(match.offset, match.line, match.column),
                    matchText = match.matchText,
                )
            }
            Result.success(SearchOutcome(matches, outcome.truncated))
        } catch (e: SearchInvalidatedException) {
            log(tag) { "Search invalidated by a concurrent edit" }
            Result.failure(e)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log(tag, ERROR) { "Search failed for query: $query - ${e.asLog()}" }
            Result.failure(e)
        }
    }

    /** A single verified replacement: [oldText] must still sit at [startOffset] when applied. */
    data class MatchReplacement(
        val startOffset: Long,
        val oldText: String,
        val newText: String,
    )

    data class ReplaceStats(
        val count: Int,
        /** False when the composite entry was immediately evicted by the undo memory cap. */
        val undoable: Boolean,
    )

    /**
     * A single verified replacement in flat offsets: [expectedOldText] must still occupy
     * [startOffset] until [endOffset] when the patch is applied, and the invariant
     * `endOffset == startOffset + expectedOldText.length` must hold.
     *
     * Patches are ALWAYS recorded and undoable. Callers keep their spans under
     * [maxUndoableEditChars] (field deltas are window-bounded and never come close); a patch whose
     * old text exceeds that budget fails cleanly instead of materializing it - the deliberately
     * unrecorded path is [applyOversizedReplace].
     */
    data class VerifiedPatch(
        val startOffset: Long,
        val endOffset: Long,
        val expectedOldText: String,
        val newText: String,
    )

    /** How a mutation joins the undo history: merge into the current typing run, or stand alone. */
    enum class UndoPolicy { COALESCE, SEPARATE }

    data class MutationOutcome(
        /** The [structuralVersion] the document reached, read inside the same lock hold. */
        val newVersion: Long,
        /** False when the recorded entry was immediately evicted by the undo memory cap. */
        val undoable: Boolean,
    )

    /**
     * Applies all [patches] as ONE undo step. [expectedVersion] (when non-null) and every patch's
     * old text are verified against the live document BEFORE anything mutates - any divergence
     * fails with [StaleMatchException] and the document is untouched. Application runs
     * back-to-front so earlier offsets stay valid.
     *
     * A single patch is normalized to the narrowest operation (pure insert / pure delete /
     * delete+insert) so one keystroke stays one undo step; [UndoPolicy.COALESCE] additionally
     * merges keystroke-sized patches into the current typing run.
     */
    suspend fun applyMutation(
        expectedVersion: Long?,
        patches: List<VerifiedPatch>,
        undoPolicy: UndoPolicy,
    ): Result<MutationOutcome> = bufferMutex.withLock {
        try {
            if (expectedVersion != null && expectedVersion != structuralVersion) {
                // The request was computed from a stale snapshot (context-sensitive regex results
                // can be invalidated even when oldText still matches)
                return@withLock Result.failure(StaleMatchException())
            }
            if (patches.isEmpty()) {
                return@withLock Result.success(MutationOutcome(structuralVersion, undoable = true))
            }
            val table = table()
            val sorted = patches.sortedByDescending { it.startOffset }

            // Verify pass: all-or-nothing before the first mutation
            var previousStart = Long.MAX_VALUE
            for (patch in sorted) {
                if (patch.endOffset != patch.startOffset + patch.expectedOldText.length) {
                    return@withLock Result.failure(
                        IllegalArgumentException("Patch range does not match its old text: $patch"),
                    )
                }
                if (patch.expectedOldText.length > maxUndoableEditChars) {
                    return@withLock Result.failure(
                        IllegalArgumentException("Patch exceeds the undoable edit budget: $maxUndoableEditChars"),
                    )
                }
                if (patch.startOffset < 0 || patch.endOffset > table.totalCharLength ||
                    patch.endOffset > previousStart
                ) {
                    return@withLock Result.failure(StaleMatchException())
                }
                if (table.read(patch.startOffset, patch.endOffset) != patch.expectedOldText) {
                    return@withLock Result.failure(StaleMatchException())
                }
                previousStart = patch.startOffset
            }

            val ops = mutableListOf<EditOperation>()
            // Snapshot before the first mutation: a mid-batch original read can fail (the backing
            // file vanished), and applying N patches is otherwise non-atomic - an earlier patch
            // would land with no undo entry. Roll back fully on any failure.
            val checkpoint = table.checkpoint()
            try {
                for (patch in sorted) {
                    val line = table.lineOfOffset(patch.startOffset)
                    val column = (patch.startOffset - table.lineStartOffset(line)).toInt()
                    val position = TextPosition(patch.startOffset, line, column)
                    if (patch.expectedOldText.isNotEmpty()) table.delete(patch.startOffset, patch.endOffset)
                    if (patch.newText.isNotEmpty()) table.insert(patch.startOffset, patch.newText)
                    ops += operationsFor(patch, position, normalize = patches.size == 1)
                }
            } catch (e: Exception) {
                table.restore(checkpoint)
                throw e
            }
            if (ops.isEmpty()) {
                // A single patch that neither removed nor inserted anything: nothing to record
                return@withLock Result.success(MutationOutcome(structuralVersion, undoable = true))
            }
            val keystrokeSized = patches.size == 1 &&
                patches.first().newText.length <= 2 &&
                (patches.first().endOffset - patches.first().startOffset) <= 2
            if (undoPolicy == UndoPolicy.COALESCE && keystrokeSized && ops.size == 1) {
                commitNewEdit(ops.single(), coalesce = true)
            } else {
                breakUndoRunLocked()
                commitNewEdit(ops)
            }
            // The lone-entry guard keeps an oversized composite until the NEXT edit; report
            // honestly whether this step is still on the stack
            val undoable = undoStack.peekLast()?.generationAfter == currentGeneration
            Result.success(MutationOutcome(structuralVersion, undoable))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log(tag, ERROR) { "applyMutation failed - ${e.asLog()}" }
            Result.failure(e)
        }
    }

    /**
     * Undo operations for an applied patch. A lone patch is normalized so a keystroke stays one
     * op (and can coalesce); a batch keeps composite [EditOperation.Replace] entries.
     */
    private fun operationsFor(
        patch: VerifiedPatch,
        position: TextPosition,
        normalize: Boolean,
    ): List<EditOperation> {
        if (!normalize) return listOf(EditOperation.Replace(position, patch.expectedOldText, patch.newText))
        val removed = patch.expectedOldText
        val inserted = patch.newText
        return when {
            removed.isEmpty() && inserted.isEmpty() -> emptyList()
            removed.isEmpty() -> listOf(EditOperation.Insert(position, inserted))
            inserted.isEmpty() -> listOf(EditOperation.Delete(position, removed.length, removed))
            else -> listOf(
                EditOperation.Delete(position, removed.length, removed),
                EditOperation.Insert(position, inserted),
            )
        }
    }

    /**
     * Replaces [startOffset]..[endOffset] with [newText] as ONE undo step, verified against
     * [expectedVersion] inside the SAME lock hold: a mutation landing between the caller's version
     * read and this call fails with [StaleMatchException] and the document stays untouched.
     *
     * Unlike [applyMutation] the caller does not know the removed text - it is read here, exactly
     * ONCE, and returned. That single materialization is the point: the removed span feeds both the
     * undo entry and the caller's result (backspace/cut report what they removed), and reading it
     * twice - or verifying it against a copy - would double the transient allocation this path is
     * budgeted for. Spans above [maxUndoableEditChars] are refused; that is
     * [applyOversizedReplace]'s job.
     *
     * The recorded operation is normalized like a single [applyMutation] patch (pure insert / pure
     * delete / delete+insert in one entry), and the typing run is broken first: this path is never
     * a keystroke.
     */
    suspend fun applyVersionedReplace(
        expectedVersion: Long,
        startOffset: Long,
        endOffset: Long,
        newText: String,
    ): Result<Pair<MutationOutcome, String>> = bufferMutex.withLock {
        try {
            if (expectedVersion != structuralVersion) return@withLock Result.failure(StaleMatchException())
            val table = table()
            if (startOffset < 0 || startOffset > endOffset || endOffset > table.totalCharLength) {
                return@withLock Result.failure(
                    IllegalArgumentException("Replace range out of bounds: $startOffset-$endOffset"),
                )
            }
            if (endOffset - startOffset > maxUndoableEditChars) {
                return@withLock Result.failure(
                    IllegalArgumentException("Replace exceeds the undoable edit budget: $maxUndoableEditChars"),
                )
            }
            val line = table.lineOfOffset(startOffset)
            val position = TextPosition(startOffset, line, (startOffset - table.lineStartOffset(line)).toInt())
            val removedText = table.read(startOffset, endOffset)
            // Snapshot before the first mutation: a mid-splice original read can fail (the backing
            // file vanished) and must not leave a half-applied edit with no undo entry.
            val checkpoint = table.checkpoint()
            try {
                if (removedText.isNotEmpty()) table.delete(startOffset, endOffset)
                if (newText.isNotEmpty()) table.insert(startOffset, newText)
            } catch (e: Exception) {
                table.restore(checkpoint)
                throw e
            }
            val ops = operationsFor(
                VerifiedPatch(startOffset, endOffset, removedText, newText),
                position,
                normalize = true,
            )
            if (ops.isEmpty()) {
                return@withLock Result.success(MutationOutcome(structuralVersion, undoable = true) to removedText)
            }
            breakUndoRunLocked()
            commitNewEdit(ops)
            // The lone-entry guard keeps an oversized entry until the NEXT edit; report honestly
            // whether this step is still on the stack
            val undoable = undoStack.peekLast()?.generationAfter == currentGeneration
            Result.success(MutationOutcome(structuralVersion, undoable) to removedText)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log(tag, ERROR) { "applyVersionedReplace failed for $startOffset-$endOffset - ${e.asLog()}" }
            Result.failure(e)
        }
    }

    /**
     * Applies all [replacements] as ONE undo step; see [applyMutation] for the verification
     * contract.
     */
    suspend fun replaceMatches(
        replacements: List<MatchReplacement>,
        expectedVersion: Long? = null,
    ): Result<ReplaceStats> {
        if (replacements.isEmpty()) return Result.success(ReplaceStats(0, undoable = true))
        val patches = replacements.map {
            VerifiedPatch(
                startOffset = it.startOffset,
                endOffset = it.startOffset + it.oldText.length,
                expectedOldText = it.oldText,
                newText = it.newText,
            )
        }
        return applyMutation(expectedVersion, patches, UndoPolicy.SEPARATE)
            .map { ReplaceStats(replacements.size, it.undoable) }
    }

    /**
     * Replaces [startOffset]..[endOffset] with [newText] WITHOUT reading the removed span into
     * memory and WITHOUT an undo entry (history is cleared instead) - the transient String for a
     * 100MB single-line replace would OOM. [expectedVersion] is re-checked under the same lock, so
     * a request prepared before a confirmation dialog can never mutate a document that moved on.
     *
     * Deliberately takes NO [PieceTable.checkpoint]: a checkpoint snapshots the whole add buffer,
     * which is the very allocation this path exists to avoid. Rollback is self-contained instead -
     * the insert runs before the delete, so a failed delete only has to drop the added piece.
     */
    suspend fun applyOversizedReplace(
        expectedVersion: Long,
        startOffset: Long,
        endOffset: Long,
        newText: String,
    ): Result<MutationOutcome> = bufferMutex.withLock {
        try {
            if (expectedVersion != structuralVersion) return@withLock Result.failure(StaleMatchException())
            val table = table()
            if (startOffset < 0 || startOffset > endOffset || endOffset > table.totalCharLength) {
                return@withLock Result.failure(
                    IllegalArgumentException("Replace range out of bounds: $startOffset-$endOffset"),
                )
            }
            if (newText.isNotEmpty()) table.insert(startOffset, newText)
            try {
                withContext(NonCancellable) {
                    table.delete(startOffset + newText.length, endOffset + newText.length)
                }
            } catch (e: Exception) {
                // Delete failed after the insert ran; roll the insert back (deletes only the
                // just-inserted Added piece - clean boundaries, no original reads).
                if (newText.isNotEmpty()) {
                    withContext(NonCancellable) {
                        table.delete(startOffset, startOffset + newText.length)
                    }
                }
                throw e
            }
            discardHistoryForUnrecordedEdit()
            Result.success(MutationOutcome(structuralVersion, undoable = false))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log(tag, ERROR) { "Failed oversized replace from $startOffset to $endOffset - ${e.asLog()}" }
            Result.failure(e)
        }
    }

    suspend fun saveFile(): Result<Unit> = bufferMutex.withLock {
        saveFileInternal()
    }

    private suspend fun saveFileInternal(): Result<Unit> {
        return try {
            saveError?.let {
                return Result.failure(IllegalStateException("Buffer requires reload after failed save", it))
            }
            // Also covers the release() flush path, which bypasses the engine's editability gate
            if (_isBackingLost.value) {
                return Result.failure(BackingUnavailableException("Backing file is no longer available"))
            }
            val table = pieceTable
                ?: return Result.failure(IllegalStateException("Buffer not initialized"))
            if (!_isModified.value) {
                log(tag) { "No modifications to save" }
                return Result.success(Unit)
            }
            (_contentSource.value as? ContentSource.File)?.let { source ->
                if (source.isLikelyBinary) {
                    // Editing a binary file through the text pipeline would corrupt it
                    return Result.failure(ReadOnlyFileException("Binary file, saving is disabled: ${source.path}"))
                }
                if (!source.canWrite) {
                    return Result.failure(ReadOnlyFileException("File is read-only: ${source.path}"))
                }
            }

            checkStaleness()
            val expectedLength = table.totalCharLength
            dataSource.commit { context -> writeSplice(context, table) }

            withContext(NonCancellable) {
                try {
                    rebase()
                    check(pieceTable?.totalCharLength == expectedLength) {
                        "Rebased length ${pieceTable?.totalCharLength} != pre-save length $expectedLength"
                    }
                } catch (e: Exception) {
                    saveError = e
                    log(tag, ERROR) { "Post-save rebase failed, buffer requires reload - ${e.asLog()}" }
                    throw e
                }
            }
            log(tag, INFO) { "Saved and rebased (${_totalLength.value} chars)" }
            Result.success(Unit)
        } catch (e: CancellationException) {
            // Cancelled before the point of no return: commit cleaned up, buffer stays editable
            throw e
        } catch (e: CommitIntegrityException) {
            // The on-disk state no longer matches the pre-commit content: pieces are stale
            saveError = e
            log(tag, ERROR) { "Commit left the target inconsistent, buffer requires reload - ${e.asLog()}" }
            Result.failure(e)
        } catch (e: Exception) {
            log(tag, ERROR) { "Failed to save - ${e.asLog()}" }
            Result.failure(e)
        }
    }

    private suspend fun writeSplice(context: FileCommitContext, table: PieceTable) {
        streamPieces(context.sink, table, context::openOriginalSource)
    }

    /**
     * Streams the current document content (including unsaved edits) to [sink] - byte-identical
     * to what saving would write. Runs under the buffer mutex, so no commit can move the
     * underlying file while originals are being read. Guarded by [checkStaleness] like a save:
     * splicing original byte ranges from an externally modified file would stream a corrupted
     * old/new mix into the destination (Save-As).
     */
    suspend fun writeContentTo(sink: BufferedSink): Result<Unit> = bufferMutex.withLock {
        try {
            checkStaleness()
            streamPieces(sink, table()) { offset -> dataSource.openByteSource(offset) }
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log(tag, ERROR) { "Failed to stream content - ${e.asLog()}" }
            Result.failure(e)
        }
    }

    /**
     * Converts every line break in the document to [target] and saves - one atomic commit, then
     * the usual rebase. Char content intentionally changes across this rebase, so undo/redo
     * history is CLEARED (the rebase survive-invariant doesn't hold). Unsaved edits are saved as
     * part of the conversion. Original pieces are decoded from the commit context (never the
     * live data source - in-place commits read from the backup) and re-encoded; malformed byte
     * sequences become U+FFFD, same as editing those regions.
     */
    suspend fun convertLineEndings(target: LineEnding): Result<Unit> = bufferMutex.withLock {
        try {
            require(target == LineEnding.LF || target == LineEnding.CRLF) {
                "Unsupported conversion target: $target"
            }
            saveError?.let {
                return Result.failure(IllegalStateException("Buffer requires reload after failed save", it))
            }
            val table = pieceTable
                ?: return Result.failure(IllegalStateException("Buffer not initialized"))
            (_contentSource.value as? ContentSource.File)?.let { source ->
                if (source.isLikelyBinary) {
                    return Result.failure(ReadOnlyFileException("Binary file, saving is disabled: ${source.path}"))
                }
                if (!source.canWrite) {
                    return Result.failure(ReadOnlyFileException("File is read-only: ${source.path}"))
                }
            }

            // Before the no-op path too: "nothing to convert" must not report success (and let
            // callers clear their external-change state) over an unverified baseline
            checkStaleness()
            if (_lineEnding.value == target && !_isModified.value) {
                log(tag) { "Document is already uniformly $target, nothing to convert" }
                return Result.success(Unit)
            }
            val targetBreak = if (target == LineEnding.CRLF) "\r\n" else "\n"
            dataSource.commit { context -> writeConverted(context, table, targetBreak) }

            withContext(NonCancellable) {
                try {
                    rebase()
                    clearUndoHistoryLocked()
                } catch (e: Exception) {
                    saveError = e
                    log(tag, ERROR) { "Post-conversion rebase failed, buffer requires reload - ${e.asLog()}" }
                    throw e
                }
            }
            log(tag, INFO) { "Converted line endings to $target (${_totalLength.value} chars)" }
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: CommitIntegrityException) {
            saveError = e
            log(tag, ERROR) { "Commit left the target inconsistent, buffer requires reload - ${e.asLog()}" }
            Result.failure(e)
        } catch (e: Exception) {
            log(tag, ERROR) { "Failed to convert line endings - ${e.asLog()}" }
            Result.failure(e)
        }
    }

    /** Like [streamPieces], but every break is rewritten to [targetBreak] via [LineBreakTransformer]. */
    private suspend fun writeConverted(
        context: FileCommitContext,
        table: PieceTable,
        targetBreak: String,
    ) {
        (_contentSource.value as? ContentSource.File)?.bomBytes?.let { context.sink.write(it) }

        val bomOffset = bomSize.toLong()
        val transformer = LineBreakTransformer(targetBreak)
        var position = 0L
        var runStart = -1L

        suspend fun flushAddedRun(end: Long) {
            if (runStart < 0) return
            val text = table.read(runStart, end)
            context.sink.write(encodeAdded(transformer.transform(text)))
            runStart = -1L
        }

        for (piece in table.pieceSnapshot()) {
            coroutineContext.ensureActive()
            when (piece) {
                is Piece.Added -> if (runStart < 0) runStart = position
                is Piece.Original -> {
                    flushAddedRun(position)
                    writeConvertedOriginal(context, bomOffset + piece.byteStart, piece.byteLen, transformer)
                }
            }
            position += piece.charCount
        }
        flushAddedRun(position)
        transformer.flushTrailing()?.let { context.sink.write(encodeAdded(it)) }
    }

    /**
     * Decodes [byteLen] original bytes from the commit context in bounded chunks, rewrites their
     * breaks, and re-encodes. The decoder carries partial multibyte sequences across chunk reads
     * and never splits a surrogate pair across output buffers, so chunked re-encoding is exact.
     */
    private suspend fun writeConvertedOriginal(
        context: FileCommitContext,
        physicalOffset: Long,
        byteLen: Long,
        transformer: LineBreakTransformer,
    ) {
        val decoder = detectedCharset.newDecoder()
            .onMalformedInput(CodingErrorAction.REPLACE)
            .onUnmappableCharacter(CodingErrorAction.REPLACE)
        context.openOriginalSource(physicalOffset).buffer().use { source ->
            val byteBuf = ByteBuffer.allocate(CONVERT_CHUNK_BYTES)
            val charBuf = CharBuffer.allocate(CONVERT_CHUNK_BYTES)
            var remaining = byteLen
            var endOfInput = remaining == 0L
            while (true) {
                coroutineContext.ensureActive()
                if (!endOfInput) {
                    val toRead = minOf(remaining, byteBuf.remaining().toLong()).toInt()
                    val chunk = ByteArray(toRead)
                    source.readFully(chunk)
                    byteBuf.put(chunk)
                    remaining -= toRead
                    endOfInput = remaining == 0L
                }
                byteBuf.flip()
                while (true) {
                    val result = decoder.decode(byteBuf, charBuf, endOfInput)
                    flushDecoded(context.sink, charBuf, transformer)
                    if (!result.isOverflow) break
                }
                byteBuf.compact()
                if (endOfInput) break
            }
            while (true) {
                val result = decoder.flush(charBuf)
                flushDecoded(context.sink, charBuf, transformer)
                if (!result.isOverflow) break
            }
        }
    }

    private fun flushDecoded(sink: BufferedSink, charBuf: CharBuffer, transformer: LineBreakTransformer) {
        charBuf.flip()
        if (charBuf.hasRemaining()) {
            sink.write(encodeAdded(transformer.transform(charBuf.toString())))
        }
        charBuf.clear()
    }

    /**
     * Bookkeeping after a piece-table mutation whose removed text was deliberately NOT read into
     * memory (oversized delete/replace). No undo entry exists for it, and every existing entry's
     * recorded offsets are potentially invalid against the now-shrunk document, so ALL history is
     * discarded - not just this op. Generation advances so isModified reflects the edit;
     * savedGeneration is left as-is (the file isn't saved) but marked invalid, so isModified stays
     * true until a real save. Callers invoke this only AFTER the table mutation succeeded.
     */
    private fun discardHistoryForUnrecordedEdit() {
        bumpStructuralVersion()
        currentGeneration = ++generationCounter
        savedGenerationValid = false
        clearUndoHistoryLocked()
        _nonUndoableEditPending.value = true
        refreshStats()
        updateModified()
    }

    private fun clearUndoHistoryLocked() {
        undoStack.clear()
        redoStack.clear()
        currentUndoMemoryBytes = 0L
        currentRedoMemoryBytes = 0L
        breakUndoRunLocked()
        refreshUndoRedo()
    }

    /** Streams the document in original-file order: BOM once, Original byte ranges verbatim, contiguous Added runs encoded as one string. */
    private suspend fun streamPieces(
        sink: BufferedSink,
        table: PieceTable,
        readOriginal: suspend (physicalOffset: Long) -> Source,
    ) {
        (_contentSource.value as? ContentSource.File)?.bomBytes?.let { sink.write(it) }

        val bomOffset = bomSize.toLong()
        var position = 0L
        var runStart = -1L

        suspend fun flushAddedRun(end: Long) {
            if (runStart < 0) return
            val text = table.read(runStart, end)
            sink.write(encodeAdded(text))
            runStart = -1L
        }

        for (piece in table.pieceSnapshot()) {
            coroutineContext.ensureActive()
            when (piece) {
                is Piece.Added -> if (runStart < 0) runStart = position
                is Piece.Original -> {
                    flushAddedRun(position)
                    readOriginal(bomOffset + piece.byteStart).buffer().use { source ->
                        sink.write(source, piece.byteLen)
                    }
                }
            }
            position += piece.charCount
        }
        flushAddedRun(position)
    }

    /**
     * Encodes an added-text run with an explicit U+FFFD replacement so unencodable content
     * (lone surrogates) produces identical bytes on every platform - `String.toByteArray`
     * replacement bytes differ between JVM ('?') and Android ICU encoders.
     */
    private fun encodeAdded(text: String): ByteArray {
        val encoder = detectedCharset.newEncoder()
            .onMalformedInput(CodingErrorAction.REPLACE)
            .onUnmappableCharacter(CodingErrorAction.REPLACE)
        when (detectedCharset) {
            Charsets.UTF_8 -> byteArrayOf(0xEF.toByte(), 0xBF.toByte(), 0xBD.toByte())
            Charsets.UTF_16LE -> byteArrayOf(0xFD.toByte(), 0xFF.toByte())
            Charsets.UTF_16BE -> byteArrayOf(0xFF.toByte(), 0xFD.toByte())
            else -> null
        }?.let { encoder.replaceWith(it) }
        val encoded = encoder.encode(CharBuffer.wrap(text))
        return ByteArray(encoded.remaining()).also { encoded.get(it) }
    }

    /**
     * Rescans the just-saved file into a fresh single-piece document. Undo/redo stacks survive:
     * their operations are text-based and char content is identical across the rebase.
     */
    private suspend fun rebase() {
        val index = dataSource.openByteSource(bomSize.toLong()).buffer().use { byteSource ->
            BlockIndexBuilder(blockSize).build(byteSource, detectedCharset)
        }
        val bomOffset = bomSize.toLong()
        val original = BlockOriginalDocument(index, detectedCharset) { byteStart, byteLen ->
            readOriginalBytes(bomOffset + byteStart, byteLen)
        }
        originalDocument = original
        blockIndex = index
        pieceTable = PieceTable.create(original, assertions)
        bumpStructuralVersion()

        _lineEnding.value = index.lineEnding
        lastKnownMeta = dataSource.getMeta()
        val freshSource = dataSource.contentSource.value
        _contentSource.value = if (freshSource is ContentSource.File) {
            // Size/mtime from our own fresh lookup - the data source's post-commit refresh is best-effort
            freshSource.copy(
                lineEnding = index.lineEnding,
                size = lastKnownMeta?.size ?: freshSource.size,
                lastModified = lastKnownMeta?.modifiedAt ?: freshSource.lastModified,
                hasLongLines = index.maxLineLength > maxDisplayLineChars,
            )
        } else {
            freshSource
        }
        savedGeneration = currentGeneration
        savedGenerationValid = true
        _nonUndoableEditPending.value = false
        breakUndoRunLocked()
        refreshStats()
        updateModified()
    }

    private suspend fun computeBlockDigest(block: BlockIndex.Block): Long {
        val bytes = readOriginalBytes(bomSize + block.byteStart, block.byteLen)
        return BlockIndexBuilder.truncateDigest(MessageDigest.getInstance("SHA-256").digest(bytes))
    }

    /**
     * Best-effort external-modification guard: size + mtime, then BOM bytes, then per-block
     * digest verification of the first, last, and [STALENESS_SAMPLE_COUNT] random interior
     * blocks (catches same-size edits under coarse/missing mtime on SAF/root). SAMPLED, not
     * exhaustive: if a same-size/same-mtime interior tamper lands only in unsampled blocks, the
     * save splices around it and the post-save rebase makes the tampered bytes the permanent new
     * baseline. Per-save catch probability is roughly (sampleCount + 2) / blockCount; closing
     * the gap entirely would mean re-reading the whole file at every save, which was rejected as
     * it defeats lazy open. Not race-free either — same limitation as any file editor.
     */
    private suspend fun checkStaleness() {
        val known = lastKnownMeta ?: return
        val current = try {
            dataSource.getMeta()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Save/convert/stream reach here before touching original bytes; a vanished file must
            // latch read-only here too, not only via the readOriginalBytes backstop.
            if (e.isBackingUnavailable()) latchBackingLost(e)
            throw e
        }
        if (current.size != known.size) {
            throw ExternalModificationException(
                "File size changed externally: ${known.size} -> ${current.size} bytes",
            )
        }
        if (known.modifiedAt != null && current.modifiedAt != null && current.modifiedAt != known.modifiedAt) {
            throw ExternalModificationException(
                "File was modified externally: ${known.modifiedAt} -> ${current.modifiedAt}",
            )
        }
        (_contentSource.value as? ContentSource.File)?.bomBytes?.let { bom ->
            if (!bom.contentEquals(readOriginalBytes(0L, bom.size))) {
                throw ExternalModificationException("File BOM changed externally (same size and mtime)")
            }
        }
        val index = blockIndex ?: return
        if (index.blocks.isEmpty()) return
        val sampled = buildSet {
            add(0)
            add(index.blocks.size - 1)
            if (index.blocks.size > 2) {
                repeat(STALENESS_SAMPLE_COUNT) {
                    add(1 + staleSampleRandom.nextInt(index.blocks.size - 2))
                }
            }
        }
        for (i in sampled) {
            if (computeBlockDigest(index.blocks[i]) != index.blockDigests[i]) {
                throw ExternalModificationException("File content changed externally (same size and mtime)")
            }
        }
    }

    /**
     * Meta-only external-change probe: the size + mtime tiers of [checkStaleness] without the
     * digest I/O, non-throwing - cheap enough to poll. Same-size changes under missing/coarse
     * mtime stay save-time-guarded only. [ExternalChangeProbe.Unknown] (no baseline, lookup
     * failed, file deleted) is distinct from [ExternalChangeProbe.Unchanged]: an unreadable file
     * is no evidence the baseline was restored.
     */
    suspend fun checkExternalChange(): ExternalChangeProbe = bufferMutex.withLock {
        val known = lastKnownMeta ?: return@withLock ExternalChangeProbe.Unknown
        val current = try {
            dataSource.getMeta()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Proactive detection: the polled metadata read is where a deleted/denied file first
            // shows up, before the user touches an original-byte range (e.g. typing at EOF).
            if (e.isBackingUnavailable()) latchBackingLost(e)
            log(tag, VERBOSE) { "checkExternalChange: getMeta failed - $e" }
            return@withLock ExternalChangeProbe.Unknown
        }
        val changed = current.size != known.size ||
            (known.modifiedAt != null && current.modifiedAt != null && current.modifiedAt != known.modifiedAt)
        if (changed) ExternalChangeProbe.Changed(current) else ExternalChangeProbe.Unchanged
    }

    sealed interface ExternalChangeProbe {
        /** The on-disk meta matches the open/rebase baseline. */
        data object Unchanged : ExternalChangeProbe

        /** The on-disk meta differs from the baseline. */
        data class Changed(val meta: EditorDataSource.Meta) : ExternalChangeProbe

        /** The disk state could not be determined; existing detections must not be cleared. */
        data object Unknown : ExternalChangeProbe
    }

    suspend fun undo(): Result<EditOperation?> = bufferMutex.withLock {
        breakUndoRunLocked()
        val entry = undoStack.pollLast() ?: return@withLock Result.success(null)
        try {
            val table = table()
            // Composite entries (typing runs, replace-all) revert as one step, newest op first
            for (operation in entry.ops.asReversed()) {
                when (operation) {
                    is EditOperation.Insert -> {
                        table.delete(operation.position.offset, operation.position.offset + operation.text.length)
                    }
                    is EditOperation.Delete -> {
                        table.insert(operation.position.offset, operation.deletedText)
                    }
                    is EditOperation.Replace -> {
                        table.delete(operation.position.offset, operation.position.offset + operation.newText.length)
                        table.insert(operation.position.offset, operation.oldText)
                    }
                }
            }
            bumpStructuralVersion()
            val memory = entry.estimateMemoryBytes()
            currentUndoMemoryBytes -= memory
            redoStack.addLast(entry)
            currentRedoMemoryBytes += memory
            currentGeneration = entry.generationBefore
            refreshStats()
            updateModified()
            refreshUndoRedo()
            Result.success(entry.ops.first())
        } catch (e: Exception) {
            undoStack.addLast(entry)
            log(tag, ERROR) { "Undo failed - ${e.asLog()}" }
            Result.failure(e)
        }
    }

    suspend fun redo(): Result<EditOperation?> = bufferMutex.withLock {
        breakUndoRunLocked()
        val entry = redoStack.pollLast() ?: return@withLock Result.success(null)
        try {
            val table = table()
            for (operation in entry.ops) {
                when (operation) {
                    is EditOperation.Insert -> {
                        table.insert(operation.position.offset, operation.text)
                    }
                    is EditOperation.Delete -> {
                        table.delete(operation.position.offset, operation.position.offset + operation.length)
                    }
                    is EditOperation.Replace -> {
                        table.delete(operation.position.offset, operation.position.offset + operation.oldText.length)
                        table.insert(operation.position.offset, operation.newText)
                    }
                }
            }
            bumpStructuralVersion()
            val memory = entry.estimateMemoryBytes()
            currentRedoMemoryBytes -= memory
            undoStack.addLast(entry)
            currentUndoMemoryBytes += memory
            currentGeneration = entry.generationAfter
            refreshStats()
            updateModified()
            refreshUndoRedo()
            Result.success(entry.ops.last())
        } catch (e: Exception) {
            redoStack.addLast(entry)
            log(tag, ERROR) { "Redo failed - ${e.asLog()}" }
            Result.failure(e)
        }
    }

    /**
     * Ends the current typing run: the next coalescable edit starts a NEW undo entry. Called
     * by the engine on cursor jumps, selection changes, save, and undo/redo - anything that
     * semantically separates two runs of typing.
     */
    suspend fun breakUndoRun() = bufferMutex.withLock {
        breakUndoRunLocked()
    }

    private fun breakUndoRunLocked() {
        coalesceAnchor = null
        coalesceDeadline = null
    }

    fun canUndo(): Boolean = _canUndo.value

    fun canRedo(): Boolean = _canRedo.value

    private fun table(): PieceTable = tableOrNull() ?: throw IllegalStateException("Buffer not initialized")

    private fun tableOrNull(): PieceTable? {
        saveError?.let { throw IllegalStateException("Buffer requires reload after failed save", it) }
        return pieceTable
    }

    private suspend fun readOriginalBytes(physicalOffset: Long, byteLen: Int): ByteArray =
        try {
            dataSource.openByteSource(physicalOffset).buffer().use { it.readByteArray(byteLen.toLong()) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Backstop: an edit/read that needs original bytes hit a vanished/denied file. Latch
            // read-only so subsequent edits are refused up front instead of failing one-by-one.
            if (e.isBackingUnavailable()) latchBackingLost(e)
            throw e
        }

    /**
     * Classifies a read failure as "the backing file is gone / access lost" (vs a transient blip).
     * Deliberately narrow: latching is sticky until reopen, so a transient read error on a
     * still-present file must NOT lock the document read-only. Definitive signals only -
     * [PathPermissionDeniedException] and the not-found family by type, plus the characteristic
     * missing/unreadable messages the local & SAF layers wrap in a generic [ReadException]
     * (local "Does not exist…", SAF cause "readable=false", raw "ENOENT"). A dropped root/ADB
     * service ([ServiceConnectionLostException]) is transient and never latches, even when
     * re-wrapped; a generic [ReadException] with no not-found signal stays recoverable.
     */
    private fun Throwable.isBackingUnavailable(): Boolean {
        val chain = generateSequence(this) { it.cause }.take(16).toList()
        if (chain.any { it is ServiceConnectionLostException }) return false
        if (chain.any {
                it is PathPermissionDeniedException ||
                    it is MissingUriPermissionException ||
                    it is PathNotFoundException ||
                    it is FileNotFoundException ||
                    it is NoSuchFileException
            }
        ) {
            return true
        }
        return chain.any { link ->
            link.message?.let { m ->
                m.contains("does not exist", ignoreCase = true) ||
                    m.contains("no such file", ignoreCase = true) ||
                    m.contains("readable=false", ignoreCase = true) ||
                    m.contains("ENOENT", ignoreCase = true)
            } == true
        }
    }

    /** Idempotently latches the read-only backing-lost state and publishes it on [contentSource]. */
    private fun latchBackingLost(cause: Throwable) {
        if (_isBackingLost.value) return
        backingLostCause = cause
        _isBackingLost.value = true
        (_contentSource.value as? ContentSource.File)?.let { _contentSource.value = it.copy(isBackingLost = true) }
        log(tag, WARN) { "Backing file became unavailable, document is now read-only - ${cause.asLog()}" }
    }

    private suspend fun getTextForLineInternal(lineNumber: Long): Result<String> {
        if (lineNumber < 0 || lineNumber >= _totalLines.value) {
            return Result.failure(IndexOutOfBoundsException("Line number $lineNumber is out of bounds"))
        }
        return try {
            val table = table()
            val start = table.lineStartOffset(lineNumber)
            val end = lineContentEnd(table, lineNumber)
            Result.success(table.read(start, end))
        } catch (e: Exception) {
            log(tag, ERROR) { "Failed to get text for line $lineNumber - ${e.asLog()}" }
            Result.failure(e)
        }
    }

    /** End of the line's text, excluding its break chars. */
    private suspend fun lineContentEnd(table: PieceTable, line: Long): Long {
        if (line + 1 > table.totalLineBreaks) return table.totalCharLength
        val breakEnd = table.lineStartOffset(line + 1)
        val precedingTwo = table.read((breakEnd - 2).coerceAtLeast(0L), breakEnd)
        return if (precedingTwo.endsWith("\r\n")) breakEnd - 2 else breakEnd - 1
    }

    private fun commitNewEdit(operation: EditOperation, coalesce: Boolean = false) {
        if (coalesce && tryCoalesce(operation)) return
        commitNewEdit(listOf(operation))
        coalesceAnchor = if (coalesce) undoStack.peekLast() else null
        coalesceDeadline = if (coalesce) timeSource.markNow() + COALESCE_WINDOW else null
    }

    internal fun commitNewEdit(ops: List<EditOperation>) {
        _nonUndoableEditPending.value = false
        bumpStructuralVersion()
        val before = currentGeneration
        currentGeneration = ++generationCounter
        for (discarded in redoStack) {
            if (discarded.generationAfter == savedGeneration) savedGenerationValid = false
        }
        redoStack.clear()
        currentRedoMemoryBytes = 0L

        val entry = UndoEntry(ops, before, currentGeneration)
        undoStack.addLast(entry)
        currentUndoMemoryBytes += entry.estimateMemoryBytes()
        enforceUndoLimits()
        refreshStats()
        updateModified()
        refreshUndoRedo()
    }

    private fun enforceUndoLimits() {
        while ((undoStack.size > maxUndoStackSize || currentUndoMemoryBytes > maxUndoMemoryBytes) &&
            undoStack.size > 1
        ) {
            val evicted = undoStack.removeFirst()
            currentUndoMemoryBytes -= evicted.estimateMemoryBytes()
            if (evicted.generationBefore == savedGeneration) savedGenerationValid = false
            log(tag, VERBOSE) {
                "Evicted old undo operation (stack: ${undoStack.size}/$maxUndoStackSize, " +
                    "memory: $currentUndoMemoryBytes/$maxUndoMemoryBytes bytes)"
            }
        }
    }

    /**
     * Merges a keystroke edit into the current typing run's top entry. Runs merge only while
     * uninterrupted: same anchor entry, within [COALESCE_WINDOW] of the previous keystroke, no
     * line break, capped at [COALESCE_MAX_CHARS], and never across the saved checkpoint (merging
     * over it would make "undo back to saved" unreachable).
     */
    private fun tryCoalesce(operation: EditOperation): Boolean {
        val top = undoStack.peekLast() ?: return false
        if (top !== coalesceAnchor) return false
        if (coalesceDeadline?.hasPassedNow() != false) return false
        if (savedGeneration == top.generationAfter) return false
        val single = top.ops.singleOrNull() ?: return false

        val merged: EditOperation? = when {
            operation is EditOperation.Insert && single is EditOperation.Insert -> {
                val contiguous = single.position.offset + single.text.length == operation.position.offset
                // A line break (a lone '\r' included) ends a run on BOTH sides: it neither joins
                // the previous run nor accumulates the following one
                val clean = !operation.text.containsLineBreak() && !single.text.containsLineBreak()
                val withinCap = single.text.length + operation.text.length <= COALESCE_MAX_CHARS
                if (contiguous && clean && withinCap) {
                    EditOperation.Insert(single.position, single.text + operation.text)
                } else null
            }
            operation is EditOperation.Delete && single is EditOperation.Delete -> {
                // Backspace run: the new deletion ends exactly where the previous one started
                val contiguous = operation.position.offset + operation.length == single.position.offset
                val clean = !operation.deletedText.containsLineBreak() && !single.deletedText.containsLineBreak()
                val withinCap = single.deletedText.length + operation.deletedText.length <= COALESCE_MAX_CHARS
                if (contiguous && clean && withinCap) {
                    EditOperation.Delete(
                        operation.position,
                        single.length + operation.length,
                        operation.deletedText + single.deletedText,
                    )
                } else null
            }
            else -> null
        }
        if (merged == null) return false

        bumpStructuralVersion()
        currentGeneration = ++generationCounter
        currentUndoMemoryBytes -= top.estimateMemoryBytes()
        undoStack.pollLast()
        val replacement = UndoEntry(listOf(merged), top.generationBefore, currentGeneration)
        undoStack.addLast(replacement)
        currentUndoMemoryBytes += replacement.estimateMemoryBytes()
        enforceUndoLimits()
        coalesceAnchor = replacement
        coalesceDeadline = timeSource.markNow() + COALESCE_WINDOW
        refreshStats()
        updateModified()
        refreshUndoRedo()
        return true
    }

    /**
     * Line breaks are SEPARATORS, matching the piece table ([PieceTable.lineOfOffset] returns
     * [PieceTable.totalLineBreaks] at the document end): a document ending in a break has a
     * trailing empty line and it must be counted, or the cursor after pressing Enter at EOF has
     * no line to land on.
     */
    private fun refreshStats() {
        val table = pieceTable ?: return
        _totalLength.value = table.totalCharLength
        _totalLines.value = (table.totalLineBreaks + 1L).coerceAtLeast(1L)
    }

    private fun updateModified() {
        _isModified.value = !savedGenerationValid || currentGeneration != savedGeneration
    }

    private fun refreshUndoRedo() {
        _canUndo.value = undoStack.isNotEmpty()
        _canRedo.value = redoStack.isNotEmpty()
    }

    private fun insertEndPosition(position: TextPosition, text: String): TextPosition =
        endPositionOf(position, text, endOffset = position.offset + text.length)

    /**
     * Estimates the memory footprint of an EditOperation in bytes.
     * Strings in JVM use UTF-16 encoding (2 bytes per character).
     */
    private fun EditOperation.estimateMemoryBytes(): Long {
        val baseSize = 32L
        return when (this) {
            is EditOperation.Insert -> baseSize + (text.length * 2L)
            is EditOperation.Delete -> baseSize + (deletedText.length * 2L)
            is EditOperation.Replace -> baseSize + (oldText.length * 2L) + (newText.length * 2L)
        }
    }

    private fun UndoEntry.estimateMemoryBytes(): Long = ops.sumOf { it.estimateMemoryBytes() }

    /** One undo step; [ops] applied forward on redo, inverted in reverse on undo. */
    private data class UndoEntry(
        val ops: List<EditOperation>,
        val generationBefore: Long,
        val generationAfter: Long,
    )

    @AssistedFactory
    interface Factory {
        fun create(
            workspaceId: Workspace.Id,
            dataSource: EditorDataSource,
            @Assisted("maxUndoStackSize") maxUndoStackSize: Int = 100,
            maxUndoMemoryBytes: Long = 10_485_760,
            @Assisted("blockSize") blockSize: Int = BlockIndexBuilder.DEFAULT_BLOCK_SIZE,
            assertions: Boolean = false,
            staleSampleRandom: Random = Random.Default,
            timeSource: TimeSource = TimeSource.Monotonic,
            @Assisted("maxDisplayLineChars") maxDisplayLineChars: Int = MAX_DISPLAY_LINE_CHARS,
        ): DocumentBuffer
    }

    companion object {
        const val STALENESS_SAMPLE_COUNT = 8

        /**
         * Longest line prefix (UTF-16 chars) the display-read API returns per line. Lines are
         * capped at the buffer read level so a single-giant-line file can never flood the UI
         * pipeline (VSCode stopRenderingLineAfter precedent). Edits remain offset-exact against
         * the FULL line; only display reads are sliced.
         */
        const val MAX_DISPLAY_LINE_CHARS = 10_000

        /** Threshold floor: keeps cut (≤250K copy cap) undoable and tolerates a tiny undo budget. */
        const val MIN_UNDOABLE_EDIT_CHARS = 1_000_000L

        /** Threshold ceiling: bounds a recorded delete's materialization at ~100MB, under the Int cap. */
        const val MAX_UNDOABLE_EDIT_CHARS = 50_000_000L

        // Bounded working memory for the streaming line-ending conversion
        private const val CONVERT_CHUNK_BYTES = 64 * 1024
        val COALESCE_WINDOW = 800.milliseconds
        const val COALESCE_MAX_CHARS = 256
    }
}
