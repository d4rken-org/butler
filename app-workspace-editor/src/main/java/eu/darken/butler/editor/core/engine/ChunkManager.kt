package eu.darken.butler.editor.core.engine

import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import eu.darken.butler.common.debug.logging.Logging
import eu.darken.butler.common.debug.logging.asLog
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.debug.logging.logTag
import eu.darken.butler.workspace.core.Workspace
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class ChunkManager @AssistedInject constructor(
    @Assisted private val workspaceId: Workspace.Id,
    @Assisted private val chunkRepository: ChunkRepository,
) {

    private val tag = logTag("Editor", "Workspace", workspaceId.shortTag, "Engine", "ChunkManager")

    private val _chunks = MutableStateFlow<Map<TextChunk.ChunkId, TextChunk>>(emptyMap())
    val chunks: StateFlow<Map<TextChunk.ChunkId, TextChunk>> = _chunks.asStateFlow()

    private val _loadStates = MutableStateFlow<Map<TextChunk.ChunkId, ChunkLoadState>>(emptyMap())
    val loadStates: StateFlow<Map<TextChunk.ChunkId, ChunkLoadState>> = _loadStates.asStateFlow()

    private val chunkMutex = Mutex()

    // LRU tracking for chunk eviction
    private val chunkAccessOrder = mutableListOf<TextChunk.ChunkId>()
    private var maxCachedChunks: Int = DEFAULT_MAX_CACHED_CHUNKS

    var chunkSize: Long = DEFAULT_CHUNK_SIZE
        private set

    suspend fun loadChunk(chunkId: TextChunk.ChunkId): Result<TextChunk> = chunkMutex.withLock {
        log(tag) { "Loading chunk: $chunkId" }

        // Check if already loaded
        _chunks.value[chunkId]?.let { existingChunk ->
            if (existingChunk.isLoaded) {
                log(tag) { "Chunk $chunkId already loaded (cached)" }
                // Update LRU: move to end (most recently used)
                chunkAccessOrder.remove(chunkId)
                chunkAccessOrder.add(chunkId)
                return@withLock Result.success(existingChunk)
            }
        }

        // Check if already loading
        val currentState = _loadStates.value[chunkId]
        if (currentState?.isLoading == true) {
            log(tag) { "Chunk $chunkId is already being loaded" }
            return@withLock Result.failure(IllegalStateException("Chunk is already being loaded"))
        }

        try {
            // Mark as loading
            _loadStates.value = _loadStates.value + (chunkId to ChunkLoadState(isLoading = true))

            // Load from repository
            log(tag) { "Loading chunk $chunkId from disk" }
            val chunk = chunkRepository.loadChunk(chunkId)

            // Update state
            _chunks.value = _chunks.value + (chunkId to chunk)
            _loadStates.value = _loadStates.value + (chunkId to ChunkLoadState(
                isLoading = false,
                loadedAt = System.currentTimeMillis()
            ))

            // Update LRU: add to end (most recently used)
            chunkAccessOrder.remove(chunkId)
            chunkAccessOrder.add(chunkId)

            log(tag) { "Successfully loaded chunk: $chunkId (${chunk.size} bytes)" }

            // Trigger eviction if cache is full
            evictOldChunksIfNeeded()

            Result.success(chunk)

        } catch (e: Exception) {
            log(tag, Logging.Priority.ERROR) { "Failed to load chunk: $chunkId - ${e.asLog()}" }

            // Mark as error
            _loadStates.value = _loadStates.value + (chunkId to ChunkLoadState(
                isLoading = false,
                error = e
            ))

            Result.failure(e)
        }
    }

    suspend fun getChunk(chunkId: TextChunk.ChunkId): TextChunk? {
        return _chunks.value[chunkId]
    }

    suspend fun getChunksInRange(startOffset: Long, endOffset: Long): List<TextChunk> {
        // Calculate which chunk IDs are needed for this offset range
        val firstChunkOffset = (startOffset / chunkSize) * chunkSize
        val neededChunkIds = mutableListOf<TextChunk.ChunkId>()

        var currentOffset = firstChunkOffset
        while (currentOffset < endOffset) {
            neededChunkIds.add(TextChunk.ChunkId.generate(currentOffset))
            currentOffset += chunkSize
        }

        // Load chunks if not already loaded
        val loadedChunks = mutableListOf<TextChunk>()
        for (chunkId in neededChunkIds) {
            val chunk = _chunks.value[chunkId]
            if (chunk != null && chunk.isLoaded) {
                // Chunk already in cache
                loadedChunks.add(chunk)
            } else {
                // Chunk not in cache, need to load it
                val loadResult = loadChunk(chunkId)
                loadResult.fold(
                    onSuccess = { loadedChunks.add(it) },
                    onFailure = {
                        log(tag, Logging.Priority.WARN) { "Failed to load chunk $chunkId for range $startOffset-$endOffset" }
                    }
                )
            }
        }

        return loadedChunks.sortedBy { it.startOffset }
    }

    suspend fun addChunk(chunk: TextChunk): Unit = chunkMutex.withLock {
        _chunks.value = _chunks.value + (chunk.id to chunk)

    }

    suspend fun updateChunk(chunkId: TextChunk.ChunkId, updater: (TextChunk) -> TextChunk): TextChunk? =
        chunkMutex.withLock {
            val currentChunk = _chunks.value[chunkId] ?: return@withLock null
            val updatedChunk = updater(currentChunk)

            _chunks.value = _chunks.value + (chunkId to updatedChunk)

            updatedChunk
        }

    suspend fun evictChunk(chunkId: TextChunk.ChunkId): Boolean = chunkMutex.withLock {
        val chunk = _chunks.value[chunkId] ?: return@withLock false

        // Don't evict dirty chunks without saving
        if (chunk.isDirty) {
            log(tag) { "Cannot evict dirty chunk: $chunkId" }
            return@withLock false
        }

        log(tag) { "Evicting chunk: $chunkId (LRU)" }

        _chunks.value = _chunks.value - chunkId
        _loadStates.value = _loadStates.value - chunkId
        chunkAccessOrder.remove(chunkId)

        true
    }

    private fun evictOldChunksIfNeeded() {
        // NOTE: This should be called from within chunkMutex.withLock
        val currentCount = _chunks.value.size
        if (currentCount <= maxCachedChunks) {
            return
        }

        val chunksToEvict = currentCount - maxCachedChunks
        log(tag) { "Cache full ($currentCount/$maxCachedChunks chunks), evicting $chunksToEvict oldest chunks" }

        // Evict least recently used chunks
        val evictCandidates = chunkAccessOrder.take(chunksToEvict).toList()
        for (chunkId in evictCandidates) {
            val chunk = _chunks.value[chunkId]
            if (chunk != null && !chunk.isDirty) {
                _chunks.value = _chunks.value - chunkId
                _loadStates.value = _loadStates.value - chunkId
                chunkAccessOrder.remove(chunkId)
                log(tag) { "Evicted chunk: $chunkId (LRU)" }
            }
        }
    }

    /**
     * Commits all dirty chunks to the data source using all-or-nothing semantics.
     *
     * Flow:
     * 1. Get all dirty chunks
     * 2. Save to data source (atomic operation)
     * 3. Mark chunks clean only if save succeeds
     *
     * Chunks remain dirty (protected from eviction) if save fails.
     *
     * @return Result.success if all chunks saved, Result.failure if save failed
     */
    suspend fun saveAllDirtyChunks(): Result<Unit> {
        val dirtyChunks = getDirtyChunks()

        if (dirtyChunks.isEmpty()) {
            log(tag) { "No dirty chunks to save" }
            return Result.success(Unit)
        }

        log(tag) { "Committing ${dirtyChunks.size} dirty chunks (all-or-nothing)" }

        return try {
            // Save all chunks - throws on failure
            chunkRepository.saveFile(dirtyChunks)

            // Only mark clean if save succeeded
            val chunkIds = dirtyChunks.map { it.id }
            markChunksClean(chunkIds)

            log(tag) { "Successfully committed ${dirtyChunks.size} chunks" }
            Result.success(Unit)

        } catch (e: Exception) {
            log(tag, Logging.Priority.ERROR) {
                "Failed to commit dirty chunks (chunks remain dirty) - ${e.asLog()}"
            }
            Result.failure(e)
        }
    }

    /**
     * Returns all dirty chunks sorted by startOffset.
     * Used for batch save operations where we need all modified chunks in order.
     */
    suspend fun getDirtyChunks(): List<TextChunk> = chunkMutex.withLock {
        val dirtyChunks = _chunks.value.values.filter { it.isDirty }
        log(tag) { "Found ${dirtyChunks.size} dirty chunks" }
        dirtyChunks.sortedBy { it.startOffset }
    }

    /**
     * Marks multiple chunks as clean after successful save.
     * This is a batch operation to avoid multiple StateFlow updates.
     *
     * @param chunkIds List of chunk IDs to mark as clean
     */
    suspend fun markChunksClean(chunkIds: List<TextChunk.ChunkId>): Unit = chunkMutex.withLock {
        log(tag) { "Marking ${chunkIds.size} chunks as clean" }

        val updatedChunks = _chunks.value.toMutableMap()
        var markedCount = 0

        for (chunkId in chunkIds) {
            updatedChunks[chunkId]?.let { chunk ->
                if (chunk.isDirty) {
                    updatedChunks[chunkId] = chunk.markClean()
                    markedCount++
                }
            }
        }

        _chunks.value = updatedChunks
        log(tag) { "Successfully marked $markedCount chunks as clean" }
    }

    suspend fun generateChunkIds(fileSize: Long): List<TextChunk.ChunkId> {
        val chunkIds = mutableListOf<TextChunk.ChunkId>()
        var offset = 0L

        while (offset < fileSize) {
            chunkIds.add(TextChunk.ChunkId.generate(offset))
            offset += chunkSize
        }

        return chunkIds
    }

    suspend fun clear() = chunkMutex.withLock {
        log(tag) { "Clearing all chunks" }
        _chunks.value = emptyMap()
        _loadStates.value = emptyMap()
    }

    @AssistedFactory
    interface Factory {
        fun create(
            workspaceId: Workspace.Id,
            chunkRepository: ChunkRepository
        ): ChunkManager
    }

    companion object {
        const val DEFAULT_CHUNK_SIZE = 64 * 1024L // 64KB for testing
        const val DEFAULT_MAX_CACHED_CHUNKS = 5 // Keep 5 chunks in memory (320KB with 64KB chunks)

        /**
         * Merges dirty chunks into original file content, correctly handling chunk size changes.
         *
         * Algorithm:
         * 1. Process chunks in order (sorted by startOffset)
         * 2. For gaps between chunks, copy unchanged content from original
         * 3. For each chunk, insert the new content (which may be different size)
         * 4. Copy any remaining content after the last chunk
         *
         * This correctly handles:
         * - Chunk expansion (new content larger than original)
         * - Chunk shrinking (new content smaller than original)
         * - Multiple modifications without manual offset tracking
         *
         * @param originalContent The original file content as bytes
         * @param dirtyChunks List of modified chunks (will be sorted by startOffset)
         * @return Merged byte array with all modifications applied
         */
        fun mergeChunks(originalContent: ByteArray, dirtyChunks: List<TextChunk>): ByteArray {
            if (dirtyChunks.isEmpty()) return originalContent

            val sortedChunks = dirtyChunks.sortedBy { it.startOffset }
            val result = mutableListOf<Byte>()
            var currentOriginalPos = 0L

            for (chunk in sortedChunks) {
                // Copy unchanged content before this chunk
                if (currentOriginalPos < chunk.startOffset) {
                    val unchangedStart = currentOriginalPos.toInt()
                    val unchangedEnd = chunk.startOffset.toInt()
                    if (unchangedStart < originalContent.size) {
                        val unchangedBytes = originalContent.sliceArray(
                            unchangedStart until minOf(unchangedEnd, originalContent.size)
                        )
                        result.addAll(unchangedBytes.toList())
                    }
                }

                // Add the modified chunk content (size may differ from original)
                val newBytes = chunk.content.toByteArray(Charsets.UTF_8)
                result.addAll(newBytes.toList())

                // Move past this chunk in original file
                currentOriginalPos = chunk.endOffset
            }

            // Copy any remaining content after the last chunk
            if (currentOriginalPos < originalContent.size) {
                val remainingBytes = originalContent.sliceArray(
                    currentOriginalPos.toInt() until originalContent.size
                )
                result.addAll(remainingBytes.toList())
            }

            return result.toByteArray()
        }
    }
}