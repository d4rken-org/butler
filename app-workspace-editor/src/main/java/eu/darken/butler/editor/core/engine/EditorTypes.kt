package eu.darken.butler.editor.core.engine

/**
 * Line ending style detected in text content.
 */
enum class LineEnding {
    LF,      // \n (Unix/Linux/macOS)
    CRLF,    // \r\n (Windows)
    CR,      // \r (old Mac OS) - rare
    MIXED    // Inconsistent line endings
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