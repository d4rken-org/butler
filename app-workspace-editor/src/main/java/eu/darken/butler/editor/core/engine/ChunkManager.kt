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

    var chunkSize: Long = DEFAULT_CHUNK_SIZE
        private set

    suspend fun loadChunk(chunkId: TextChunk.ChunkId): Result<TextChunk> = chunkMutex.withLock {
        log(tag) { "Loading chunk: $chunkId" }

        // Check if already loaded
        _chunks.value[chunkId]?.let { existingChunk ->
            if (existingChunk.isLoaded) {
                log(tag) { "Chunk $chunkId already loaded" }
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
            val chunk = chunkRepository.loadChunk(chunkId)

            // Update state
            _chunks.value = _chunks.value + (chunkId to chunk)
            _loadStates.value = _loadStates.value + (chunkId to ChunkLoadState(
                isLoading = false,
                loadedAt = System.currentTimeMillis()
            ))

            log(tag) { "Successfully loaded chunk: $chunkId (${chunk.size} bytes)" }
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
        return _chunks.value.values.filter { chunk ->
            chunk.startOffset < endOffset && chunk.endOffset >= startOffset
        }.sortedBy { it.startOffset }
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

        log(tag) { "Evicting chunk: $chunkId" }

        _chunks.value = _chunks.value - chunkId
        _loadStates.value = _loadStates.value - chunkId

        true
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
        const val DEFAULT_CHUNK_SIZE = 1024 * 1024L // 1MB
    }
}