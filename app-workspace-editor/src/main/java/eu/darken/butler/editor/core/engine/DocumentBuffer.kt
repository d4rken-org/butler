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
import eu.darken.butler.editor.core.engine.text.BlockIndexBuilder
import eu.darken.butler.editor.core.engine.text.BlockOriginalDocument
import eu.darken.butler.editor.core.engine.text.PieceTable
import eu.darken.butler.editor.core.engine.text.WindowedSearch
import eu.darken.butler.editor.core.sources.EditorDataSource
import eu.darken.butler.workspace.core.Workspace
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okio.buffer
import okio.use
import java.nio.charset.Charset
import java.util.LinkedList

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
) {

    private val tag = logTag("Editor", "Workspace", workspaceId.shortTag, "Engine", "DocumentBuffer")

    private val _contentSource = MutableStateFlow<ContentSource>(ContentSource.Memory(size = 0L))
    val contentSource: StateFlow<ContentSource> = _contentSource.asStateFlow()

    private val _lineEnding = MutableStateFlow(LineEnding.LF)
    val lineEnding: StateFlow<LineEnding> = _lineEnding.asStateFlow()

    private val _totalLines = MutableStateFlow(0)
    val totalLines: StateFlow<Int> = _totalLines.asStateFlow()

    private val _totalLength = MutableStateFlow(0L)
    val totalLength: StateFlow<Long> = _totalLength.asStateFlow()

    private val _isModified = MutableStateFlow(false)
    val isModified: StateFlow<Boolean> = _isModified.asStateFlow()

    private val bufferMutex = Mutex()
    private var pieceTable: PieceTable? = null
    private var originalDocument: BlockOriginalDocument? = null
    private var detectedCharset: Charset = Charsets.UTF_8
    private var bomSize: Int = 0

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
            pieceTable = PieceTable.create(original, assertions)

            _lineEnding.value = index.lineEnding
            (_contentSource.value as? ContentSource.File)?.let {
                _contentSource.value = it.copy(lineEnding = index.lineEnding)
            }

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

            log(tag) { "Initialized (${_totalLength.value} chars, ${_totalLines.value} lines, ${index.lineEnding})" }
            Result.success(Unit)
        } catch (e: Exception) {
            log(tag, ERROR) { "Failed to initialize document buffer - ${e.asLog()}" }
            Result.failure(e)
        }
    }

    suspend fun release(): Result<Unit> = bufferMutex.withLock {
        try {
            if (_isModified.value) {
                // Parity with the old engine: flush on close, but never block closing on failure
                saveFileInternal().onFailure {
                    log(tag, WARN) { "Failed to flush changes on release - ${it.asLog()}" }
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

    suspend fun getTextForLine(lineNumber: Int): Result<String> = bufferMutex.withLock {
        getTextForLineInternal(lineNumber)
    }

    suspend fun getTextForRange(startLine: Int, endLine: Int): Result<String> = bufferMutex.withLock {
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
            table.insert(position.offset, text)
            commitNewEdit(EditOperation.Insert(position, text))

            val newPosition = TextPosition(
                offset = position.offset + text.length,
                line = position.line + text.count { it == '\n' },
                column = if (text.contains('\n')) {
                    text.length - text.lastIndexOf('\n') - 1
                } else {
                    position.column + text.length
                },
            )
            Result.success(newPosition)
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
                table.delete(startPosition.offset, endPosition.offset)
                commitNewEdit(
                    EditOperation.Delete(
                        startPosition,
                        (endPosition.offset - startPosition.offset).toInt(),
                        deletedText,
                    ),
                )
                Result.success(deletedText)
            } catch (e: Exception) {
                log(tag, ERROR) { "Failed to delete text from $startPosition to $endPosition - ${e.asLog()}" }
                Result.failure(e)
            }
        }

    suspend fun replaceText(
        startPosition: TextPosition,
        endPosition: TextPosition,
        newText: String,
    ): Result<TextPosition> {
        return try {
            deleteText(startPosition, endPosition).getOrThrow()
            insertText(startPosition, newText)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun findPosition(offset: Long): TextPosition = bufferMutex.withLock {
        val table = pieceTable ?: return@withLock TextPosition(offset, 0, 0)
        val clamped = offset.coerceIn(0L, table.totalCharLength)
        val line = table.lineOfOffset(clamped)
        val lineStart = table.lineStartOffset(line)
        TextPosition(offset, line.toInt(), (clamped - lineStart).toInt())
    }

    suspend fun findOffset(line: Int, column: Int): Long = bufferMutex.withLock {
        if (line < 0 || line >= _totalLines.value) {
            throw IndexOutOfBoundsException("Line $line is out of bounds (total lines: ${_totalLines.value})")
        }
        val table = table()
        val start = table.lineStartOffset(line.toLong())
        val end = lineContentEnd(table, line.toLong())
        start + column.coerceIn(0, (end - start).toInt())
    }

    suspend fun search(query: String, startFrom: TextPosition?, options: SearchOptions): List<SearchResult> =
        bufferMutex.withLock {
            val table = pieceTable ?: return@withLock emptyList()
            try {
                val windowedSearch = WindowedSearch { start, end -> table.read(start, end) }
                windowedSearch.search(table.totalCharLength, query, options).map { match ->
                    SearchResult(
                        position = TextPosition(match.offset, match.line, match.column),
                        matchText = match.matchText,
                        chunkId = DOC_CHUNK_ID,
                    )
                }
            } catch (e: Exception) {
                log(tag, ERROR) { "Search failed for query: $query - ${e.asLog()}" }
                emptyList()
            }
        }

    suspend fun saveFile(): Result<Unit> = bufferMutex.withLock {
        saveFileInternal()
    }

    private fun saveFileInternal(): Result<Unit> {
        if (!_isModified.value) {
            log(tag) { "No modifications to save" }
            return Result.success(Unit)
        }
        // Streaming splice save lands in editor-v3 phase 3
        return Result.failure(UnsupportedOperationException("Save is not implemented yet"))
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
            val memory = entry.operation.estimateMemoryBytes()
            currentUndoMemoryBytes -= memory
            redoStack.addLast(entry)
            currentRedoMemoryBytes += memory
            currentGeneration = entry.generationBefore
            refreshStats()
            updateModified()
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
            val memory = entry.operation.estimateMemoryBytes()
            currentRedoMemoryBytes -= memory
            undoStack.addLast(entry)
            currentUndoMemoryBytes += memory
            currentGeneration = entry.generationAfter
            refreshStats()
            updateModified()
            Result.success(entry.operation)
        } catch (e: Exception) {
            redoStack.addLast(entry)
            log(tag, ERROR) { "Redo failed - ${e.asLog()}" }
            Result.failure(e)
        }
    }

    fun canUndo(): Boolean = undoStack.isNotEmpty()

    fun canRedo(): Boolean = redoStack.isNotEmpty()

    private fun table(): PieceTable = pieceTable ?: throw IllegalStateException("Buffer not initialized")

    private suspend fun readOriginalBytes(physicalOffset: Long, byteLen: Int): ByteArray =
        dataSource.openByteSource(physicalOffset).buffer().use { it.readByteArray(byteLen.toLong()) }

    private suspend fun getTextForLineInternal(lineNumber: Int): Result<String> {
        if (lineNumber < 0 || lineNumber >= _totalLines.value) {
            return Result.failure(IndexOutOfBoundsException("Line number $lineNumber is out of bounds"))
        }
        return try {
            val table = table()
            val start = table.lineStartOffset(lineNumber.toLong())
            val end = lineContentEnd(table, lineNumber.toLong())
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
    }

    private fun refreshStats() {
        val table = pieceTable ?: return
        _totalLength.value = table.totalCharLength
        val lines = table.totalLineBreaks + (if (table.endsWithBreak) 0L else 1L)
        _totalLines.value = lines.coerceAtLeast(1L).toInt()
    }

    private fun updateModified() {
        _isModified.value = !savedGenerationValid || currentGeneration != savedGeneration
    }

    /**
     * Estimates the memory footprint of an EditOperation in bytes.
     * Strings in JVM use UTF-16 encoding (2 bytes per character).
     */
    private fun EditOperation.estimateMemoryBytes(): Long {
        val baseSize = 32L
        return when (this) {
            is EditOperation.Insert -> baseSize + (text.length * 2L)
            is EditOperation.Delete -> baseSize + (deletedText.length * 2L) + 4L
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
        ): DocumentBuffer
    }

    companion object {
        // SearchResult still carries a chunk id until the chunk engine is deleted
        private val DOC_CHUNK_ID = TextChunk.ChunkId("document")
    }
}
