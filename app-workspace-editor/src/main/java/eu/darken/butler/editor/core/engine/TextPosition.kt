package eu.darken.butler.editor.core.engine

data class TextPosition(
    val offset: Long,
    val line: Int,
    val column: Int
) {
    companion object {
        val ZERO = TextPosition(0, 0, 0)
    }
}
