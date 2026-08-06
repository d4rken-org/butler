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

/**
 * End of [text] when it is placed at [start]. [endOffset] stays the caller's business - the insert,
 * replace and undo/redo paths anchor it differently - only the line/column walk is shared.
 *
 * "\r\n", a lone "\r" and a lone "\n" each count as exactly ONE break (CRLF consumed as a single
 * break, not two): the buffer exposes a trailing empty line for all three, so an LF-only scan
 * leaves the cursor a line short on any text carrying a lone CR. That cursor is published before
 * the window refresh, so the line it should have landed on would never load.
 */
internal fun endPositionOf(start: TextPosition, text: String, endOffset: Long): TextPosition {
    var line = start.line
    var column = start.column
    var index = 0
    while (index < text.length) {
        when (text[index]) {
            '\r' -> {
                line++
                column = 0
                if (index + 1 < text.length && text[index + 1] == '\n') index++
            }
            '\n' -> {
                line++
                column = 0
            }
            else -> column++
        }
        index++
    }
    return TextPosition(offset = endOffset, line = line, column = column)
}
