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

class ChunkManager @AssistedInject constructor(
    @Assisted private val workspaceId: Workspace.Id,
    @Assisted private val chunkRepository: ChunkRepository?,
    @Assisted val chunkSize: Long = DEFAULT_CHUNK_SIZE
) {

    private val tag = logTag("Editor", "Workspace", workspaceId.shortTag, "Engine", "ChunkManager")

    // ==================== Unified Chunk Storage ====================
    // Single storage for all chunks using EditorChunk sealed class (Text and Binary)
    private val _chunks = MutableStateFlow<Map<EditorChunk.ChunkId, EditorChunk>>(emptyMap())
    val chunks: StateFlow<Map<EditorChunk.ChunkId, EditorChunk>> = _chunks

    private val _loadStates = MutableStateFlow<Map<EditorChunk.ChunkId, ChunkLoadState>>(emptyMap())
    val loadStates: StateFlow<Map<EditorChunk.ChunkId, ChunkLoadState>> = _loadStates

    private val chunkMutex = Mutex()

    // Unified chunk boundary tracking (offset → ID mapping)
    // @Volatile ensures cross-thread visibility after mutex release (fixes race condition)
    @Volatile
    private var boundaries: MutableMap<EditorChunk.ChunkId, ChunkBoundary> = mutableMapOf()

    // LRU tracking for chunk eviction
    private val chunkAccessOrder = mutableListOf<EditorChunk.ChunkId>()
    private var maxCachedChunks: Int = DEFAULT_MAX_CACHED_CHUNKS

    // ==================== Type-Safe Accessors ====================

    /**
     * Get a text chunk by ID (for internal use).
     */
    private fun getTextChunk(chunkId: EditorChunk.ChunkId): EditorChunk.Text? {
        return _chunks.value[chunkId] as? EditorChunk.Text
    }

    /**
     * Get a binary chunk by ID.
     */
    fun getBinaryChunk(chunkId: EditorChunk.ChunkId): EditorChunk.Binary? {
        return _chunks.value[chunkId] as? EditorChunk.Binary
    }

    // ==================== Text Chunk Methods ====================

    suspend fun loadChunk(chunkId: EditorChunk.ChunkId): Result<EditorChunk.Text> = chunkMutex.withLock {
        log(tag) { "Loading chunk: $chunkId" }

        // Check if already loaded
        getTextChunk(chunkId)?.let { existingChunk ->
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
            // Get boundary for this chunk
            val boundary = boundaries[chunkId]
                ?: return@withLock Result.failure(IllegalArgumentException("No boundary found for chunk: $chunkId"))

            // Mark as loading
            _loadStates.value = _loadStates.value + (chunkId to ChunkLoadState(isLoading = true))

            // Load from repository with boundary information
            log(tag) { "Loading chunk $chunkId from disk" }
            val editorChunk = chunkRepository?.loadChunk(chunkId, boundary)
                ?: return@withLock Result.failure(IllegalStateException("ChunkRepository not available"))

            // CRITICAL: Adjust boundary if chunk size differs due to surrogate pair protection
            // This can happen when chunk end falls in the middle of a UTF-16 surrogate pair
            val actualEndOffset = boundary.startOffset + editorChunk.size
            if (actualEndOffset != boundary.endOffset) {
                log(tag) {
                    "Adjusting boundary for $chunkId: [${boundary.startOffset}, ${boundary.endOffset}) -> " +
                            "[${boundary.startOffset}, $actualEndOffset) (surrogate pair protection)"
                }

                // Update this chunk's boundary
                boundaries[chunkId] = ChunkBoundary(
                    startOffset = boundary.startOffset,
                    endOffset = actualEndOffset,
                    lineCount = editorChunk.lineCount
                )

                // CASCADE: Adjust next chunk's start offset to match this chunk's new end
                // Find the next chunk by looking for one whose start matches our old end
                val nextChunkEntry = boundaries.entries.find { it.value.startOffset == boundary.endOffset }
                if (nextChunkEntry != null) {
                    val nextChunkId = nextChunkEntry.key
                    val nextBoundary = nextChunkEntry.value
                    log(tag) {
                        "Cascading boundary adjustment to next chunk ${nextChunkId.value}: " +
                                "start ${nextBoundary.startOffset} -> $actualEndOffset"
                    }
                    boundaries[nextChunkId] = ChunkBoundary(
                        startOffset = actualEndOffset,  // Start where previous chunk ended
                        endOffset = nextBoundary.endOffset,
                        lineCount = nextBoundary.lineCount
                    )
                }
            }

            // Update state with EditorChunk.Text
            _chunks.value = _chunks.value + (chunkId to editorChunk)
            _loadStates.value = _loadStates.value + (chunkId to ChunkLoadState(
                isLoading = false,
                loadedAt = System.currentTimeMillis()
            ))

            // Update LRU: add to end (most recently used)
            chunkAccessOrder.remove(chunkId)
            chunkAccessOrder.add(chunkId)

            log(tag) { "Successfully loaded chunk: $chunkId (${editorChunk.size} bytes)" }

            // Trigger eviction if cache is full
            evictOldChunksIfNeeded()

            Result.success(editorChunk)

        } catch (e: Exception) {
            log(tag, ERROR) { "Failed to load chunk: $chunkId - ${e.asLog()}" }

            // Mark as error
            _loadStates.value = _loadStates.value + (chunkId to ChunkLoadState(
                isLoading = false,
                error = e
            ))

            Result.failure(e)
        }
    }

    suspend fun getChunk(chunkId: EditorChunk.ChunkId): EditorChunk.Text? {
        return getTextChunk(chunkId)
    }

    /**
     * Get the boundary for a chunk ID.
     * Boundaries are the authoritative source for chunk positions in the file.
     *
     * Thread-safe: boundaries map is @Volatile and only modified atomically during boundary updates.
     */
    fun getBoundary(chunkId: EditorChunk.ChunkId): ChunkBoundary? {
        return boundaries[chunkId]
    }

    /**
     * Update a single chunk's boundary. Used when chunk content changes size.
     */
    suspend fun updateBoundary(chunkId: EditorChunk.ChunkId, newBoundary: ChunkBoundary) = chunkMutex.withLock {
        boundaries[chunkId] = newBoundary
    }

    /**
     * Get all chunk IDs currently tracked (both in cache and evicted but with boundaries).
     */
    fun getAllChunkIds(): List<EditorChunk.ChunkId> {
        return boundaries.keys.filter { _chunks.value[it] is EditorChunk.Text || _chunks.value[it] == null }
    }

    /**
     * Updates line counts for multiple chunks atomically.
     * Used during first metadata build to initialize line counts.
     */
    suspend fun batchUpdateLineCounts(lineCounts: Map<EditorChunk.ChunkId, Int>) = chunkMutex.withLock {
        for ((chunkId, lineCount) in lineCounts) {
            val boundary = boundaries[chunkId] ?: continue
            boundaries[chunkId] = boundary.copy(lineCount = lineCount)
        }
        log(tag) { "Batch updated line counts for ${lineCounts.size} chunks" }
    }

    suspend fun getChunksInRange(startOffset: Long, endOffset: Long): List<EditorChunk.Text> {
        // Snapshot relevant chunk IDs while holding mutex to avoid race conditions
        val relevantChunkIds = chunkMutex.withLock {
            val matching = boundaries.filter { (chunkId, boundary) ->
                // Only text chunks
                (_chunks.value[chunkId] is EditorChunk.Text || _chunks.value[chunkId] == null) &&
                // Use >= to include chunks that end exactly at startOffset (insertion at end of chunk)
                boundary.endOffset >= startOffset && boundary.startOffset < endOffset
            }
            log(tag, DEBUG) {
                "getChunksInRange($startOffset, $endOffset): checking ${boundaries.size} boundaries\n" +
                    boundaries.map { (id, b) -> "  ${id.value}: [${b.startOffset}, ${b.endOffset})" }.joinToString("\n") +
                    "\n  Matched: ${matching.keys.map { it.value }}"
            }
            matching.keys.toSet()
        }

        log(tag) { "Found ${relevantChunkIds.size} chunks in range $startOffset-$endOffset" }

        // Load chunks if not already loaded (outside mutex to avoid reentrant lock)
        val loadedChunks = mutableListOf<EditorChunk.Text>()
        for (chunkId in relevantChunkIds) {
            val chunk = _chunks.value[chunkId]
            if (chunk != null && chunk is EditorChunk.Text && chunk.isLoaded) {
                // Chunk already in cache
                loadedChunks.add(chunk)
            } else {
                // Chunk not in cache, need to load it
                val loadResult = loadChunk(chunkId)
                loadResult.fold(
                    onSuccess = { loadedChunks.add(it) },
                    onFailure = {
                        log(tag, WARN) { "Failed to load chunk $chunkId for range $startOffset-$endOffset" }
                    }
                )
            }
        }

        // Sort by boundary startOffset (using boundaries map as source of truth)
        return loadedChunks.sortedBy { chunk ->
            boundaries[chunk.id]?.startOffset ?: Long.MAX_VALUE
        }
    }

    suspend fun addChunk(chunk: EditorChunk.Text): Unit = chunkMutex.withLock {
        _chunks.value = _chunks.value + (chunk.id to chunk)
    }

    suspend fun updateChunk(chunkId: EditorChunk.ChunkId, updater: (EditorChunk.Text) -> EditorChunk.Text): EditorChunk.Text? =
        chunkMutex.withLock {
            val currentChunk = _chunks.value[chunkId] as? EditorChunk.Text ?: return@withLock null
            val updatedChunk = updater(currentChunk)

            // Preserve refCount from current chunk (updater might not include it in copy())
            val finalChunk = if (updatedChunk.refCount != currentChunk.refCount) {
                updatedChunk.copy(refCount = currentChunk.refCount)
            } else {
                updatedChunk
            }

            _chunks.value = _chunks.value + (chunkId to finalChunk)

            finalChunk
        }

    /**
     * Internal helper to pin a chunk without acquiring mutex (caller must hold lock).
     */
    private suspend fun pinChunkInternal(chunkId: EditorChunk.ChunkId): Result<EditorChunk.Text> {
        // Ensure chunk is loaded
        val editorChunk = _chunks.value[chunkId] as? EditorChunk.Text ?: run {
            log(tag) { "Chunk $chunkId not in cache, loading before pinning" }
            val loadResult = loadChunk(chunkId)
            if (loadResult.isFailure) {
                return Result.failure(
                    loadResult.exceptionOrNull() ?: Exception("Failed to load chunk for pinning")
                )
            }
            return Result.success(loadResult.getOrThrow())
        }

        // Pin the chunk
        val pinnedChunk = editorChunk.pin() as EditorChunk.Text
        _chunks.value = _chunks.value + (chunkId to pinnedChunk)

        log(tag) { "Pinned chunk: $chunkId (refCount=${pinnedChunk.refCount})" }

        return Result.success(pinnedChunk)
    }

    /**
     * Internal helper to unpin a chunk without acquiring mutex (caller must hold lock).
     */
    private suspend fun unpinChunkInternal(chunkId: EditorChunk.ChunkId): Result<Unit> {
        val editorChunk = _chunks.value[chunkId] as? EditorChunk.Text
            ?: return Result.failure(IllegalStateException("Chunk $chunkId not in cache"))

        if (editorChunk.refCount == 0) {
            log(tag, WARN) { "Attempting to unpin chunk $chunkId with refCount=0" }
            return Result.failure(IllegalStateException("Chunk $chunkId already has refCount=0"))
        }

        val unpinnedChunk = editorChunk.unpin() as EditorChunk.Text
        _chunks.value = _chunks.value + (chunkId to unpinnedChunk)

        log(tag) { "Unpinned chunk: $chunkId (refCount=${unpinnedChunk.refCount})" }

        return Result.success(Unit)
    }

    /**
     * Pin a chunk, incrementing its reference count.
     * Pinned chunks cannot be evicted from the cache.
     * If the chunk is not in cache, it will be loaded first.
     *
     * @return Result containing the pinned chunk, or failure if chunk cannot be loaded
     */
    suspend fun pinChunk(chunkId: EditorChunk.ChunkId): Result<EditorChunk.Text> = chunkMutex.withLock {
        pinChunkInternal(chunkId)
    }

    /**
     * Unpin a chunk, decrementing its reference count.
     * When refCount reaches 0 and the chunk is clean, it becomes eligible for eviction.
     *
     * @return Result indicating success or failure
     */
    suspend fun unpinChunk(chunkId: EditorChunk.ChunkId): Result<Unit> = chunkMutex.withLock {
        unpinChunkInternal(chunkId)
    }

    /**
     * Pin multiple chunks atomically.
     * All chunks must be successfully pinned, or the operation fails without pinning any.
     *
     * @return Result containing list of pinned chunks, or failure if any chunk cannot be loaded
     */
    suspend fun pinChunks(chunkIds: Set<EditorChunk.ChunkId>): Result<List<EditorChunk.Text>> = chunkMutex.withLock {
        val pinnedChunks = mutableListOf<EditorChunk.Text>()

        try {
            for (chunkId in chunkIds) {
                val pinResult = pinChunkInternal(chunkId)
                if (pinResult.isFailure) {
                    // Rollback: unpin all chunks we already pinned
                    for (alreadyPinned in pinnedChunks) {
                        unpinChunkInternal(alreadyPinned.id)
                    }
                    return@withLock Result.failure(
                        pinResult.exceptionOrNull() ?: Exception("Failed to pin chunk $chunkId")
                    )
                }
                pinnedChunks.add(pinResult.getOrThrow())
            }

            log(tag) { "Pinned ${chunkIds.size} chunks atomically" }
            Result.success(pinnedChunks)
        } catch (e: Exception) {
            // Rollback on exception
            for (pinnedChunk in pinnedChunks) {
                unpinChunkInternal(pinnedChunk.id)
            }
            Result.failure(e)
        }
    }

    /**
     * Unpin multiple chunks atomically.
     *
     * @return Result indicating success or failure
     */
    suspend fun unpinChunks(chunkIds: Set<EditorChunk.ChunkId>): Result<Unit> = chunkMutex.withLock {
        for (chunkId in chunkIds) {
            val result = unpinChunkInternal(chunkId)
            if (result.isFailure) {
                log(tag, WARN) { "Failed to unpin chunk $chunkId: ${result.exceptionOrNull()?.message}" }
                // Continue unpinning other chunks despite failure
            }
        }
        Result.success(Unit)
    }

    /**
     * Execute a block with a pinned chunk, automatically unpinning when done.
     * The chunk is guaranteed to stay in cache for the duration of the block.
     *
     * @param chunkId The chunk to pin
     * @param block The operation to perform with the pinned chunk
     * @return Result containing the block's return value, or failure if chunk cannot be pinned
     */
    suspend fun <T> withPinnedChunk(
        chunkId: EditorChunk.ChunkId,
        block: suspend (EditorChunk.Text) -> T
    ): Result<T> {
        val pinResult = pinChunk(chunkId)
        if (pinResult.isFailure) {
            return Result.failure(pinResult.exceptionOrNull() ?: Exception("Failed to pin chunk"))
        }

        val pinnedChunk = pinResult.getOrThrow()

        return try {
            Result.success(block(pinnedChunk))
        } catch (e: Exception) {
            Result.failure(e)
        } finally {
            unpinChunk(chunkId)
        }
    }

    /**
     * Execute a block with multiple pinned chunks, automatically unpinning when done.
     * All chunks are guaranteed to stay in cache for the duration of the block.
     *
     * @param chunkIds The chunks to pin
     * @param block The operation to perform with the pinned chunks
     * @return Result containing the block's return value, or failure if chunks cannot be pinned
     */
    suspend fun <T> withPinnedChunks(
        chunkIds: Set<EditorChunk.ChunkId>,
        block: suspend (List<EditorChunk.Text>) -> T
    ): Result<T> {
        val pinResult = pinChunks(chunkIds)
        if (pinResult.isFailure) {
            return Result.failure(pinResult.exceptionOrNull() ?: Exception("Failed to pin chunks"))
        }

        val pinnedChunks = pinResult.getOrThrow()

        return try {
            Result.success(block(pinnedChunks))
        } catch (e: Exception) {
            Result.failure(e)
        } finally {
            unpinChunks(chunkIds)
        }
    }

    /**
     * Evict a chunk from the cache.
     *
     * @param chunkId The chunk to evict
     * @param removeFromStructure If true, also removes the chunk's boundary (chunk deleted from file structure).
     *                           If false, keeps boundary (chunk can be reloaded later).
     */
    suspend fun evictChunk(chunkId: EditorChunk.ChunkId, removeFromStructure: Boolean = false): Boolean = chunkMutex.withLock {
        val chunk = _chunks.value[chunkId] ?: return@withLock false

        // Don't evict pinned chunks (dirty or actively referenced)
        if (chunk.isPinned) {
            log(tag) { "Cannot evict pinned chunk: $chunkId (refCount=${chunk.refCount}, isDirty=${chunk.isDirty})" }
            return@withLock false
        }

        log(tag) { "Evicting chunk: $chunkId (removeFromStructure=$removeFromStructure)" }

        _chunks.value = _chunks.value - chunkId
        _loadStates.value = _loadStates.value - chunkId
        chunkAccessOrder.remove(chunkId)

        if (removeFromStructure) {
            // Remove boundary - chunk no longer exists in logical file structure
            boundaries = (boundaries - chunkId).toMutableMap()
        }

        true
    }

    /**
     * Evict a binary chunk from the cache.
     */
    suspend fun evictBinaryChunk(chunkId: EditorChunk.ChunkId, removeFromStructure: Boolean = false): Boolean = chunkMutex.withLock {
        val chunk = getBinaryChunk(chunkId) ?: return@withLock false

        // Don't evict pinned chunks (dirty or actively referenced)
        if (chunk.isPinned) {
            log(tag) { "Cannot evict pinned binary chunk: ${chunkId.value} (refCount=${chunk.refCount}, isDirty=${chunk.isDirty})" }
            return@withLock false
        }

        log(tag) { "Evicting binary chunk: ${chunkId.value} (removeFromStructure=$removeFromStructure)" }

        _chunks.value = _chunks.value - chunkId

        if (removeFromStructure) {
            // Remove boundary - chunk no longer exists in logical file structure
            boundaries = (boundaries - chunkId).toMutableMap()
        }

        true
    }

    /**
     * Update chunk boundaries after an edit operation.
     * Shifts boundaries for chunks after the edit point by deltaLength.
     *
     * Note: This ONLY updates the boundaries map. The chunks themselves keep their
     * original offsets which represent the content they hold, not their file position.
     *
     * @param editOffset The file offset where the edit occurred
     * @param deltaLength The change in length (positive for insert, negative for delete)
     */
    suspend fun updateBoundaries(editOffset: Long, deltaLength: Long, deltaLines: Int = 0) = chunkMutex.withLock {
        if (deltaLength == 0L && deltaLines == 0) return@withLock

        val updatedBoundaries = mutableMapOf<EditorChunk.ChunkId, ChunkBoundary>()
        var adjustedCount = 0

        for ((chunkId, boundary) in boundaries) {
            // Only update text chunks
            if (_chunks.value[chunkId] !is EditorChunk.Text && _chunks.value[chunkId] != null) continue

            val newBoundary = when {
                // Chunk entirely after edit point - shift both offsets, keep line count
                // Use > (not >=) because edit at startOffset means edit is WITHIN chunk, not after it
                boundary.startOffset > editOffset -> {
                    adjustedCount++
                    ChunkBoundary(
                        boundary.startOffset + deltaLength,
                        boundary.endOffset + deltaLength,
                        boundary.lineCount
                    )
                }
                // Chunk contains or ends at edit point - adjust end offset and line count
                boundary.endOffset >= editOffset -> {
                    adjustedCount++
                    ChunkBoundary(
                        boundary.startOffset,
                        boundary.endOffset + deltaLength,
                        boundary.lineCount + deltaLines
                    )
                }
                // Chunk entirely before edit point - no change
                else -> boundary
            }

            updatedBoundaries[chunkId] = newBoundary
        }

        // Merge updated boundaries back (only replaces text chunk boundaries)
        boundaries = (boundaries + updatedBoundaries).toMutableMap()

        log(tag) { "Updated $adjustedCount chunk boundaries after edit at offset $editOffset (delta=$deltaLength bytes, $deltaLines lines)" }
    }

    /**
     * Update binary chunk boundaries after an edit operation.
     * Similar to updateBoundaries() but for binary chunks.
     */
    suspend fun updateBinaryBoundaries(editOffset: Long, deltaLength: Long) = chunkMutex.withLock {
        if (deltaLength == 0L) return@withLock

        val updatedBoundaries = mutableMapOf<EditorChunk.ChunkId, ChunkBoundary>()
        var adjustedCount = 0

        // Only process binary chunks
        for ((chunkId, boundary) in boundaries) {
            // Skip non-binary chunks
            if (_chunks.value[chunkId] !is EditorChunk.Binary) continue

            val newBoundary = when {
                // Chunk entirely after edit point - shift both offsets
                boundary.startOffset > editOffset -> {
                    adjustedCount++
                    ChunkBoundary(
                        boundary.startOffset + deltaLength,
                        boundary.endOffset + deltaLength,
                        0  // Binary chunks don't track lines
                    )
                }
                // Chunk contains or ends at edit point - adjust end offset
                boundary.endOffset >= editOffset -> {
                    adjustedCount++
                    ChunkBoundary(
                        boundary.startOffset,
                        boundary.endOffset + deltaLength,
                        0  // Binary chunks don't track lines
                    )
                }
                // Chunk entirely before edit point - no change
                else -> boundary
            }

            updatedBoundaries[chunkId] = newBoundary
        }

        // Merge updated boundaries back (only replaces binary chunk boundaries)
        boundaries = (boundaries + updatedBoundaries).toMutableMap()

        log(tag) { "Updated $adjustedCount binary chunk boundaries after edit at offset $editOffset (delta=$deltaLength bytes)" }
    }

    private fun evictOldChunksIfNeeded() {
        // NOTE: This should be called from within chunkMutex.withLock
        val currentCount = _chunks.value.size
        if (currentCount <= maxCachedChunks) {
            return
        }

        val chunksToEvict = currentCount - maxCachedChunks
        log(tag) { "Cache full ($currentCount/$maxCachedChunks chunks), evicting $chunksToEvict oldest chunks" }

        // Take atomic snapshot to avoid stale reads during iteration
        val chunksSnapshot = _chunks.value
        val dirtyCount = chunksSnapshot.count { it.value.isDirty }
        log(tag, DEBUG) { "Cache state: ${chunksSnapshot.size} chunks, $dirtyCount dirty" }

        // Filter to only unpinned chunks (refCount=0 and clean)
        val evictCandidates = chunkAccessOrder
            .take(chunksToEvict * 2)  // Take extra candidates in case some are pinned
            .mapNotNull { chunkId ->
                val chunk = chunksSnapshot[chunkId]
                if (chunk != null && !chunk.isPinned) {
                    chunkId
                } else {
                    if (chunk != null) {
                        log(tag, DEBUG) { "Skipping chunk $chunkId for eviction (isPinned=${chunk.isPinned}, isDirty=${chunk.isDirty}, refCount=${chunk.refCount})" }
                    }
                    null
                }
            }
            .take(chunksToEvict)

        if (evictCandidates.isEmpty()) {
            val pinnedCount = chunksSnapshot.count { it.value.isPinned }
            log(tag, WARN) {
                "Cannot evict: all LRU chunks are pinned ($pinnedCount/${currentCount} chunks pinned)"
            }
            return
        }

        // Evict candidates
        var updatedChunks = _chunks.value
        var updatedLoadStates = _loadStates.value

        for (chunkId in evictCandidates) {
            updatedChunks = updatedChunks - chunkId
            updatedLoadStates = updatedLoadStates - chunkId
            chunkAccessOrder.remove(chunkId)
            log(tag) { "Evicted chunk: $chunkId (LRU)" }
        }

        // Atomic update
        _chunks.value = updatedChunks
        _loadStates.value = updatedLoadStates
        // NOTE: Boundaries are NOT removed - they're needed to reload chunks later

        log(tag) { "Evicted ${evictCandidates.size} chunks, cache now ${_chunks.value.size}/${maxCachedChunks}" }
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
        // Get dirty chunk IDs first (before pinning) - only text chunks
        val dirtyChunkIds = chunkMutex.withLock {
            _chunks.value.values
                .filterIsInstance<EditorChunk.Text>()
                .filter { it.isDirty }
                .map { it.id }
                .toSet()
        }

        if (dirtyChunkIds.isEmpty()) {
            log(tag) { "No dirty chunks to save" }
            return Result.success(Unit)
        }

        log(tag) { "Saving ${dirtyChunkIds.size} dirty chunks with pinning protection" }

        // Pin all dirty chunks to prevent eviction during save
        // withPinnedChunks will wrap the result, so we return Unit directly (not Result<Unit>)
        return withPinnedChunks(dirtyChunkIds) { pinnedChunks ->
            // Get snapshot of boundaries for save operation
            val boundariesSnapshot = chunkMutex.withLock {
                boundaries
                    .filterKeys { _chunks.value[it] is EditorChunk.Text || _chunks.value[it] == null }
                    .toMap()
            }

            // Save all chunks - throws on failure, withPinnedChunks will catch and return Result.failure
            chunkRepository?.saveFile(pinnedChunks, boundariesSnapshot)
                ?: throw IllegalStateException("ChunkRepository not available")

            // Only mark clean if save succeeded (if exception thrown above, this won't execute)
            markChunksClean(dirtyChunkIds.toList())

            log(tag) { "Successfully committed ${pinnedChunks.size} chunks" }
            // Return Unit, withPinnedChunks will wrap in Result.success
        }
    }

    /**
     * Returns all dirty chunks sorted by startOffset.
     * Used for batch save operations where we need all modified chunks in order.
     */
    suspend fun getDirtyChunks(): List<EditorChunk.Text> = chunkMutex.withLock {
        val dirtyEditorChunks = _chunks.value.values.filterIsInstance<EditorChunk.Text>().filter { it.isDirty }
        log(tag) { "Found ${dirtyEditorChunks.size} dirty chunks" }
        // Sort by boundary startOffset (using boundaries map as source of truth)
        dirtyEditorChunks
            .sortedBy { chunk -> boundaries[chunk.id]?.startOffset ?: Long.MAX_VALUE }
    }

    /**
     * Marks multiple chunks as clean after successful save.
     * This is a batch operation to avoid multiple StateFlow updates.
     *
     * @param chunkIds List of chunk IDs to mark as clean
     */
    suspend fun markChunksClean(chunkIds: List<EditorChunk.ChunkId>): Unit = chunkMutex.withLock {
        log(tag) { "Marking ${chunkIds.size} chunks as clean" }

        val updatedChunks = _chunks.value.toMutableMap()
        var markedCount = 0

        for (chunkId in chunkIds) {
            val chunk = updatedChunks[chunkId]
            if (chunk != null && chunk.isDirty) {
                // markClean is a method on EditorChunk, not TextChunk
                val cleanChunk = when (chunk) {
                    is EditorChunk.Text -> chunk.copy(isDirty = false)
                    is EditorChunk.Binary -> chunk.copy(isDirty = false)
                }
                updatedChunks[chunkId] = cleanChunk
                markedCount++
            }
        }

        _chunks.value = updatedChunks
        log(tag) { "Successfully marked $markedCount chunks as clean" }
    }

    suspend fun generateChunkIds(fileSize: Long): List<EditorChunk.ChunkId> = chunkMutex.withLock {
        // Reset counter for deterministic ID generation
        EditorChunk.ChunkId.resetCounter()

        // Clear old boundaries
        boundaries.clear()

        val chunkIds = mutableListOf<EditorChunk.ChunkId>()

        // Handle empty files - create one empty chunk
        if (fileSize == 0L) {
            val id = EditorChunk.ChunkId.generate()
            boundaries[id] = ChunkBoundary(0L, 0L, lineCount = 1)
            chunkIds.add(id)
            log(tag) { "Generated 1 chunk ID for empty file" }
            return@withLock chunkIds
        }

        var offset = 0L
        while (offset < fileSize) {
            val id = EditorChunk.ChunkId.generate()
            val endOffset = minOf(offset + chunkSize, fileSize)

            // Store boundary for this chunk (lineCount=0 sentinel, will be populated by buildChunkMetadata)
            boundaries[id] = ChunkBoundary(offset, endOffset, lineCount = 0)

            chunkIds.add(id)
            offset += chunkSize
        }

        log(tag) { "Generated ${chunkIds.size} chunk IDs with boundaries for ${fileSize} bytes" }
        return@withLock chunkIds
    }

    /**
     * Generate binary chunk IDs and boundaries for a file of given size.
     * Similar to generateChunkIds() but for binary chunks.
     */
    suspend fun generateBinaryChunkIds(fileSize: Long): List<EditorChunk.ChunkId> = chunkMutex.withLock {
        // Reset counter for deterministic ID generation
        EditorChunk.ChunkId.resetCounter()

        // Clear old binary boundaries (only those for binary chunks)
        boundaries = boundaries.filterKeys { chunkId ->
            _chunks.value[chunkId] !is EditorChunk.Binary
        }.toMutableMap()

        val chunkIds = mutableListOf<EditorChunk.ChunkId>()

        // Handle empty files - create one empty chunk
        if (fileSize == 0L) {
            val id = EditorChunk.ChunkId.generate()
            boundaries[id] = ChunkBoundary(0L, 0L, lineCount = 0)
            chunkIds.add(id)
            log(tag) { "Generated 1 binary chunk ID for empty file" }
            return@withLock chunkIds
        }

        var offset = 0L
        while (offset < fileSize) {
            val id = EditorChunk.ChunkId.generate()
            val endOffset = minOf(offset + chunkSize, fileSize)
            boundaries[id] = ChunkBoundary(
                startOffset = offset,
                endOffset = endOffset,
                lineCount = 0  // Binary chunks don't track lines
            )
            chunkIds.add(id)
            offset = endOffset
        }

        log(tag) { "Generated ${chunkIds.size} binary chunk IDs with boundaries for ${fileSize} bytes" }
        return@withLock chunkIds
    }

    suspend fun clear() = chunkMutex.withLock {
        log(tag) { "Clearing all chunks" }
        _chunks.value = emptyMap()
        _loadStates.value = emptyMap()
        boundaries.clear()
        chunkAccessOrder.clear()
    }

    // ==================== Binary Chunk Methods ====================
    // Now unified with text chunks using EditorChunk sealed class

    suspend fun addBinaryChunk(chunk: EditorChunk.Binary): Unit = chunkMutex.withLock {
        _chunks.value = _chunks.value + (chunk.id to chunk)
        log(tag) { "Added binary chunk: ${chunk.id.value}" }
    }

    suspend fun loadBinaryChunk(chunkId: EditorChunk.ChunkId): Result<EditorChunk.Binary> = chunkMutex.withLock {
        log(tag) { "Loading binary chunk: ${chunkId.value}" }

        // Check if already loaded
        getBinaryChunk(chunkId)?.let { existingChunk ->
            log(tag) { "Binary chunk ${chunkId.value} already loaded (cached)" }
            return@withLock Result.success(existingChunk)
        }

        try {
            // Get boundary for this chunk
            val boundary = boundaries[chunkId]
                ?: return@withLock Result.failure(IllegalArgumentException("No boundary found for binary chunk: ${chunkId.value}"))

            // Load from repository
            log(tag) { "Loading binary chunk ${chunkId.value} from disk" }
            // TODO Phase 3: Use proper BinaryChunkRepository here
            // For now, this will be called after initialization when chunk already exists
            return@withLock Result.failure(UnsupportedOperationException("Binary chunk loading from disk not yet implemented"))

        } catch (e: Exception) {
            log(tag, ERROR) { "Failed to load binary chunk: ${chunkId.value} - ${e.asLog()}" }
            Result.failure(e)
        }
    }

    suspend fun getBinaryChunksInRange(startOffset: Long, endOffset: Long): List<EditorChunk.Binary> = chunkMutex.withLock {
        val relevantChunkIds = boundaries.filter { (chunkId, boundary) ->
            // Only binary chunks
            _chunks.value[chunkId] is EditorChunk.Binary &&
            boundary.endOffset >= startOffset && boundary.startOffset < endOffset
        }.keys

        log(tag) { "Found ${relevantChunkIds.size} binary chunks in range $startOffset-$endOffset" }

        relevantChunkIds.mapNotNull { getBinaryChunk(it) }
    }

    suspend fun updateBinaryChunk(chunkId: EditorChunk.ChunkId, updater: (EditorChunk.Binary) -> EditorChunk.Binary): EditorChunk.Binary? =
        chunkMutex.withLock {
            val currentChunk = getBinaryChunk(chunkId) ?: return@withLock null
            val updatedChunk = updater(currentChunk)
            _chunks.value = _chunks.value + (chunkId to updatedChunk)
            log(tag, DEBUG) { "Updated binary chunk ${chunkId.value}: size=${updatedChunk.size}, dirty=${updatedChunk.isDirty}" }
            updatedChunk
        }

    suspend fun <T> withPinnedBinaryChunk(
        chunkId: EditorChunk.ChunkId,
        block: suspend (EditorChunk.Binary) -> T
    ): Result<T> {
        // Pin chunk (with mutex)
        val pinnedChunk = chunkMutex.withLock {
            val chunk = getBinaryChunk(chunkId)
                ?: return Result.failure(IllegalArgumentException("Binary chunk not found: ${chunkId.value}"))
            val pinned = chunk.pin() as EditorChunk.Binary
            _chunks.value = _chunks.value + (chunkId to pinned)
            pinned
        }

        // Execute block WITHOUT holding mutex (prevents deadlock)
        return try {
            val result = block(pinnedChunk)

            // Unpin chunk (with mutex)
            chunkMutex.withLock {
                val currentChunk = getBinaryChunk(chunkId)
                if (currentChunk != null && currentChunk.refCount > 0) {
                    val unpinned = currentChunk.unpin() as EditorChunk.Binary
                    _chunks.value = _chunks.value + (chunkId to unpinned)
                }
            }

            Result.success(result)
        } catch (e: Exception) {
            // Unpin on error (with mutex)
            chunkMutex.withLock {
                val currentChunk = getBinaryChunk(chunkId)
                if (currentChunk != null && currentChunk.refCount > 0) {
                    val unpinned = currentChunk.unpin() as EditorChunk.Binary
                    _chunks.value = _chunks.value + (chunkId to unpinned)
                }
            }
            Result.failure(e)
        }
    }

    /**
     * Get all dirty binary chunks.
     */
    suspend fun getDirtyBinaryChunks(): List<EditorChunk.Binary> = chunkMutex.withLock {
        _chunks.value.values.filterIsInstance<EditorChunk.Binary>().filter { it.isDirty }
    }

    suspend fun <T> withPinnedBinaryChunks(
        chunkIds: Set<EditorChunk.ChunkId>,
        block: suspend (List<EditorChunk.Binary>) -> T
    ): Result<T> {
        // Pin all chunks (with mutex)
        val pinnedChunks = chunkMutex.withLock {
            val chunks = chunkIds.mapNotNull { getBinaryChunk(it) }
            if (chunks.size != chunkIds.size) {
                return Result.failure(IllegalArgumentException("Some binary chunks not found"))
            }

            val updatedChunks = mutableMapOf<EditorChunk.ChunkId, EditorChunk>()
            chunks.map { chunk ->
                val pinnedChunk = chunk.pin() as EditorChunk.Binary
                updatedChunks[chunk.id] = pinnedChunk
                pinnedChunk
            }.also {
                _chunks.value = _chunks.value + updatedChunks
            }
        }

        // Execute block WITHOUT holding mutex (prevents deadlock)
        return try {
            val result = block(pinnedChunks)

            // Unpin all chunks (with mutex)
            chunkMutex.withLock {
                val updatedChunks = mutableMapOf<EditorChunk.ChunkId, EditorChunk>()
                for (chunkId in chunkIds) {
                    val currentChunk = getBinaryChunk(chunkId)
                    if (currentChunk != null && currentChunk.refCount > 0) {
                        val unpinned = currentChunk.unpin() as EditorChunk.Binary
                        updatedChunks[chunkId] = unpinned
                    }
                }
                _chunks.value = _chunks.value + updatedChunks
            }

            Result.success(result)
        } catch (e: Exception) {
            // Unpin all on error (with mutex)
            chunkMutex.withLock {
                val updatedChunks = mutableMapOf<EditorChunk.ChunkId, EditorChunk>()
                for (chunkId in chunkIds) {
                    val currentChunk = getBinaryChunk(chunkId)
                    if (currentChunk != null && currentChunk.refCount > 0) {
                        val unpinned = currentChunk.unpin() as EditorChunk.Binary
                        updatedChunks[chunkId] = unpinned
                    }
                }
                _chunks.value = _chunks.value + updatedChunks
            }
            Result.failure(e)
        }
    }

    @AssistedFactory
    interface Factory {
        fun create(
            workspaceId: Workspace.Id,
            chunkRepository: ChunkRepository,
            chunkSize: Long = DEFAULT_CHUNK_SIZE
        ): ChunkManager
    }

    companion object {
        const val DEFAULT_CHUNK_SIZE = 64 * 1024L // 64KB for testing
        const val DEFAULT_MAX_CACHED_CHUNKS = 5 // Keep 5 chunks in memory (320KB with 64KB chunks)

        /**
         * Merges dirty chunks into original file content, correctly handling chunk size changes.
         *
         * Algorithm:
         * 1. Process chunks in order (sorted by startOffset from boundaries)
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
         * @param dirtyChunks List of modified chunks (will be sorted by startOffset from boundaries)
         * @param boundaries Map of chunk IDs to their original positions in the file
         * @return Merged byte array with all modifications applied
         */
        fun mergeChunks(
            originalContent: ByteArray,
            dirtyChunks: List<EditorChunk.Text>,
            boundaries: Map<EditorChunk.ChunkId, ChunkBoundary>
        ): ByteArray {
            if (dirtyChunks.isEmpty()) return originalContent

            // Sort by boundary startOffset
            val sortedChunks = dirtyChunks.sortedBy { chunk ->
                boundaries[chunk.id]?.startOffset ?: Long.MAX_VALUE
            }
            val result = mutableListOf<Byte>()
            var currentOriginalPos = 0L

            for (chunk in sortedChunks) {
                val boundary = boundaries[chunk.id]
                    ?: throw IllegalStateException("No boundary found for chunk ${chunk.id}")

                // Copy unchanged content before this chunk
                if (currentOriginalPos < boundary.startOffset) {
                    val unchangedStart = currentOriginalPos.toInt()
                    val unchangedEnd = boundary.startOffset.toInt()
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
                currentOriginalPos = boundary.endOffset
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

/**
 * Tracks the offset boundaries of a chunk.
 * Chunk IDs are sequential/opaque, boundaries track actual file positions.
 */
data class ChunkBoundary(
    val startOffset: Long,
    val endOffset: Long,
    val lineCount: Int
) {
    val size: Long get() = endOffset - startOffset
}