package eu.darken.butler.editor.core.engine

import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import eu.darken.butler.common.debug.logging.Logging.Priority.*
import eu.darken.butler.common.debug.logging.asLog
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.debug.logging.logTag
import eu.darken.butler.common.files.APath
import eu.darken.butler.workspace.core.Workspace
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okio.Buffer
import okio.buffer
import okio.use
import java.util.LinkedList

class ChunkedTextBuffer @AssistedInject constructor(
    @Assisted private val workspaceId: Workspace.Id,
    @Assisted private val chunkManager: ChunkManager,
    @Assisted private val chunkRepository: ChunkRepository
) {

    private val tag = logTag("Editor", "Workspace", workspaceId.shortTag, "Engine", "ChunkedTextBuffer")

    private val _fileInfo = MutableStateFlow<FileInfo?>(null)
    val fileInfo: StateFlow<FileInfo?> = _fileInfo.asStateFlow()

    private val _totalLines = MutableStateFlow(0)
    val totalLines: StateFlow<Int> = _totalLines.asStateFlow()

    private val _totalLength = MutableStateFlow(0L)
    val totalLength: StateFlow<Long> = _totalLength.asStateFlow()

    private val _isModified = MutableStateFlow(false)
    val isModified: StateFlow<Boolean> = _isModified.asStateFlow()

    private val bufferMutex = Mutex()
    private val chunkMetadata = mutableListOf<ChunkMetadata>()
    private val undoStack = LinkedList<EditOperation>()
    private val redoStack = LinkedList<EditOperation>()

    private var chunkIds: List<TextChunk.ChunkId> = emptyList()

    suspend fun initialize(): Result<Unit> = bufferMutex.withLock {
        try {
            // Get info from data source (may be null for in-memory sources)
            val info = chunkRepository.getFileInfo()
            val size = if (info != null) {
                log(tag) { "Initializing text buffer with file: ${info.path}" }
                _fileInfo.value = info
                info.size
            } else {
                // In-memory content or uninitialized source
                log(tag) { "Initializing text buffer with in-memory content" }
                val contentSize = chunkRepository.dataSource.getSize()
                _totalLength.value = contentSize
                contentSize
            }

            _totalLength.value = size

            // Generate chunk IDs based on content size
            chunkIds = if (size > 0) {
                chunkManager.generateChunkIds(size)
            } else {
                // For empty content, create one empty chunk
                listOf(TextChunk.ChunkId.generate(0))
            }

            // For empty content, we need to create and load the empty chunk
            if (size == 0L && chunkIds.isNotEmpty()) {
                val emptyChunkId = chunkIds.first()
                val emptyChunk = TextChunk(
                    id = emptyChunkId,
                    startOffset = 0L,
                    endOffset = 0L,
                    content = "",
                    lineCount = 1,
                    isDirty = false,
                    isLoaded = true
                )
                chunkManager.addChunk(emptyChunk)
            }

            // Build chunk metadata via streaming scan
            buildChunkMetadata()

            // Evict chunks outside initial visible range to enable on-demand loading
            evictChunksOutsideRange(0, 50)

            log(tag) { "Successfully initialized text buffer (${size} bytes, ${_totalLines.value} lines)" }
            Result.success(Unit)

        } catch (e: Exception) {
            log(tag, ERROR) { "Failed to initialize text buffer - ${e.asLog()}" }
            Result.failure(e)
        }
    }

    suspend fun release(): Result<Unit> = bufferMutex.withLock {
        try {
            closeFileInternal()
            Result.success(Unit)
        } catch (e: Exception) {
            log(tag, ERROR) { "Failed to close file - ${e.asLog()}" }
            Result.failure(e)
        }
    }

    private suspend fun closeFileInternal() {
        // Save any dirty chunks
        if (_isModified.value) {
            chunkManager.saveAllDirtyChunks()
        }

        // Clear state
        chunkManager.clear()
        chunkRepository.closeFile()
        chunkMetadata.clear()
        chunkIds = emptyList()
        undoStack.clear()
        redoStack.clear()

        _fileInfo.value = null
        _totalLines.value = 0
        _totalLength.value = 0L
        _isModified.value = false
    }

    suspend fun getText(startOffset: Long, endOffset: Long): Result<String> {
        try {
            val chunks = chunkManager.getChunksInRange(startOffset, endOffset)
            val stringBuilder = StringBuilder()

            for (chunk in chunks) {
                // Load chunk if not loaded
                val loadedChunk = if (chunk.isLoaded) {
                    chunk
                } else {
                    chunkManager.loadChunk(chunk.id).getOrThrow()
                }

                // Calculate the portion of this chunk we need
                val chunkStartInRange = maxOf(startOffset, loadedChunk.startOffset)
                val chunkEndInRange = minOf(endOffset, loadedChunk.endOffset)

                val startInChunk = (chunkStartInRange - loadedChunk.startOffset).toInt()
                val endInChunk = (chunkEndInRange - loadedChunk.startOffset).toInt()

                stringBuilder.append(loadedChunk.content.substring(startInChunk, endInChunk))
            }

            return Result.success(stringBuilder.toString())

        } catch (e: Exception) {
            log(tag, ERROR) { "Failed to get text for range: $startOffset-$endOffset - ${e.asLog()}" }
            return Result.failure(e)
        }
    }

    suspend fun getTextForLine(lineNumber: Int): Result<String> {
        if (lineNumber < 0 || lineNumber >= _totalLines.value) {
            return Result.failure(IndexOutOfBoundsException("Line number $lineNumber is out of bounds"))
        }

        // Find which chunk contains the START of this line
        val startMetadata = findChunkMetadataForLine(lineNumber)
            ?: return Result.failure(IllegalStateException("Could not find chunk for line $lineNumber"))

        // Calculate line index within the starting chunk
        val lineIndexInChunk = lineNumber - startMetadata.firstLineNumber

        // Load the starting chunk
        val startChunk = chunkManager.loadChunk(startMetadata.chunkId).getOrElse { error ->
            return Result.failure(error)
        }

        // Find the start position of this line within the first chunk
        var lineStartPos = 0
        var linesScanned = 0
        while (lineStartPos < startChunk.content.length && linesScanned < lineIndexInChunk) {
            if (startChunk.content[lineStartPos] == '\n') {
                linesScanned++
            }
            lineStartPos++
        }

        // Scan across chunks to find the complete line (may span multiple chunks)
        val result = StringBuilder()
        var currentChunkIndex = chunkMetadata.indexOf(startMetadata)
        var currentPos = lineStartPos
        var currentChunk = startChunk

        while (true) {
            // Scan current chunk from currentPos looking for newline
            while (currentPos < currentChunk.content.length) {
                val char = currentChunk.content[currentPos]
                if (char == '\n') {
                    // Found end of line
                    return Result.success(result.toString())
                }
                result.append(char)
                currentPos++
            }

            // Reached end of chunk without finding newline
            currentChunkIndex++
            if (currentChunkIndex >= chunkMetadata.size) {
                // No more chunks, line ends at EOF
                return Result.success(result.toString())
            }

            // Load next chunk and continue scanning from its start
            val nextMetadata = chunkMetadata[currentChunkIndex]
            currentChunk = chunkManager.loadChunk(nextMetadata.chunkId).getOrElse { error ->
                return Result.failure(error)
            }
            currentPos = 0  // Reset position for next chunk
        }
    }

    suspend fun getTextForRange(startLine: Int, endLine: Int): Result<String> {
        if (startLine < 0 || endLine >= _totalLines.value || startLine > endLine) {
            return Result.failure(IndexOutOfBoundsException("Invalid line range: $startLine-$endLine"))
        }

        val result = StringBuilder()

        // Get each line in the range using getTextForLine() which handles multi-chunk lines
        for (lineNumber in startLine..endLine) {
            if (result.isNotEmpty()) {
                result.append('\n')
            }

            val line = getTextForLine(lineNumber).getOrElse { error ->
                return Result.failure(error)
            }
            result.append(line)
        }

        return Result.success(result.toString())
    }

    suspend fun insertText(position: TextPosition, text: String): Result<TextPosition> = bufferMutex.withLock {
        try {
            // Find the chunk containing this position
            val chunk = findChunkForOffset(position.offset)
                ?: return@withLock Result.failure(IllegalArgumentException("Position is out of bounds"))

            // Load the chunk if needed
            val loadedChunk = if (chunk.isLoaded) {
                chunk
            } else {
                chunkManager.loadChunk(chunk.id).getOrThrow()
            }

            // Calculate insertion point within chunk
            val insertionIndex = (position.offset - loadedChunk.startOffset).toInt()
            val newContent = loadedChunk.content.substring(0, insertionIndex) +
                text +
                loadedChunk.content.substring(insertionIndex)

            // Update the chunk
            val updatedChunk = loadedChunk.copy(
                content = newContent,
                endOffset = loadedChunk.endOffset + text.length,
                isDirty = true
            )

            chunkManager.updateChunk(chunk.id) { updatedChunk }

            // Update total length
            _totalLength.value = _totalLength.value + text.length

            // Update line index and state  
            updateAfterEdit()

            // Add to undo stack
            val operation = EditOperation.Insert(position, text)
            undoStack.addLast(operation)
            redoStack.clear()

            // Calculate new position
            val newPosition = TextPosition(
                offset = position.offset + text.length,
                line = position.line + text.count { it == '\n' },
                column = if (text.contains('\n')) {
                    text.length - text.lastIndexOf('\n') - 1
                } else {
                    position.column + text.length
                }
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
                // Get the text being deleted first
                val deletedText = getText(startPosition.offset, endPosition.offset).getOrThrow()

                // Find affected chunks
                val affectedChunks = chunkManager.getChunksInRange(startPosition.offset, endPosition.offset)

                // For simplicity, handle single chunk case first
                if (affectedChunks.size == 1) {
                    val chunk = affectedChunks.first()
                    val loadedChunk = if (chunk.isLoaded) {
                        chunk
                    } else {
                        chunkManager.loadChunk(chunk.id).getOrThrow()
                    }

                    val startInChunk = (startPosition.offset - loadedChunk.startOffset).toInt()
                    val endInChunk = (endPosition.offset - loadedChunk.startOffset).toInt()

                    val newContent = loadedChunk.content.removeRange(startInChunk, endInChunk)
                    val updatedChunk = loadedChunk.copy(
                        content = newContent,
                        endOffset = loadedChunk.endOffset - (endPosition.offset - startPosition.offset),
                        isDirty = true
                    )

                    chunkManager.updateChunk(chunk.id) { updatedChunk }
                }

                // Update line index and state
                updateAfterEdit()

                // Add to undo stack
                val operation = EditOperation.Delete(
                    startPosition,
                    (endPosition.offset - startPosition.offset).toInt(),
                    deletedText
                )
                undoStack.addLast(operation)
                redoStack.clear()

                Result.success(deletedText)

            } catch (e: Exception) {
                log(tag, ERROR) { "Failed to delete text from $startPosition to $endPosition - ${e.asLog()}" }
                Result.failure(e)
            }
        }

    suspend fun replaceText(
        startPosition: TextPosition,
        endPosition: TextPosition,
        newText: String
    ): Result<TextPosition> {
        return try {
            deleteText(startPosition, endPosition).getOrThrow()
            insertText(startPosition, newText)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun findPosition(offset: Long): TextPosition {
        // Find chunk containing this offset
        val chunkMeta = chunkMetadata.firstOrNull { meta ->
            offset >= meta.startOffset && offset < meta.endOffset
        } ?: chunkMetadata.lastOrNull() ?: return TextPosition(offset, 0, 0)

        // Load the chunk
        val chunk = chunkManager.loadChunk(chunkMeta.chunkId).getOrNull()
            ?: return TextPosition(offset, chunkMeta.firstLineNumber, 0)

        // Find line within chunk
        val offsetInChunk = (offset - chunkMeta.startOffset).toInt()
        var currentLine = 0
        var lineStartPos = 0

        for (i in 0 until offsetInChunk) {
            if (i < chunk.content.length && chunk.content[i] == '\n') {
                currentLine++
                lineStartPos = i + 1
            }
        }

        val globalLineNumber = chunkMeta.firstLineNumber + currentLine
        val column = offsetInChunk - lineStartPos

        return TextPosition(offset, globalLineNumber, column)
    }

    suspend fun findOffset(line: Int, column: Int): Long {
        if (line < 0 || line >= _totalLines.value) {
            return _totalLength.value
        }

        // Find chunk containing this line
        val chunkMeta = findChunkMetadataForLine(line)
            ?: return _totalLength.value

        // Load the chunk
        val chunk = chunkManager.loadChunk(chunkMeta.chunkId).getOrNull()
            ?: return chunkMeta.startOffset

        // Find the line within the chunk
        val lineIndexInChunk = line - chunkMeta.firstLineNumber
        val lines = chunk.content.split('\n')

        // Calculate offset to start of the line
        var offsetInChunk = 0
        for (i in 0 until lineIndexInChunk) {
            if (i < lines.size) {
                offsetInChunk += lines[i].length + 1 // +1 for newline
            }
        }

        // Add column offset
        val lineContent = lines.getOrNull(lineIndexInChunk) ?: ""
        val clampedColumn = column.coerceIn(0, lineContent.length)
        offsetInChunk += clampedColumn

        return chunkMeta.startOffset + offsetInChunk
    }

    suspend fun search(query: String, startFrom: TextPosition?, ignoreCase: Boolean): List<SearchResult> {
        val results = mutableListOf<SearchResult>()

        for (chunkId in chunkIds) {
            val chunkResults = chunkRepository.searchInChunk(chunkId, query, ignoreCase)
            results.addAll(chunkResults)
        }

        return results.sortedBy { it.position.offset }
    }

    suspend fun saveFile(): Result<Unit> {
        return try {
            // saveAllDirtyChunks() now handles complete save flow (get dirty, save, mark clean)
            val result = chunkManager.saveAllDirtyChunks()
            if (result.isSuccess) {
                _isModified.value = false
            }
            result
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun saveFileAs(filePath: APath<*>): Result<Unit> {
        // This would require implementing file copying with chunks
        return Result.failure(UnsupportedOperationException("Save As not implemented yet"))
    }

    suspend fun undo(): Result<EditOperation?> = bufferMutex.withLock {
        if (undoStack.isEmpty()) {
            return@withLock Result.success(null)
        }

        val operation = undoStack.removeLast()
        redoStack.addLast(operation)

        // Apply reverse operation
        when (operation) {
            is EditOperation.Insert -> {
                val endPosition = TextPosition(
                    operation.position.offset + operation.text.length,
                    operation.position.line,
                    operation.position.column
                )
                deleteText(operation.position, endPosition)
            }
            is EditOperation.Delete -> {
                insertText(operation.position, operation.deletedText)
            }
            is EditOperation.Replace -> {
                replaceText(
                    operation.position,
                    TextPosition(
                        operation.position.offset + operation.newText.length,
                        operation.position.line,
                        operation.position.column
                    ),
                    operation.oldText
                )
            }
        }

        Result.success(operation)
    }

    suspend fun redo(): Result<EditOperation?> = bufferMutex.withLock {
        if (redoStack.isEmpty()) {
            return@withLock Result.success(null)
        }

        val operation = redoStack.removeLast()
        undoStack.addLast(operation)

        // Re-apply operation
        when (operation) {
            is EditOperation.Insert -> {
                insertText(operation.position, operation.text)
            }
            is EditOperation.Delete -> {
                val endPosition = TextPosition(
                    operation.position.offset + operation.length,
                    operation.position.line,
                    operation.position.column
                )
                deleteText(operation.position, endPosition)
            }
            is EditOperation.Replace -> {
                replaceText(
                    operation.position,
                    TextPosition(
                        operation.position.offset + operation.oldText.length,
                        operation.position.line,
                        operation.position.column
                    ),
                    operation.newText
                )
            }
        }

        Result.success(operation)
    }

    fun canUndo(): Boolean = undoStack.isNotEmpty()

    fun canRedo(): Boolean = redoStack.isNotEmpty()

    private suspend fun buildChunkMetadata() {
        chunkMetadata.clear()
        var totalLines = 0

        val fileSize = _totalLength.value

        // Handle empty files
        if (fileSize == 0L) {
            chunkMetadata.add(
                ChunkMetadata(
                    chunkId = chunkIds.firstOrNull() ?: TextChunk.ChunkId.generate(0),
                    startOffset = 0L,
                    endOffset = 0L,
                    lineCount = 1,
                    firstLineNumber = 0
                )
            )
            _totalLines.value = 1
            log(tag) { "Built metadata for empty file (1 line)" }
            return
        }

        val startTime = System.currentTimeMillis()
        log(tag) { "Building chunk metadata for ${chunkIds.size} chunks via streaming with complete reads" }

        // Stream through file using pre-generated chunkIds to define boundaries
        // This time with proper looping to ensure complete reads
        chunkRepository.dataSource.openSource().use { source ->
            source.buffer().use { buffered ->
                for ((index, chunkId) in chunkIds.withIndex()) {
                    val chunkStart = extractOffsetFromChunkId(chunkId)
                    val chunkEnd = if (index < chunkIds.size - 1) {
                        extractOffsetFromChunkId(chunkIds[index + 1])
                    } else {
                        fileSize
                    }
                    val chunkSize = chunkEnd - chunkStart

                    // Read this chunk's data - loop until we get the full chunk size
                    // (mimics FileDataSource.readChunk() logic to handle partial reads)
                    val buffer = Buffer()
                    var totalBytesRead = 0L
                    while (totalBytesRead < chunkSize && !buffered.exhausted()) {
                        val remainingBytes = chunkSize - totalBytesRead
                        val bytesRead = buffered.read(buffer, remainingBytes)
                        if (bytesRead == -1L) break
                        totalBytesRead += bytesRead
                    }

                    // Count newlines in the complete chunk data
                    // Add 1 only for last chunk if it ends without newline
                    val content = buffer.readByteArray()
                    val isLastChunk = index == chunkIds.size - 1
                    val lineCount = content.count { it == '\n'.code.toByte() } +
                        if (isLastChunk && content.isNotEmpty() && content.last() != '\n'.code.toByte()) 1 else 0

                    chunkMetadata.add(
                        ChunkMetadata(
                            chunkId = chunkId,
                            startOffset = chunkStart,
                            endOffset = chunkEnd,
                            lineCount = lineCount,
                            firstLineNumber = totalLines
                        )
                    )

                    totalLines += lineCount

                    // Progress logging every 500 chunks
                    if ((index + 1) % 500 == 0) {
                        val elapsed = System.currentTimeMillis() - startTime
                        log(tag) { "Metadata progress: ${index + 1}/${chunkIds.size} chunks, $totalLines lines so far (${elapsed}ms)" }
                    }
                }
            }
        }

        // Ensure at least one line
        if (totalLines == 0) totalLines = 1

        val totalTime = System.currentTimeMillis() - startTime
        _totalLines.value = totalLines
        log(tag) { "Built ${chunkMetadata.size} metadata entries with $totalLines lines in ${totalTime}ms" }
    }

    private suspend fun findChunkForOffset(offset: Long): TextChunk? {
        return chunkManager.getChunksInRange(offset, offset + 1).firstOrNull()
    }

    /**
     * Extract offset from chunk ID.
     * ChunkIds are formatted as "chunk_<offset>", e.g. "chunk_0", "chunk_65536"
     */
    private fun extractOffsetFromChunkId(chunkId: TextChunk.ChunkId): Long {
        return chunkId.value.removePrefix("chunk_").toLongOrNull() ?: 0L
    }

    /**
     * Find chunk metadata that contains the START of the given line number.
     *
     * Line N starts after the (N-1)th newline (or at offset 0 for line 0).
     * We need to find the chunk where:
     * 1. For line 0: The first chunk (firstLineNumber == 0)
     * 2. For line N: The chunk that completes line N-1, which is where line N begins
     */
    private fun findChunkMetadataForLine(lineNumber: Int): ChunkMetadata? {
        if (chunkMetadata.isEmpty() || lineNumber < 0) return null

        for (index in chunkMetadata.indices) {
            val metadata = chunkMetadata[index]
            val nextMetadata = chunkMetadata.getOrNull(index + 1)

            // If this chunk's firstLineNumber equals our line number, this is the start
            // (handles lines that start at chunk boundaries or span from previous chunks)
            if (metadata.firstLineNumber == lineNumber) {
                return metadata
            }

            // If this chunk contains the completion of the previous line (N-1),
            // then line N starts in this chunk (after the newline)
            val linesCompletedInChunk = metadata.firstLineNumber + metadata.lineCount
            if (metadata.firstLineNumber < lineNumber && linesCompletedInChunk >= lineNumber) {
                return metadata
            }

            // If the next chunk's firstLineNumber is past our line, we must be in this chunk
            if (nextMetadata != null && nextMetadata.firstLineNumber > lineNumber) {
                return metadata
            }
        }

        // Line number is at or beyond all chunks, return last chunk
        return chunkMetadata.lastOrNull()
    }

    private suspend fun updateAfterEdit() {
        _isModified.value = true
        // Rebuild chunk metadata - in a real implementation, this would be more efficient
        buildChunkMetadata()
    }

    private suspend fun evictChunksOutsideRange(startLine: Int, endLine: Int) {
        if (chunkMetadata.isEmpty()) return

        // Find which chunks are needed for the given line range
        val neededChunkIds = mutableSetOf<TextChunk.ChunkId>()

        for (metadata in chunkMetadata) {
            val chunkLastLine = if (metadata == chunkMetadata.last()) {
                _totalLines.value - 1
            } else {
                val nextMetadata = chunkMetadata[chunkMetadata.indexOf(metadata) + 1]
                nextMetadata.firstLineNumber - 1
            }

            // Check if this chunk overlaps with the line range
            val overlaps = !(chunkLastLine < startLine || metadata.firstLineNumber > endLine)
            if (overlaps) {
                neededChunkIds.add(metadata.chunkId)
            }
        }

        // Evict all other chunks
        val allChunkIds = chunkIds.toSet()
        val chunksToEvict = allChunkIds - neededChunkIds

        if (chunksToEvict.isNotEmpty()) {
            log(tag) {
                "Evicting ${chunksToEvict.size} chunks outside range $startLine..$endLine, " +
                        "keeping ${neededChunkIds.size} chunks in memory"
            }

            for (chunkId in chunksToEvict) {
                chunkManager.evictChunk(chunkId)
            }
        }
    }

    @AssistedFactory
    interface Factory {
        fun create(
            workspaceId: Workspace.Id,
            chunkManager: ChunkManager,
            chunkRepository: ChunkRepository
        ): ChunkedTextBuffer
    }
}

data class LineInfo(
    val lineNumber: Int,
    val startOffset: Long,
    val endOffset: Long,
    val chunkId: TextChunk.ChunkId
)

data class ChunkMetadata(
    val chunkId: TextChunk.ChunkId,
    val startOffset: Long,
    val endOffset: Long,
    val lineCount: Int,
    val firstLineNumber: Int
)