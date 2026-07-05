package eu.darken.butler.editor.core.engine

import java.util.concurrent.atomic.AtomicInteger

data class TextChunk(
    val id: ChunkId,
    val content: String,
    val lineCount: Int,
    val lineEnding: LineEnding = LineEnding.LF,
    val isDirty: Boolean = false,
    val isLoaded: Boolean = true,
    val refCount: Int = 0,
) {
    val size: Long get() = content.length.toLong()
    val isEmpty: Boolean get() = content.isEmpty()

    /**
     * A chunk is pinned if it has active references (refCount > 0) or is dirty.
     * Pinned chunks cannot be evicted from the cache.
     */
    val isPinned: Boolean get() = refCount > 0 || isDirty

    // Note: Position queries (containsOffset, containsLine) should use ChunkManager.getBoundary()
    // instead of storing positions in chunks. Chunks represent content, boundaries represent positions.

    fun markDirty(): TextChunk = copy(isDirty = true)

    fun markClean(): TextChunk = copy(isDirty = false)

    /**
     * Increment the reference count, marking this chunk as actively used.
     * Pinned chunks cannot be evicted from the cache.
     */
    fun pin(): TextChunk = copy(refCount = refCount + 1)

    /**
     * Decrement the reference count. When refCount reaches 0 and chunk is clean,
     * it becomes eligible for eviction.
     */
    fun unpin(): TextChunk {
        require(refCount > 0) { "Cannot unpin chunk $id with refCount=$refCount" }
        return copy(refCount = refCount - 1)
    }

    @JvmInline
    value class ChunkId(val value: String) {
        companion object {
            private val counter = AtomicInteger(0)

            fun generate(): ChunkId = ChunkId("chunk_${counter.getAndIncrement()}")

            fun resetCounter() {
                counter.set(0)
            }
        }
    }
}

data class ChunkLoadState(
    val isLoading: Boolean = false,
    val error: Throwable? = null,
    val loadedAt: Long? = null
) {
    val isLoaded: Boolean get() = loadedAt != null && error == null
    val hasError: Boolean get() = error != null
}