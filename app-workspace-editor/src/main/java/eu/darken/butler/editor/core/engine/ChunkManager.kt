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

    suspend fun saveChunk(chunkId: TextChunk.ChunkId): Result<Unit> = chunkMutex.withLock {
        val chunk = _chunks.value[chunkId] ?: return@withLock Result.failure(
            IllegalArgumentException("Chunk not found: $chunkId")
        )

        if (!chunk.isDirty) {
            log(tag) { "Chunk $chunkId is not dirty, skipping save" }
            return@withLock Result.success(Unit)
        }

        try {
            chunkRepository.saveChunk(chunk)

            // Mark as clean
            val cleanChunk = chunk.markClean()
            _chunks.value = _chunks.value + (chunkId to cleanChunk)

            log(tag) { "Successfully saved chunk: $chunkId" }
            Result.success(Unit)

        } catch (e: Exception) {
            log(tag, Logging.Priority.ERROR) { "Failed to save chunk: $chunkId - ${e.asLog()}" }
            Result.failure(e)
        }
    }

    suspend fun saveAllDirtyChunks(): Result<Unit> {
        val dirtyChunks = _chunks.value.values.filter { it.isDirty }

        log(tag) { "Saving ${dirtyChunks.size} dirty chunks" }

        var lastError: Exception? = null
        var successCount = 0

        for (chunk in dirtyChunks) {
            saveChunk(chunk.id).fold(
                onSuccess = { successCount++ },
                onFailure = { lastError = it as? Exception ?: Exception(it.message) }
            )
        }

        return if (lastError != null && successCount == 0) {
            Result.failure(lastError)
        } else {
            Result.success(Unit)
        }
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
    }
}