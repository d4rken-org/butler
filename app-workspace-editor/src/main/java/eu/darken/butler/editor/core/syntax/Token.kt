package eu.darken.butler.editor.core.syntax

enum class TokenType {
    KEYWORD,
    STRING,
    NUMBER,
    COMMENT,
    EMPHASIS,
}

/**
 * A styled span within a single line. [start]/[end] are RAW char offsets into the tokenized line
 * (end-exclusive) - NOT display/tab-expanded columns; the UI remaps them at render time.
 *
 * Invariants for a tokenizer's output list (see [isValidTokenizationOf]): every token is within
 * the line's bounds, non-empty, and the list is sorted by [start] with no overlaps.
 */
data class Token(
    val start: Int,
    val end: Int,
    val type: TokenType,
)

/** Invariant check used by tests (and debug assertions): bounded, non-empty, sorted, non-overlapping. */
fun List<Token>.isValidTokenizationOf(line: String): Boolean {
    var previousEnd = 0
    for (token in this) {
        if (token.start < previousEnd) return false
        if (token.start >= token.end) return false
        if (token.end > line.length) return false
        previousEnd = token.end
    }
    return true
}
