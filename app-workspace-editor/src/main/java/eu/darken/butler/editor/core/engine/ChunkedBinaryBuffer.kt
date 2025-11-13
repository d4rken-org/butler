package eu.darken.butler.editor.core.engine

import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import eu.darken.butler.common.debug.logging.Logging.Priority.*
import eu.darken.butler.common.debug.logging.asLog
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.debug.logging.logTag
import eu.darken.butler.workspace.core.Workspace
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.LinkedList

/**
 * Chunked buffer for binary file editing.
 * Provides byte-level operations without text decoding.
 */
class ChunkedBinaryBuffer @AssistedInject constructor(
    @Assisted private val workspaceId: Workspace.Id,
    @Assisted private val chunkManager: ChunkManager,
    @Assisted private val repository: BinaryChunkRepository,
    @Assisted private val maxUndoStackSize: Int = 100,
    @Assisted private val maxUndoMemoryBytes: Long = 10_485_760, // 10 MB
) : EditorBuffer {

    private val tag = logTag("Editor", "Workspace", workspaceId.shortTag, "Engine", "ChunkedBinaryBuffer")

    private val _fileInfo = MutableStateFlow<FileInfo?>(null)
    val fileInfo: StateFlow<FileInfo?> = _fileInfo.asStateFlow()

    private val _totalLength = MutableStateFlow(0L)
    val totalLength: StateFlow<Long> = _totalLength.asStateFlow()

    private val _isModified = MutableStateFlow(false)
    override val isModified: StateFlow<Boolean> = _isModified.asStateFlow()

    private val bufferMutex = Mutex()
    private val undoStack = LinkedList<BinaryEditOperation>()
    private val redoStack = LinkedList<BinaryEditOperation>()
    private var isUndoRedoInProgress = false
    private var currentUndoMemoryBytes: Long = 0
    private var currentRedoMemoryBytes: Long = 0

    private var chunkIds: List<EditorChunk.ChunkId> = emptyList()

    override suspend fun initialize(): Result<Unit> = bufferMutex.withLock {
        try {
            // Get size from data source
            val size = repository.dataSource.getSize()
            _totalLength.value = size

            log(tag) { "Initializing binary buffer with ${size} bytes" }

            // Generate binary chunk IDs based on content size
            chunkIds = chunkManager.generateBinaryChunkIds(size)

            // Load all chunks initially (for now - we'll add lazy loading in Phase 3)
            for (chunkId in chunkIds) {
                val boundary = chunkManager.getBoundary(chunkId)
                    ?: throw IllegalStateException("No boundary for chunk ${chunkId.value}")

                // Load chunk from repository
                val chunk = repository.loadChunk(chunkId, boundary)
                chunkManager.addBinaryChunk(chunk)
            }

            log(tag) { "Successfully initialized binary buffer (${size} bytes, ${chunkIds.size} chunks loaded)" }
            Result.success(Unit)

        } catch (e: Exception) {
            log(tag, ERROR) { "Failed to initialize binary buffer - ${e.asLog()}" }
            Result.failure(e)
        }
    }

    suspend fun release(): Result<Unit> {
        return try {
            // Save any dirty chunks
            if (_isModified.value) {
                log(tag, WARN) { "Binary buffer has unsaved changes, saving before release" }
                val saveResult = saveFile()
                if (saveResult.isFailure) {
                    log(tag, ERROR) { "Failed to save changes before release" }
                    return saveResult
                }
            }

            bufferMutex.withLock {
                // Clear state
                chunkManager.clear()
                chunkIds = emptyList()
                undoStack.clear()
                redoStack.clear()

                _fileInfo.value = null
                _totalLength.value = 0L
                _isModified.value = false
            }

            Result.success(Unit)
        } catch (e: Exception) {
            log(tag, ERROR) { "Failed to release buffer - ${e.asLog()}" }
            Result.failure(e)
        }
    }

    suspend fun getBytes(startOffset: Long, endOffset: Long): Result<ByteArray> {
        try {
            if (startOffset < 0 || endOffset > _totalLength.value || startOffset > endOffset) {
                return Result.failure(IndexOutOfBoundsException("Invalid range: $startOffset-$endOffset"))
            }

            if (startOffset == endOffset) {
                return Result.success(byteArrayOf())
            }

            val chunks = chunkManager.getBinaryChunksInRange(startOffset, endOffset)
            val resultSize = (endOffset - startOffset).toInt()
            val result = ByteArray(resultSize)
            var resultOffset = 0

            for (chunk in chunks) {
                // Load chunk if not loaded
                val loadedChunk = if (!chunk.isLoaded) {
                    chunkManager.loadBinaryChunk(chunk.id).getOrThrow()
                } else {
                    chunk
                }

                // Get boundary for this chunk
                val boundary = chunkManager.getBoundary(chunk.id)
                    ?: return Result.failure(IllegalStateException("No boundary for chunk ${chunk.id}"))

                // Calculate the portion of this chunk we need
                val chunkStartInRange = maxOf(startOffset, boundary.startOffset)
                val chunkEndInRange = minOf(endOffset, boundary.endOffset)

                val startInChunk = (chunkStartInRange - boundary.startOffset).toInt()
                val endInChunk = (chunkEndInRange - boundary.startOffset).toInt()
                val copyLength = endInChunk - startInChunk

                // Copy bytes from chunk to result
                System.arraycopy(loadedChunk.content, startInChunk, result, resultOffset, copyLength)
                resultOffset += copyLength
            }

            return Result.success(result)

        } catch (e: Exception) {
            log(tag, ERROR) { "Failed to get bytes for range: $startOffset-$endOffset - ${e.asLog()}" }
            return Result.failure(e)
        }
    }

    suspend fun insertBytes(offset: Long, data: ByteArray): Result<Long> = bufferMutex.withLock {
        try {
            if (offset < 0 || offset > _totalLength.value) {
                return@withLock Result.failure(IllegalArgumentException("Offset is out of bounds"))
            }

            if (data.isEmpty()) {
                return@withLock Result.success(offset)
            }

            // Find the chunk containing this offset
            val chunk = findChunkForOffset(offset)
            if (chunk == null) {
                log(tag, ERROR) { "insertBytes: No chunk found for offset $offset, totalLength=${_totalLength.value}" }
                return@withLock Result.failure(IllegalArgumentException("Offset is out of bounds"))
            }

            // Pin the chunk to prevent eviction during this operation
            val pinResult = chunkManager.withPinnedBinaryChunk(chunk.id) { pinnedChunk ->
                // Get boundary for offset calculation
                val boundary = chunkManager.getBoundary(chunk.id)
                    ?: throw IllegalStateException("No boundary for chunk ${chunk.id}")

                // Calculate insertion point within chunk
                val insertionIndex = (offset - boundary.startOffset).toInt()
                val newContent = pinnedChunk.content.copyOfRange(0, insertionIndex) +
                    data +
                    pinnedChunk.content.copyOfRange(insertionIndex, pinnedChunk.content.size)

                // Update the chunk
                chunkManager.updateBinaryChunk(chunk.id) { currentChunk ->
                    currentChunk.copy(
                        content = newContent,
                        size = newContent.size.toLong(),
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
            _totalLength.value = _totalLength.value + data.size

            // Update binary chunk boundaries
            chunkManager.updateBinaryBoundaries(offset, data.size.toLong())

            // Mark as modified
            _isModified.value = true

            // Add to undo stack
            if (!isUndoRedoInProgress) {
                val operation = BinaryEditOperation.Insert(offset, data)
                addToUndoStack(operation)
            }

            Result.success(offset + data.size)

        } catch (e: Exception) {
            log(tag, ERROR) { "Failed to insert bytes at offset: $offset - ${e.asLog()}" }
            Result.failure(e)
        }
    }

    suspend fun deleteBytes(startOffset: Long, endOffset: Long): Result<ByteArray> = bufferMutex.withLock {
        try {
            if (startOffset < 0 || endOffset > _totalLength.value || startOffset >= endOffset) {
                return@withLock Result.failure(IllegalArgumentException("Invalid range: $startOffset-$endOffset"))
            }

            // Get the data being deleted first
            val deletedData = getBytes(startOffset, endOffset).getOrThrow()

            // Find affected chunks
            val affectedChunks = chunkManager.getBinaryChunksInRange(startOffset, endOffset)
            val affectedChunkIds = affectedChunks.map { it.id }.toSet()

            // Track merged chunk info
            var mergedChunkId: EditorChunk.ChunkId? = null
            var mergedChunkSize: Long = 0

            // Pin all affected chunks
            val pinResult = chunkManager.withPinnedBinaryChunks(affectedChunkIds) { pinnedChunks ->
                if (pinnedChunks.size == 1) {
                    // Single chunk deletion
                    val chunk = pinnedChunks.first()
                    val boundary = chunkManager.getBoundary(chunk.id)
                        ?: throw IllegalStateException("No boundary for chunk ${chunk.id}")

                    val startInChunk = (startOffset - boundary.startOffset).toInt()
                    val endInChunk = (endOffset - boundary.startOffset).toInt()

                    val newContent = chunk.content.copyOfRange(0, startInChunk) +
                        chunk.content.copyOfRange(endInChunk, chunk.content.size)

                    chunkManager.updateBinaryChunk(chunk.id) { currentChunk ->
                        currentChunk.copy(
                            content = newContent,
                            size = newContent.size.toLong(),
                            isDirty = true
                        )
                    } ?: throw IllegalStateException("Failed to update chunk ${chunk.id}")

                    emptySet<EditorChunk.ChunkId>()
                } else {
                    // Multi-chunk deletion: merge content from first and last chunks
                    val firstChunk = pinnedChunks.first()
                    val lastChunk = pinnedChunks.last()

                    val firstBoundary = chunkManager.getBoundary(firstChunk.id)
                        ?: throw IllegalStateException("No boundary for chunk ${firstChunk.id}")
                    val lastBoundary = chunkManager.getBoundary(lastChunk.id)
                        ?: throw IllegalStateException("No boundary for chunk ${lastChunk.id}")

                    val startInFirstChunk = (startOffset - firstBoundary.startOffset).toInt()
                    val endInLastChunk = (endOffset - lastBoundary.startOffset).toInt()

                    val contentBeforeDelete = firstChunk.content.copyOfRange(0, startInFirstChunk)
                    val contentAfterDelete = lastChunk.content.copyOfRange(endInLastChunk, lastChunk.content.size)
                    val mergedContent = contentBeforeDelete + contentAfterDelete

                    chunkManager.updateBinaryChunk(firstChunk.id) { currentChunk ->
                        currentChunk.copy(
                            content = mergedContent,
                            size = mergedContent.size.toLong(),
                            isDirty = true
                        )
                    } ?: throw IllegalStateException("Failed to update chunk ${firstChunk.id}")

                    mergedChunkId = firstChunk.id
                    mergedChunkSize = mergedContent.size.toLong()

                    log(tag, DEBUG) { "Multi-chunk delete: merged ${pinnedChunks.size} chunks" }

                    // Return chunks to evict (all except first)
                    pinnedChunks.drop(1).map { it.id }.toSet()
                }
            }

            if (pinResult.isFailure) {
                return@withLock Result.failure(
                    pinResult.exceptionOrNull() ?: Exception("Failed to pin chunks for deletion")
                )
            }

            // Evict chunks that were merged
            val chunksToEvict = pinResult.getOrThrow()
            if (chunksToEvict.isNotEmpty()) {
                for (chunkId in chunksToEvict) {
                    chunkManager.evictBinaryChunk(chunkId, removeFromStructure = true)
                }
                chunkIds = chunkIds.filterNot { it in chunksToEvict }
                log(tag, DEBUG) { "Evicted ${chunksToEvict.size} chunks after multi-chunk delete" }
            }

            // Update total length
            val deletedLength = endOffset - startOffset
            _totalLength.value = _totalLength.value - deletedLength

            // Update binary chunk boundaries
            chunkManager.updateBinaryBoundaries(startOffset, -deletedLength)

            // Fix merged chunk boundary if needed
            if (mergedChunkId != null) {
                val mergedBoundary = chunkManager.getBoundary(mergedChunkId!!)
                if (mergedBoundary != null) {
                    val correctedBoundary = ChunkBoundary(
                        startOffset = mergedBoundary.startOffset,
                        endOffset = mergedBoundary.startOffset + mergedChunkSize,
                        lineCount = 0
                    )
                    chunkManager.updateBoundary(mergedChunkId!!, correctedBoundary)
                }
            }

            // Mark as modified
            _isModified.value = true

            // Add to undo stack
            if (!isUndoRedoInProgress) {
                val operation = BinaryEditOperation.Delete(startOffset, deletedLength, deletedData)
                addToUndoStack(operation)
            }

            Result.success(deletedData)

        } catch (e: Exception) {
            log(tag, ERROR) { "Failed to delete bytes from $startOffset to $endOffset - ${e.asLog()}" }
            Result.failure(e)
        }
    }

    suspend fun replaceBytes(
        startOffset: Long,
        endOffset: Long,
        newData: ByteArray
    ): Result<Long> {
        return try {
            deleteBytes(startOffset, endOffset).getOrThrow()
            insertBytes(startOffset, newData)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Undo the last operation.
     * Returns null for position since binary buffers don't have cursor positioning.
     */
    override suspend fun undo(): Result<TextPosition?> {
        // Get operation from stack
        val operation = bufferMutex.withLock {
            if (undoStack.isEmpty()) {
                return Result.success(null)
            }
            val op = undoStack.removeLast()
            val opMemory = op.estimateMemoryBytes()

            currentUndoMemoryBytes -= opMemory
            redoStack.addLast(op)
            currentRedoMemoryBytes += opMemory

            op
        }

        // Set flag to prevent adding new undo operations
        isUndoRedoInProgress = true
        try {
            // Apply reverse operation
            when (operation) {
                is BinaryEditOperation.Insert -> {
                    log(tag, DEBUG) { "Undoing insert: deleting ${operation.data.size} bytes at offset ${operation.offset}" }
                    deleteBytes(operation.offset, operation.offset + operation.data.size)
                }
                is BinaryEditOperation.Delete -> {
                    log(tag, DEBUG) { "Undoing delete: inserting ${operation.deletedData.size} bytes at offset ${operation.offset}" }
                    insertBytes(operation.offset, operation.deletedData)
                }
                is BinaryEditOperation.Replace -> {
                    replaceBytes(
                        operation.offset,
                        operation.offset + operation.newData.size,
                        operation.oldData
                    )
                }
            }

            // Binary buffers don't have cursor positioning - return null
            return Result.success(null)
        } finally {
            isUndoRedoInProgress = false
        }
    }

    /**
     * Redo the previously undone operation.
     * Returns null for position since binary buffers don't have cursor positioning.
     */
    override suspend fun redo(): Result<TextPosition?> {
        // Get operation from stack
        val operation = bufferMutex.withLock {
            if (redoStack.isEmpty()) {
                return Result.success(null)
            }
            val op = redoStack.removeLast()
            val opMemory = op.estimateMemoryBytes()

            currentRedoMemoryBytes -= opMemory
            undoStack.addLast(op)
            currentUndoMemoryBytes += opMemory

            op
        }

        // Set flag to prevent adding new undo operations
        isUndoRedoInProgress = true
        try {
            // Re-apply operation
            when (operation) {
                is BinaryEditOperation.Insert -> {
                    insertBytes(operation.offset, operation.data)
                }
                is BinaryEditOperation.Delete -> {
                    deleteBytes(operation.offset, operation.offset + operation.length)
                }
                is BinaryEditOperation.Replace -> {
                    replaceBytes(
                        operation.offset,
                        operation.offset + operation.oldData.size,
                        operation.newData
                    )
                }
            }

            // Binary buffers don't have cursor positioning - return null
            return Result.success(null)
        } finally {
            isUndoRedoInProgress = false
        }
    }

    override fun canUndo(): Boolean = undoStack.isNotEmpty()

    override fun canRedo(): Boolean = redoStack.isNotEmpty()

    /**
     * Dispose of resources held by this buffer.
     * Note: This does NOT save modifications. Call saveFile() first if needed.
     */
    override fun dispose() {
        // Binary buffer cleanup is handled by ChunkManager
        // undo/redo stacks will be cleared when buffer is released
        log(tag) { "Disposing binary buffer" }
    }

    override suspend fun saveFile(): Result<Unit> {
        return try {
            // Get all dirty binary chunks
            val dirtyChunks = chunkManager.getDirtyBinaryChunks()

            if (dirtyChunks.isEmpty()) {
                log(tag) { "No dirty chunks to save" }
                return Result.success(Unit)
            }

            // Get boundaries for dirty chunks
            val boundaries = dirtyChunks.associate { chunk ->
                val boundary = chunkManager.getBoundary(chunk.id)
                    ?: return Result.failure(IllegalStateException("No boundary for chunk ${chunk.id.value}"))
                chunk.id to boundary
            }

            // Save using repository
            repository.saveFile(dirtyChunks, boundaries)

            // Mark all chunks as clean
            for (chunk in dirtyChunks) {
                chunkManager.updateBinaryChunk(chunk.id) { it.copy(isDirty = false) }
            }

            // Mark buffer as not modified
            _isModified.value = false

            log(tag) { "Successfully saved ${dirtyChunks.size} dirty binary chunks" }
            Result.success(Unit)

        } catch (e: Exception) {
            log(tag, ERROR) { "Failed to save binary file - ${e.asLog()}" }
            Result.failure(e)
        }
    }

    private suspend fun findChunkForOffset(offset: Long): EditorChunk.Binary? {
        val chunks = chunkManager.getBinaryChunksInRange(offset, offset + 1)
        return chunks.firstOrNull()
    }

    private fun addToUndoStack(operation: BinaryEditOperation) {
        val opMemory = operation.estimateMemoryBytes()

        undoStack.addLast(operation)
        currentUndoMemoryBytes += opMemory

        // Evict oldest operations if limits exceeded
        while ((undoStack.size > maxUndoStackSize || currentUndoMemoryBytes > maxUndoMemoryBytes)
            && undoStack.size > 1) {
            val evicted = undoStack.removeFirst()
            currentUndoMemoryBytes -= evicted.estimateMemoryBytes()
            log(tag, DEBUG) {
                "Evicted old undo operation (stack: ${undoStack.size}/${maxUndoStackSize}, " +
                    "memory: ${currentUndoMemoryBytes}/${maxUndoMemoryBytes} bytes)"
            }
        }

        // Clear redo stack
        redoStack.clear()
        currentRedoMemoryBytes = 0
    }

    @AssistedFactory
    interface Factory {
        fun create(
            workspaceId: Workspace.Id,
            chunkManager: ChunkManager,
            repository: BinaryChunkRepository,
            maxUndoStackSize: Int = 100,
            maxUndoMemoryBytes: Long = 10_485_760,
        ): ChunkedBinaryBuffer
    }
}
