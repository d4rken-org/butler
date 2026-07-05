package eu.darken.butler.editor.core.engine

/**
 * [line] is Long end-to-end so line addressing stays exact on documents beyond 2^31 lines;
 * narrowing to Int happens only where a Compose/framework API forces it (always saturating).
 * [column] stays Int: it indexes a Kotlin String / Compose text layout, a hard Int ceiling.
 */
data class TextPosition(
    val offset: Long,
    val line: Long,
    val column: Int,
) {
    companion object {
        val ZERO = TextPosition(0, 0, 0)
    }
}
