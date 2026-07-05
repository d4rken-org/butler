package eu.darken.butler.editor.core.engine

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
