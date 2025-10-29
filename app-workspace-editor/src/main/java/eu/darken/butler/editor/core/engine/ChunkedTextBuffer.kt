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
    private val lineIndex = mutableListOf<LineInfo>()
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

            // Build line index
            buildLineIndex()

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
        lineIndex.clear()
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
        if (lineNumber < 0 || lineNumber >= lineIndex.size) {
            return Result.failure(IndexOutOfBoundsException("Line number $lineNumber is out of bounds"))
        }

        val lineInfo = lineIndex[lineNumber]
        return getText(lineInfo.startOffset, lineInfo.endOffset)
    }

    suspend fun getTextForRange(startLine: Int, endLine: Int): Result<String> {
        if (startLine < 0 || endLine >= lineIndex.size || startLine > endLine) {
            return Result.failure(IndexOutOfBoundsException("Invalid line range: $startLine-$endLine"))
        }

        val startOffset = lineIndex[startLine].startOffset
        val endOffset = lineIndex[endLine].endOffset

        return getText(startOffset, endOffset)
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
        // Binary search through line index
        var left = 0
        var right = lineIndex.size - 1

        while (left <= right) {
            val mid = (left + right) / 2
            val lineInfo = lineIndex[mid]

            when {
                offset < lineInfo.startOffset -> right = mid - 1
                offset >= lineInfo.endOffset -> left = mid + 1
                else -> {
                    val column = (offset - lineInfo.startOffset).toInt()
                    return TextPosition(offset, mid, column)
                }
            }
        }

        // If not found, return position at end
        return TextPosition(offset, lineIndex.size - 1, 0)
    }

    suspend fun findOffset(line: Int, column: Int): Long {
        if (line < 0 || line >= lineIndex.size) {
            return _totalLength.value
        }

        val lineInfo = lineIndex[line]
        val maxColumn = (lineInfo.endOffset - lineInfo.startOffset).toInt()
        val clampedColumn = column.coerceIn(0, maxColumn)

        return lineInfo.startOffset + clampedColumn
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

    private suspend fun buildLineIndex() {
        lineIndex.clear()
        var currentOffset = 0L
        var currentLineNumber = 0

        // Build line index from ALL chunks
        if (chunkIds.isNotEmpty()) {
            log(tag) { "Building line index from ${chunkIds.size} chunks" }

            for ((chunkIndex, chunkId) in chunkIds.withIndex()) {
                val chunk = try {
                    chunkManager.getChunk(chunkId) ?: chunkManager.loadChunk(chunkId).getOrThrow()
                } catch (e: Exception) {
                    log(tag, ERROR) { "Failed to load chunk $chunkIndex for line indexing - ${e.asLog()}" }
                    throw e
                }

                log(tag) { "Processing chunk $chunkIndex/${chunkIds.size} with ${chunk.content.length} bytes (offset: $currentOffset)" }

                val linesBeforeChunk = lineIndex.size
                val isLastChunk = chunkIndex == chunkIds.size - 1
                buildLineIndexForChunk(chunk, currentLineNumber, currentOffset, isLastChunk)
                val linesInChunk = lineIndex.size - linesBeforeChunk

                currentLineNumber += linesInChunk
                currentOffset = chunk.endOffset

                log(tag) { "Chunk $chunkIndex added $linesInChunk lines (total: ${lineIndex.size})" }
            }
        }

        // Ensure we have at least one line for empty content
        if (lineIndex.isEmpty()) {
            log(tag) { "Line index is empty, creating fallback empty line entry" }
            lineIndex.add(
                LineInfo(
                    lineNumber = 0,
                    startOffset = 0L,
                    endOffset = 0L,
                    chunkId = chunkIds.firstOrNull() ?: TextChunk.ChunkId.generate(0)
                )
            )
        }

        _totalLines.value = lineIndex.size
        log(tag) { "Line index built with ${lineIndex.size} lines from ${chunkIds.size} chunks" }
    }

    private fun buildLineIndexForChunk(chunk: TextChunk, startLineNumber: Int, startOffset: Long, isLastChunk: Boolean) {
        var currentOffset = startOffset
        var lineStart = 0

        chunk.content.forEachIndexed { index, char ->
            if (char == '\n') {
                val lineEnd = index
                lineIndex.add(
                    LineInfo(
                        lineNumber = startLineNumber + lineIndex.size,
                        startOffset = currentOffset + lineStart,
                        endOffset = currentOffset + lineEnd,
                        chunkId = chunk.id
                    )
                )
                lineStart = index + 1
            }
        }

        // Only add final line if this is the last chunk AND it doesn't end with \n
        if (isLastChunk && chunk.content.isNotEmpty() && !chunk.content.endsWith('\n')) {
            lineIndex.add(
                LineInfo(
                    lineNumber = startLineNumber + lineIndex.size,
                    startOffset = currentOffset + lineStart,
                    endOffset = currentOffset + chunk.content.length,
                    chunkId = chunk.id
                )
            )
        }
    }

    private suspend fun findChunkForOffset(offset: Long): TextChunk? {
        return chunkManager.getChunksInRange(offset, offset + 1).firstOrNull()
    }

    private suspend fun updateAfterEdit() {
        _isModified.value = true
        // Rebuild line index - in a real implementation, this would be more efficient
        buildLineIndex()
    }

    private suspend fun evictChunksOutsideRange(startLine: Int, endLine: Int) {
        if (lineIndex.isEmpty()) return

        // Find which chunk IDs are needed for the given line range
        val neededChunkIds = mutableSetOf<TextChunk.ChunkId>()
        for (lineNum in startLine..minOf(endLine, lineIndex.size - 1)) {
            val lineInfo = lineIndex.getOrNull(lineNum)
            if (lineInfo != null) {
                neededChunkIds.add(lineInfo.chunkId)
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