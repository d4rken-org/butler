package eu.darken.butler.editor.core.engine

import java.util.concurrent.atomic.AtomicInteger

/**
 * Line ending style detected in text content.
 */
enum class LineEnding {
    LF,      // \n (Unix/Linux/macOS)
    CRLF,    // \r\n (Windows)
    CR,      // \r (old Mac OS) - rare
    MIXED    // Inconsistent line endings
}

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

data class TextPosition(
    val offset: Long,
    val line: Int,
    val column: Int
) {
    companion object {
        val ZERO = TextPosition(0, 0, 0)
    }
}

sealed interface EditOperation {
    val position: TextPosition
    val timestamp: Long

    data class Insert(
        override val position: TextPosition,
        val text: String,
        override val timestamp: Long = System.currentTimeMillis()
    ) : EditOperation

    data class Delete(
        override val position: TextPosition,
        val length: Int,
        val deletedText: String,
        override val timestamp: Long = System.currentTimeMillis()
    ) : EditOperation

    data class Replace(
        override val position: TextPosition,
        val oldText: String,
        val newText: String,
        override val timestamp: Long = System.currentTimeMillis()
    ) : EditOperation
}

data class ChunkLoadState(
    val isLoading: Boolean = false,
    val error: Throwable? = null,
    val loadedAt: Long? = null
) {
    val isLoaded: Boolean get() = loadedAt != null && error == null
    val hasError: Boolean get() = error != null
}