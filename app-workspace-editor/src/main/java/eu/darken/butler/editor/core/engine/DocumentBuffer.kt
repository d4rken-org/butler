package eu.darken.butler.editor.core.engine

import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import eu.darken.butler.common.ca.toCaString
import eu.darken.butler.common.debug.logging.Logging.Priority.*
import eu.darken.butler.common.debug.logging.asLog
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.debug.logging.logTag
import eu.darken.butler.common.progress.Progress
import eu.darken.butler.editor.R
import eu.darken.butler.editor.core.engine.text.BlockIndex
import eu.darken.butler.editor.core.engine.text.BlockIndexBuilder
import eu.darken.butler.editor.core.engine.text.BlockOriginalDocument
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
import java.nio.CharBuffer
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest
import java.util.LinkedList
import kotlin.coroutines.coroutineContext
import kotlin.random.Random

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

    // Save checkpoint: every edit gets a monotonic generation; isModified compares against the
    // generation recorded at save time (undo back to the saved state clears the flag)
    private var generationCounter = 0L
    private var currentGeneration = 0L
    private var savedGeneration = 0L
    private var savedGenerationValid = true

    // Bumped on EVERY piece-table mutation (edits, undo/redo, rebase, initialize). Searches
    // validate it per window so a long scan aborts instead of holding the lock across the
    // whole document while typing queues behind it.
    private var structuralVersion = 0L

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
            structuralVersion++

            _lineEnding.value = index.lineEnding
            (_contentSource.value as? ContentSource.File)?.let {
                _contentSource.value = it.copy(lineEnding = index.lineEnding)
            }
            saveError = null
            lastKnownMeta = dataSource.getMeta()

            undoStack.clear()
            redoStack.clear()
            currentUndoMemoryBytes = 0L
            currentRedoMemoryBytes = 0L
            generationCounter = 0L
            currentGeneration = 0L
            savedGeneration = 0L
            savedGenerationValid = true
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

    suspend fun getFullText(): Result<String> = bufferMutex.withLock {
        try {
            Result.success(table().readAll())
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
            if (result.isNotEmpty()) result.append('\n')
            val line = getTextForLineInternal(lineNumber).getOrElse { return@withLock Result.failure(it) }
            result.append(line)
        }
        Result.success(result.toString())
    }

    suspend fun insertText(position: TextPosition, text: String): Result<TextPosition> = bufferMutex.withLock {
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
                commitNewEdit(EditOperation.Insert(position, text))
            }
            Result.success(insertEndPosition(position, text))
        } catch (e: Exception) {
            log(tag, ERROR) { "Failed to insert text at position: $position - ${e.asLog()}" }
            Result.failure(e)
        }
    }

    suspend fun deleteText(startPosition: TextPosition, endPosition: TextPosition): Result<String> =
        bufferMutex.withLock {
            try {
                val table = table()
                val deletedText = table.read(startPosition.offset, endPosition.offset)
                if (deletedText.isNotEmpty()) {
                    table.delete(startPosition.offset, endPosition.offset)
                    commitNewEdit(
                        EditOperation.Delete(
                            startPosition,
                            (endPosition.offset - startPosition.offset).toInt(),
                            deletedText,
                        ),
                    )
                }
                Result.success(deletedText)
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
        try {
            val table = table()
            val deletedText = table.read(startPosition.offset, endPosition.offset)
            if (deletedText.isNotEmpty()) {
                table.delete(startPosition.offset, endPosition.offset)
            }
            if (newText.isNotEmpty()) {
                try {
                    table.insert(startPosition.offset, newText)
                } catch (e: Exception) {
                    if (deletedText.isNotEmpty()) {
                        withContext(NonCancellable) { table.insert(startPosition.offset, deletedText) }
                    }
                    throw e
                }
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
        } catch (e: Exception) {
            log(tag, ERROR) { "Failed to replace text from $startPosition to $endPosition - ${e.asLog()}" }
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

    /**
     * Scans the whole document WITHOUT holding [bufferMutex] across the scan: the lock is taken
     * per window read, and [structuralVersion] is validated each time. A concurrent edit makes
     * the scan fail with [SearchInvalidatedException] (distinguishable from no-matches) instead
     * of stalling the edit for the scan's duration. Edits can still wait for at most one
     * window's decode (~64KB) - bounded and acceptable.
     */
    suspend fun search(query: String, options: SearchOptions): Result<List<SearchResult>> {
        // tableOrNull() throws when the buffer requires a reload - that must surface as a
        // Result too, the engine no longer wraps this call
        val (table, version, totalLength) = try {
            bufferMutex.withLock {
                val t = tableOrNull() ?: return Result.success(emptyList())
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
            val matches = windowedSearch.search(totalLength, query, options).map { match ->
                SearchResult(
                    position = TextPosition(match.offset, match.line, match.column),
                    matchText = match.matchText,
                )
            }
            Result.success(matches)
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

    suspend fun saveFile(): Result<Unit> = bufferMutex.withLock {
        saveFileInternal()
    }

    private suspend fun saveFileInternal(): Result<Unit> {
        return try {
            saveError?.let {
                return Result.failure(IllegalStateException("Buffer requires reload after failed save", it))
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

    private suspend fun writeSplice(context: EditorDataSource.CommitContext, table: PieceTable) {
        streamPieces(context.sink, table, context::openOriginalSource)
    }

    /**
     * Streams the current document content (including unsaved edits) to [sink] - byte-identical
     * to what saving would write. Runs under the buffer mutex, so no commit can move the
     * underlying file while originals are being read.
     */
    suspend fun writeContentTo(sink: BufferedSink): Result<Unit> = bufferMutex.withLock {
        try {
            streamPieces(sink, table()) { offset -> dataSource.openByteSource(offset) }
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log(tag, ERROR) { "Failed to stream content - ${e.asLog()}" }
            Result.failure(e)
        }
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
        structuralVersion++

        _lineEnding.value = index.lineEnding
        lastKnownMeta = dataSource.getMeta()
        val freshSource = dataSource.contentSource.value
        _contentSource.value = if (freshSource is ContentSource.File) {
            // Size/mtime from our own fresh lookup - the data source's post-commit refresh is best-effort
            freshSource.copy(
                lineEnding = index.lineEnding,
                size = lastKnownMeta?.size ?: freshSource.size,
                lastModified = lastKnownMeta?.modifiedAt ?: freshSource.lastModified,
            )
        } else {
            freshSource
        }
        savedGeneration = currentGeneration
        savedGenerationValid = true
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
        val current = dataSource.getMeta()
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

    suspend fun undo(): Result<EditOperation?> = bufferMutex.withLock {
        val entry = undoStack.pollLast() ?: return@withLock Result.success(null)
        try {
            val table = table()
            when (val operation = entry.operation) {
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
            structuralVersion++
            val memory = entry.operation.estimateMemoryBytes()
            currentUndoMemoryBytes -= memory
            redoStack.addLast(entry)
            currentRedoMemoryBytes += memory
            currentGeneration = entry.generationBefore
            refreshStats()
            updateModified()
            refreshUndoRedo()
            Result.success(entry.operation)
        } catch (e: Exception) {
            undoStack.addLast(entry)
            log(tag, ERROR) { "Undo failed - ${e.asLog()}" }
            Result.failure(e)
        }
    }

    suspend fun redo(): Result<EditOperation?> = bufferMutex.withLock {
        val entry = redoStack.pollLast() ?: return@withLock Result.success(null)
        try {
            val table = table()
            when (val operation = entry.operation) {
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
            structuralVersion++
            val memory = entry.operation.estimateMemoryBytes()
            currentRedoMemoryBytes -= memory
            undoStack.addLast(entry)
            currentUndoMemoryBytes += memory
            currentGeneration = entry.generationAfter
            refreshStats()
            updateModified()
            refreshUndoRedo()
            Result.success(entry.operation)
        } catch (e: Exception) {
            redoStack.addLast(entry)
            log(tag, ERROR) { "Redo failed - ${e.asLog()}" }
            Result.failure(e)
        }
    }

    fun canUndo(): Boolean = _canUndo.value

    fun canRedo(): Boolean = _canRedo.value

    private fun table(): PieceTable = tableOrNull() ?: throw IllegalStateException("Buffer not initialized")

    private fun tableOrNull(): PieceTable? {
        saveError?.let { throw IllegalStateException("Buffer requires reload after failed save", it) }
        return pieceTable
    }

    private suspend fun readOriginalBytes(physicalOffset: Long, byteLen: Int): ByteArray =
        dataSource.openByteSource(physicalOffset).buffer().use { it.readByteArray(byteLen.toLong()) }

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

    private fun commitNewEdit(operation: EditOperation) {
        structuralVersion++
        val before = currentGeneration
        currentGeneration = ++generationCounter
        for (discarded in redoStack) {
            if (discarded.generationAfter == savedGeneration) savedGenerationValid = false
        }
        redoStack.clear()
        currentRedoMemoryBytes = 0L

        undoStack.addLast(UndoEntry(operation, before, currentGeneration))
        currentUndoMemoryBytes += operation.estimateMemoryBytes()
        while ((undoStack.size > maxUndoStackSize || currentUndoMemoryBytes > maxUndoMemoryBytes) &&
            undoStack.size > 1
        ) {
            val evicted = undoStack.removeFirst()
            currentUndoMemoryBytes -= evicted.operation.estimateMemoryBytes()
            if (evicted.generationBefore == savedGeneration) savedGenerationValid = false
            log(tag, VERBOSE) {
                "Evicted old undo operation (stack: ${undoStack.size}/$maxUndoStackSize, " +
                    "memory: $currentUndoMemoryBytes/$maxUndoMemoryBytes bytes)"
            }
        }
        refreshStats()
        updateModified()
        refreshUndoRedo()
    }

    private fun refreshStats() {
        val table = pieceTable ?: return
        _totalLength.value = table.totalCharLength
        val lines = table.totalLineBreaks + (if (table.endsWithBreak) 0L else 1L)
        _totalLines.value = lines.coerceAtLeast(1L)
    }

    private fun updateModified() {
        _isModified.value = !savedGenerationValid || currentGeneration != savedGeneration
    }

    private fun refreshUndoRedo() {
        _canUndo.value = undoStack.isNotEmpty()
        _canRedo.value = redoStack.isNotEmpty()
    }

    private fun insertEndPosition(position: TextPosition, text: String): TextPosition = TextPosition(
        offset = position.offset + text.length,
        line = position.line + text.count { it == '\n' },
        column = if (text.contains('\n')) {
            text.length - text.lastIndexOf('\n') - 1
        } else {
            position.column + text.length
        },
    )

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

    private data class UndoEntry(
        val operation: EditOperation,
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
        ): DocumentBuffer
    }

    companion object {
        const val STALENESS_SAMPLE_COUNT = 8
    }
}
