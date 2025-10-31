package eu.darken.butler.editor.core.engine

import java.util.concurrent.atomic.AtomicInteger

data class TextChunk(
    val id: ChunkId,
    val content: String,
    val lineCount: Int,
    val isDirty: Boolean = false,
    val isLoaded: Boolean = true
) {
    val size: Long get() = content.length.toLong()
    val isEmpty: Boolean get() = content.isEmpty()

    // Note: Position queries (containsOffset, containsLine) should use ChunkManager.getBoundary()
    // instead of storing positions in chunks. Chunks represent content, boundaries represent positions.

    fun markDirty(): TextChunk = copy(isDirty = true)

    fun markClean(): TextChunk = copy(isDirty = false)
    
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