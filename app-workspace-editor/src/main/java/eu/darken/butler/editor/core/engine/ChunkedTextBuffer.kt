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
    @Assisted private val chunkRepository: ChunkRepository,
    @Assisted private val maxUndoStackSize: Int = 100,
    @Assisted private val maxUndoMemoryBytes: Long = 10_485_760, // 10 MB
) {

    private val tag = logTag("Editor", "Workspace", workspaceId.shortTag, "Engine", "ChunkedTextBuffer")

    private val _fileInfo = MutableStateFlow<FileInfo?>(null)
    val fileInfo: StateFlow<FileInfo?> = _fileInfo.asStateFlow()

    private val _lineEnding = MutableStateFlow(LineEnding.LF)
    val lineEnding: StateFlow<LineEnding> = _lineEnding.asStateFlow()

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
    private var isUndoRedoInProgress = false
    private var currentUndoMemoryBytes: Long = 0
    private var currentRedoMemoryBytes: Long = 0

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

            // Generate chunk IDs based on content size (handles empty files too)
            chunkIds = chunkManager.generateChunkIds(size)

            // For empty content, we need to create and load the empty chunk
            if (size == 0L && chunkIds.isNotEmpty()) {
                val emptyChunkId = chunkIds.first()
                val emptyChunk = TextChunk(
                    id = emptyChunkId,
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

                // Get boundary for this chunk
                val boundary = chunkManager.getBoundary(chunk.id)
                    ?: return Result.failure(IllegalStateException("No boundary for chunk ${chunk.id}"))

                // Calculate the portion of this chunk we need
                val chunkStartInRange = maxOf(startOffset, boundary.startOffset)
                val chunkEndInRange = minOf(endOffset, boundary.endOffset)

                val startInChunk = (chunkStartInRange - boundary.startOffset).toInt()
                val endInChunk = (chunkEndInRange - boundary.startOffset).toInt()

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
                    // Found end of line - strip trailing \r for CRLF
                    val lineText = result.toString()
                    val strippedText = if (lineText.endsWith('\r')) {
                        lineText.dropLast(1)
                    } else {
                        lineText
                    }
                    return Result.success(strippedText)
                }
                result.append(char)
                currentPos++
            }

            // Reached end of chunk without finding newline
            currentChunkIndex++
            if (currentChunkIndex >= chunkMetadata.size) {
                // No more chunks, line ends at EOF - strip trailing \r if present
                val lineText = result.toString()
                val strippedText = if (lineText.endsWith('\r')) {
                    lineText.dropLast(1)
                } else {
                    lineText
                }
                return Result.success(strippedText)
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
            if (chunk == null) {
                log(tag, ERROR) {
                    "insertText: No chunk found for offset ${position.offset}, totalLength=${_totalLength.value}, chunkIds=${chunkIds.map { it.value }}"
                }
                return@withLock Result.failure(IllegalArgumentException("Position is out of bounds"))
            }

            // Pin the chunk to prevent eviction during this operation
            val pinResult = chunkManager.withPinnedChunk(chunk.id) { pinnedChunk ->
                // Get boundary for offset calculation
                val boundary = chunkManager.getBoundary(chunk.id)
                    ?: throw IllegalStateException("No boundary for chunk ${chunk.id}")

                // Calculate insertion point within chunk
                val insertionIndex = (position.offset - boundary.startOffset).toInt()
                val newContent = pinnedChunk.content.substring(0, insertionIndex) +
                    text +
                    pinnedChunk.content.substring(insertionIndex)

                // Update the chunk - transform the CURRENT chunk in cache (preserves refCount)
                chunkManager.updateChunk(chunk.id) { currentChunk ->
                    currentChunk.copy(
                        content = newContent,
                        isDirty = true
                    )
                } ?: throw IllegalStateException("Failed to update chunk ${chunk.id}")
            }

            if (pinResult.isFailure) {
                return@withLock Result.failure(
                    pinResult.exceptionOrNull() ?: Exception("Failed to pin chunk for insertion")
                )
            }

            // Update total length
            _totalLength.value = _totalLength.value + text.length

            // Update chunk boundaries FIRST to reflect the insertion
            // This must happen BEFORE buildChunkMetadata() so metadata uses updated boundaries
            val deltaLines = text.count { it == '\n' }
            chunkManager.updateBoundaries(position.offset, text.length.toLong(), deltaLines)

            // Update line index and state (AFTER boundary update to use correct boundaries)
            updateAfterEdit()

            // Add to undo stack (unless we're undoing/redoing)
            if (!isUndoRedoInProgress) {
                val operation = EditOperation.Insert(position, text)
                val opMemory = operation.estimateMemoryBytes()

                undoStack.addLast(operation)
                currentUndoMemoryBytes += opMemory

                // Evict oldest operations if limits exceeded
                while ((undoStack.size > maxUndoStackSize || currentUndoMemoryBytes > maxUndoMemoryBytes)
                       && undoStack.size > 1) {  // Keep at least one operation
                    val evicted = undoStack.removeFirst()
                    currentUndoMemoryBytes -= evicted.estimateMemoryBytes()
                    log(tag, DEBUG) {
                        "Evicted old undo operation (stack: ${undoStack.size}/${maxUndoStackSize}, " +
                        "memory: ${currentUndoMemoryBytes}/${maxUndoMemoryBytes} bytes)"
                    }
                }

                // Clear redo stack and reset its memory counter
                redoStack.clear()
                currentRedoMemoryBytes = 0
            }

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
                val affectedChunkIds = affectedChunks.map { it.id }.toSet()

                // Track merged chunk info for boundary fix (needed outside pinning block)
                var mergedChunkId: TextChunk.ChunkId? = null
                var mergedChunkSize: Long = 0

                // Pin all affected chunks to prevent eviction during this operation
                // Lambda returns the set of chunk IDs that need to be evicted after unpinning
                val pinResult = chunkManager.withPinnedChunks(affectedChunkIds) { pinnedChunks ->
                    // Handle single chunk and multi-chunk deletions
                    if (pinnedChunks.size == 1) {
                        // Single chunk deletion
                        val chunk = pinnedChunks.first()

                        // Get boundary for offset calculation
                        val boundary = chunkManager.getBoundary(chunk.id)
                            ?: throw IllegalStateException("No boundary for chunk ${chunk.id}")

                        val startInChunk = (startPosition.offset - boundary.startOffset).toInt()
                        val endInChunk = (endPosition.offset - boundary.startOffset).toInt()

                        val newContent = chunk.content.removeRange(startInChunk, endInChunk)

                        // Update the chunk - transform the CURRENT chunk in cache (preserves refCount)
                        chunkManager.updateChunk(chunk.id) { currentChunk ->
                            currentChunk.copy(
                                content = newContent,
                                isDirty = true
                            )
                        } ?: throw IllegalStateException("Failed to update chunk ${chunk.id}")

                        // Single chunk: nothing to evict
                        emptySet<TextChunk.ChunkId>()
                    } else {
                        // Multi-chunk deletion: merge content from first and last chunks
                        val firstChunk = pinnedChunks.first()
                        val lastChunk = pinnedChunks.last()

                        // Get boundaries for offset calculation
                        val firstBoundary = chunkManager.getBoundary(firstChunk.id)
                            ?: throw IllegalStateException("No boundary for chunk ${firstChunk.id}")
                        val lastBoundary = chunkManager.getBoundary(lastChunk.id)
                            ?: throw IllegalStateException("No boundary for chunk ${lastChunk.id}")

                        // Calculate what to keep from each chunk
                        val startInFirstChunk = (startPosition.offset - firstBoundary.startOffset).toInt()
                        val endInLastChunk = (endPosition.offset - lastBoundary.startOffset).toInt()

                        // Build merged content: keep beginning of first chunk + end of last chunk
                        val contentBeforeDelete = firstChunk.content.substring(0, startInFirstChunk)
                        val contentAfterDelete = lastChunk.content.substring(endInLastChunk)
                        val mergedContent = contentBeforeDelete + contentAfterDelete

                        // Update first chunk with merged content - transform the CURRENT chunk (preserves refCount)
                        chunkManager.updateChunk(firstChunk.id) { currentChunk ->
                            currentChunk.copy(
                                content = mergedContent,
                                isDirty = true
                            )
                        } ?: throw IllegalStateException("Failed to update chunk ${firstChunk.id}")

                        // Save info for boundary fix (must happen AFTER updateBoundaries call)
                        mergedChunkId = firstChunk.id
                        mergedChunkSize = mergedContent.length.toLong()

                        // CRITICAL: Mark all chunks AFTER the merge point as dirty
                        // Even though their content hasn't changed, their logical offsets have moved.
                        // Until the file is saved, these chunks can't be reloaded from disk because
                        // the disk still has the old structure (boundaries don't match file offsets).
                        val allChunkIds = chunkManager.getAllChunkIds()
                        val chunksAfterMerge = allChunkIds
                            .mapNotNull { id -> chunkManager.getBoundary(id)?.let { id to it } }
                            .filter { (id, boundary) -> boundary.startOffset > firstBoundary.startOffset && id !in pinnedChunks.map { it.id } }
                            .map { it.first }

                        // Load and mark all chunks after merge as dirty
                        // These chunks' logical offsets have shifted, so they can't be reloaded from disk
                        // at their boundary offsets until the file is saved with the new structure
                        for (chunkId in chunksAfterMerge) {
                            // Load chunk if not in cache
                            val chunk = chunkManager.getChunk(chunkId)
                                ?: chunkManager.loadChunk(chunkId).getOrNull()

                            if (chunk == null) {
                                log(tag, WARN) { "Failed to load chunk $chunkId for marking as dirty" }
                                continue
                            }

                            // Mark as dirty to prevent eviction/reload
                            chunkManager.updateChunk(chunkId) { it.copy(isDirty = true) }
                        }

                        log(tag, DEBUG) {
                            "Multi-chunk delete: merged ${pinnedChunks.size} chunks, marked ${chunksAfterMerge.size} chunks as dirty"
                        }

                        // Return all chunks except the first one (which has the merged content)
                        // These will be evicted AFTER unpinning
                        pinnedChunks.drop(1).map { it.id }.toSet()
                    }
                }

                if (pinResult.isFailure) {
                    return@withLock Result.failure(
                        pinResult.exceptionOrNull() ?: Exception("Failed to pin chunks for deletion")
                    )
                }

                // Now evict the chunks that need to be removed (they're unpinned now)
                // Use removeFromStructure=true because these chunks no longer exist in the logical file
                val chunksToEvict = pinResult.getOrThrow()
                if (chunksToEvict.isNotEmpty()) {
                    for (chunkId in chunksToEvict) {
                        val evicted = chunkManager.evictChunk(chunkId, removeFromStructure = true)
                        if (!evicted) {
                            log(tag, WARN) { "Failed to evict chunk $chunkId after deletion" }
                        }
                    }

                        // Update chunkIds list to remove evicted chunks
                    chunkIds = chunkIds.filterNot { it in chunksToEvict }

                    log(tag, DEBUG) { "Evicted ${chunksToEvict.size} chunks after multi-chunk delete" }
                }

                // Update total length
                val deletedLength = endPosition.offset - startPosition.offset
                _totalLength.value = _totalLength.value - deletedLength

                // Update chunk boundaries FIRST to reflect the deletion
                // This must happen BEFORE buildChunkMetadata() so metadata uses updated boundaries
                val deltaLines = -deletedText.count { it == '\n' }
                chunkManager.updateBoundaries(startPosition.offset, -deletedLength, deltaLines)

                // CRITICAL FIX: For multi-chunk delete, the merged chunk's boundary was destroyed by updateBoundaries()
                // We need to fix it to reflect the actual merged content size
                if (mergedChunkId != null) {
                    val mergedBoundary = chunkManager.getBoundary(mergedChunkId!!)
                    if (mergedBoundary != null) {
                        val correctedBoundary = ChunkBoundary(
                            startOffset = mergedBoundary.startOffset,
                            endOffset = mergedBoundary.startOffset + mergedChunkSize,
                            lineCount = mergedBoundary.lineCount
                        )
                        chunkManager.updateBoundary(mergedChunkId!!, correctedBoundary)
                        log(tag, DEBUG) {
                            "Fixed merged chunk boundary: ${mergedChunkId!!.value} from [${mergedBoundary.startOffset}, ${mergedBoundary.endOffset}) to [${correctedBoundary.startOffset}, ${correctedBoundary.endOffset})"
                        }
                    }
                }

                // Update line index and state (AFTER boundary update to use correct boundaries)
                updateAfterEdit()

                // Add to undo stack (unless we're undoing/redoing)
                if (!isUndoRedoInProgress) {
                    val operation = EditOperation.Delete(
                        startPosition,
                        (endPosition.offset - startPosition.offset).toInt(),
                        deletedText
                    )
                    val opMemory = operation.estimateMemoryBytes()

                    undoStack.addLast(operation)
                    currentUndoMemoryBytes += opMemory

                    // Evict oldest operations if limits exceeded
                    while ((undoStack.size > maxUndoStackSize || currentUndoMemoryBytes > maxUndoMemoryBytes)
                           && undoStack.size > 1) {  // Keep at least one operation
                        val evicted = undoStack.removeFirst()
                        currentUndoMemoryBytes -= evicted.estimateMemoryBytes()
                        log(tag, DEBUG) {
                            "Evicted old undo operation (stack: ${undoStack.size}/${maxUndoStackSize}, " +
                            "memory: ${currentUndoMemoryBytes}/${maxUndoMemoryBytes} bytes)"
                        }
                    }

                    // Clear redo stack and reset its memory counter
                    redoStack.clear()
                    currentRedoMemoryBytes = 0
                }

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

        // Calculate column: if no newline found in this chunk, line may have started in previous chunk
        val column = if (currentLine == 0 && chunkMeta.startOffset > 0) {
            // Line started in previous chunk - need to find actual line start
            val lineStartOffset = findLineStartOffset(offset)
            (offset - lineStartOffset).toInt()
        } else {
            // Line started in this chunk
            offsetInChunk - lineStartPos
        }

        return TextPosition(offset, globalLineNumber, column)
    }

    private suspend fun findLineStartOffset(offset: Long): Long {
        // Scan backwards from offset to find the last newline (or file start)
        var scanOffset = offset - 1
        while (scanOffset >= 0) {
            val text = getText(scanOffset, scanOffset + 1).getOrNull() ?: break
            if (text == "\n") {
                return scanOffset + 1
            }
            scanOffset--
        }
        return 0 // Line starts at file beginning
    }

    suspend fun findOffset(line: Int, column: Int): Long {
        if (line < 0 || line >= _totalLines.value) {
            throw IndexOutOfBoundsException("Line $line is out of bounds (total lines: ${_totalLines.value})")
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
        // Handle empty query early
        if (query.isEmpty()) {
            return emptyList()
        }

        val results = mutableListOf<SearchResult>()

        // Build metadata map for line number corrections
        val metadataMap = chunkMetadata.associateBy { it.chunkId }

        for (chunkId in chunkIds) {
            // Load chunk first (needed for boundary-based loading)
            val chunk = chunkManager.loadChunk(chunkId).getOrNull()
            if (chunk == null) {
                log(tag, WARN) { "Failed to load chunk $chunkId for search" }
                continue
            }

            // Get boundary for absolute offset calculation
            val boundary = chunkManager.getBoundary(chunkId)
            if (boundary == null) {
                log(tag, WARN) { "No boundary found for chunk $chunkId" }
                continue
            }

            val chunkResults = chunkRepository.searchInChunk(chunk, boundary, query, ignoreCase)

            // Correct line numbers from chunk-relative to file-relative
            val metadata = metadataMap[chunkId]
            if (metadata != null) {
                val correctedResults = chunkResults.map { result ->
                    result.copy(
                        position = result.position.copy(
                            line = metadata.firstLineNumber + result.position.line
                        )
                    )
                }
                results.addAll(correctedResults)
            } else {
                // Fallback: add results as-is if metadata not found
                results.addAll(chunkResults)
            }
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

    suspend fun undo(): Result<EditOperation?> {
        // Get operation from stack (protected by mutex)
        val operation = bufferMutex.withLock {
            if (undoStack.isEmpty()) {
                return Result.success(null)
            }
            val op = undoStack.removeLast()
            val opMemory = op.estimateMemoryBytes()

            // Update memory counters when moving operation between stacks
            currentUndoMemoryBytes -= opMemory
            redoStack.addLast(op)
            currentRedoMemoryBytes += opMemory

            op
        }

        // Set flag to prevent adding new undo operations
        isUndoRedoInProgress = true
        try {
            // Apply reverse operation OUTSIDE mutex to avoid deadlock
            // (insertText/deleteText/replaceText acquire their own mutex)
            when (operation) {
                is EditOperation.Insert -> {
                    log(tag, DEBUG) { "Undoing insert: deleting ${operation.text.length} bytes at offset ${operation.position.offset}" }
                    val endPosition = TextPosition(
                        operation.position.offset + operation.text.length,
                        operation.position.line,
                        operation.position.column
                    )
                    deleteText(operation.position, endPosition)
                }
                is EditOperation.Delete -> {
                    log(tag, DEBUG) { "Undoing delete: inserting ${operation.deletedText.length} bytes at offset ${operation.position.offset}" }
                    val result = insertText(operation.position, operation.deletedText)
                    log(tag, DEBUG) { "Undo insert result: ${if (result.isSuccess) "SUCCESS" else "FAILED - ${result.exceptionOrNull()?.message}"}" }
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

            return Result.success(operation)
        } finally {
            isUndoRedoInProgress = false
        }
    }

    suspend fun redo(): Result<EditOperation?> {
        // Get operation from stack (protected by mutex)
        val operation = bufferMutex.withLock {
            if (redoStack.isEmpty()) {
                return Result.success(null)
            }
            val op = redoStack.removeLast()
            val opMemory = op.estimateMemoryBytes()

            // Update memory counters when moving operation between stacks
            currentRedoMemoryBytes -= opMemory
            undoStack.addLast(op)
            currentUndoMemoryBytes += opMemory

            op
        }

        // Set flag to prevent adding new undo operations
        isUndoRedoInProgress = true
        try {
            // Re-apply operation OUTSIDE mutex to avoid deadlock
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

            return Result.success(operation)
        } finally {
            isUndoRedoInProgress = false
        }
    }

    fun canUndo(): Boolean = undoStack.isNotEmpty()

    fun canRedo(): Boolean = redoStack.isNotEmpty()

    /**
     * Counts lines in a chunk using the specified line ending style.
     * Only adds +1 for missing trailing newline if this is the last chunk.
     *
     * For CRLF files, we count LF since every CRLF contains an LF.
     * This handles CRLF sequences that may be split across chunk boundaries.
     */
    private fun countLinesInChunk(content: String, lineEnding: LineEnding, isLastChunk: Boolean): Int {
        if (content.isEmpty()) return if (isLastChunk) 1 else 0

        val newlineCount = when (lineEnding) {
            LineEnding.LF -> content.count { it == '\n' }
            LineEnding.CRLF -> content.count { it == '\n' }  // Count LF (part of every CRLF)
            LineEnding.CR -> content.count { it == '\r' }
            LineEnding.MIXED -> {
                // For mixed, count distinct line endings (avoid double-counting CRLF)
                val crlfCount = content.windowed(2).count { it == "\r\n" }
                val totalLfCount = content.count { it == '\n' }
                val totalCrCount = content.count { it == '\r' }
                val standaloneLf = totalLfCount - crlfCount
                val standaloneCr = totalCrCount - crlfCount
                crlfCount + standaloneLf + standaloneCr
            }
        }

        // Only add +1 for last chunk if it doesn't end with newline
        if (isLastChunk) {
            val endsWithNewline = when (lineEnding) {
                LineEnding.LF -> content.endsWith('\n')
                LineEnding.CRLF -> content.endsWith('\n') || content.endsWith("\r\n")
                LineEnding.CR -> content.endsWith('\r')
                LineEnding.MIXED -> content.endsWith('\n') || content.endsWith("\r\n") || content.endsWith('\r')
            }
            return newlineCount + if (!endsWithNewline) 1 else 0
        }

        return newlineCount
    }

    /**
     * Detects line ending style from content.
     * Same logic as ChunkRepository but needed here for multi-chunk scanning.
     */
    private fun detectLineEndingFromContent(content: String): LineEnding {
        if (content.isEmpty()) return LineEnding.LF

        val crlfCount = content.windowed(2).count { it == "\r\n" }
        val lfCount = content.count { it == '\n' } - crlfCount  // LF not part of CRLF
        val crCount = content.count { it == '\r' } - crlfCount  // CR not part of CRLF

        return when {
            // Pure CRLF (Windows)
            crlfCount > 0 && lfCount == 0 && crCount == 0 -> LineEnding.CRLF
            // Pure LF (Unix)
            lfCount > 0 && crlfCount == 0 && crCount == 0 -> LineEnding.LF
            // Pure CR (old Mac)
            crCount > 0 && lfCount == 0 && crlfCount == 0 -> LineEnding.CR
            // Mixed or multiple styles present
            else -> {
                if (crlfCount + lfCount + crCount == 0) LineEnding.LF  // No newlines, default LF
                else LineEnding.MIXED
            }
        }
    }

    private suspend fun buildChunkMetadata() {
        chunkMetadata.clear()
        var totalLines = 0

        val fileSize = _totalLength.value

        // Handle empty files
        if (fileSize == 0L) {
            chunkMetadata.add(
                ChunkMetadata(
                    chunkId = chunkIds.firstOrNull() ?: TextChunk.ChunkId.generate(),
                    startOffset = 0L,
                    endOffset = 0L,
                    lineCount = 1,
                    firstLineNumber = 0
                )
            )
            _totalLines.value = 1
            // Set default line ending for empty files
            _lineEnding.value = LineEnding.LF
            _fileInfo.value = _fileInfo.value?.copy(lineEnding = LineEnding.LF)
            log(tag) { "Built metadata for empty file (1 line, default LF)" }
            return
        }

        val startTime = System.currentTimeMillis()
        log(tag) { "Building chunk metadata for ${chunkIds.size} chunks from boundaries" }

        // Detect document-wide line ending from multiple chunks for accuracy
        // (A single chunk may have incomplete CRLF at boundary)
        val documentLineEnding = if (chunkIds.isNotEmpty()) {
            // Scan first 3 chunks (or all if fewer) to get better sample
            val chunksToScan = chunkIds.take(3)
            val combinedContent = StringBuilder()

            for (scanChunkId in chunksToScan) {
                val scanChunk = chunkManager.getChunk(scanChunkId)
                    ?: chunkManager.loadChunk(scanChunkId).getOrThrow()
                combinedContent.append(scanChunk.content)
            }

            // Detect from combined content (handles split CRLF at boundaries)
            detectLineEndingFromContent(combinedContent.toString())
        } else {
            LineEnding.LF  // Default for empty
        }

        // Update line ending state (works for both file and in-memory sources)
        _lineEnding.value = documentLineEnding
        // Also update FileInfo if present (file sources)
        _fileInfo.value = _fileInfo.value?.copy(lineEnding = documentLineEnding)
        log(tag) { "Detected document line ending: $documentLineEnding" }

        // Use line counts from boundaries (incrementally maintained during edits)
        // Only load chunks on first initialization when lineCount=0 (sentinel value)
        val newLineCounts = mutableMapOf<TextChunk.ChunkId, Int>()

        // Snapshot all boundaries at once to prevent race conditions during concurrent updates
        // Without this, concurrent edits could modify boundaries mid-iteration, causing inconsistent state
        val boundariesSnapshot = chunkIds.associateWith { chunkId ->
            chunkManager.getBoundary(chunkId)
                ?: throw IllegalStateException("No boundary for chunk $chunkId")
        }

        for ((index, chunkId) in chunkIds.withIndex()) {
            // Use snapshotted boundary data (prevents stale reads during concurrent edits)
            val boundary = boundariesSnapshot[chunkId]
                ?: throw IllegalStateException("No boundary for chunk $chunkId")

            val chunkStart = boundary.startOffset
            val chunkEnd = boundary.endOffset
            val lineCount: Int

            // Check if line count needs initialization (first time building metadata)
            if (boundary.lineCount == 0) {
                // Load chunk to count lines (only on first initialization)
                val chunk = chunkManager.getChunk(chunkId)
                    ?: chunkManager.loadChunk(chunkId).getOrThrow()

                // Count lines using detected line ending style
                val content = chunk.content
                val isLastChunk = index == chunkIds.size - 1
                lineCount = countLinesInChunk(content, documentLineEnding, isLastChunk)

                // Collect line count for batch update (don't update boundary yet)
                newLineCounts[chunkId] = lineCount
            } else {
                // Use cached line count from boundary (no chunk loading needed!)
                lineCount = boundary.lineCount
            }

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

        // Apply all line count updates atomically at the end
        if (newLineCounts.isNotEmpty()) {
            chunkManager.batchUpdateLineCounts(newLineCounts)
            log(tag) { "Initialized line counts for ${newLineCounts.size} chunks (first-time metadata build)" }
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

    /**
     * Estimates the memory footprint of an EditOperation in bytes.
     * Strings in JVM use UTF-16 encoding (2 bytes per character).
     */
    private fun EditOperation.estimateMemoryBytes(): Long {
        val baseSize = 32L  // Position (24 bytes) + timestamp (8 bytes) overhead
        return when (this) {
            is EditOperation.Insert -> baseSize + (text.length * 2L)
            is EditOperation.Delete -> baseSize + (deletedText.length * 2L) + 4L  // +4 for length Int
            is EditOperation.Replace -> baseSize + (oldText.length * 2L) + (newText.length * 2L)
        }
    }

    @AssistedFactory
    interface Factory {
        fun create(
            workspaceId: Workspace.Id,
            chunkManager: ChunkManager,
            chunkRepository: ChunkRepository,
            maxUndoStackSize: Int = 100,
            maxUndoMemoryBytes: Long = 10_485_760,
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