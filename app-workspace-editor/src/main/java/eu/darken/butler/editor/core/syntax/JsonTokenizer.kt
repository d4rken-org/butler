package eu.darken.butler.editor.core.syntax

/** JSON: strings (with escapes), numbers, true/false/null. Stateless - no multi-line constructs. */
internal class JsonTokenizer : SyntaxTokenizer {

    override fun tokenize(line: String, startState: LineState): TokenizeResult {
        if (line.isEmpty()) return TokenizeResult.EMPTY
        val tokens = mutableListOf<Token>()
        var i = 0
        while (i < line.length) {
            val c = line[i]
            when {
                c == '"' -> {
                    val end = scanStringEnd(line, i)
                    tokens += Token(i, end, TokenType.STRING)
                    i = end
                }
                c == '-' && i + 1 < line.length && line[i + 1].isDigit() || c.isDigit() -> {
                    val end = scanNumberEnd(line, i)
                    tokens += Token(i, end, TokenType.NUMBER)
                    i = end
                }
                c.isLetter() -> {
                    var end = i
                    while (end < line.length && line[end].isLetter()) end++
                    when (line.substring(i, end)) {
                        "true", "false", "null" -> tokens += Token(i, end, TokenType.KEYWORD)
                    }
                    i = end
                }
                else -> i++
            }
        }
        return TokenizeResult(tokens, LineState.Default)
    }

    /** End index (exclusive) of a string starting at [start]; unterminated runs to end of line. */
    private fun scanStringEnd(line: String, start: Int): Int {
        var i = start + 1
        while (i < line.length) {
            when (line[i]) {
                '\\' -> i += 2
                '"' -> return i + 1
                else -> i++
            }
        }
        return line.length
    }

    private fun scanNumberEnd(line: String, start: Int): Int {
        var i = if (line[start] == '-') start + 1 else start
        while (i < line.length && (line[i].isDigit() || line[i] == '.')) i++
        if (i < line.length && (line[i] == 'e' || line[i] == 'E')) {
            i++
            if (i < line.length && (line[i] == '+' || line[i] == '-')) i++
            while (i < line.length && line[i].isDigit()) i++
        }
        return i
    }
}
