package eu.darken.butler.editor.core

data class TextChunk(
    val id: ChunkId,
    val startOffset: Long,
    val endOffset: Long,
    val content: String,
    val lineCount: Int,
    val isDirty: Boolean = false,
    val isLoaded: Boolean = true
) {
    val size: Long get() = endOffset - startOffset
    val isEmpty: Boolean get() = content.isEmpty()
    
    fun containsOffset(offset: Long): Boolean = offset in startOffset until endOffset
    
    fun containsLine(lineNumber: Int): Boolean {
        // This will be calculated based on line tracking in the buffer
        return false // Placeholder - will be implemented with line index
    }
    
    fun markDirty(): TextChunk = copy(isDirty = true)
    
    fun markClean(): TextChunk = copy(isDirty = false)
    
    @JvmInline
    value class ChunkId(val value: String) {
        companion object {
            fun generate(startOffset: Long): ChunkId = ChunkId("chunk_$startOffset")
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