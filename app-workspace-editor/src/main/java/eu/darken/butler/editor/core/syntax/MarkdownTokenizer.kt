package eu.darken.butler.editor.core.syntax

/**
 * Markdown (GitHub-basic source highlighting): ATX headings, fenced code blocks (``` and ~~~,
 * CommonMark close rule: same char, at least opening length), inline code spans, bold/italic,
 * blockquotes. Links and list markers stay unstyled. Cross-line state: [LineState.MdFencedCode].
 *
 * Documented simplification: inline constructs (code spans, emphasis) are line-local - CommonMark
 * allows them to span lines, this tokenizer doesn't.
 */
internal class MarkdownTokenizer : SyntaxTokenizer {

    override fun tokenize(line: String, startState: LineState): TokenizeResult {
        if (startState is LineState.MdFencedCode) {
            val endState = if (isClosingFence(line, startState)) LineState.Default else startState
            val tokens = if (line.isEmpty()) emptyList() else listOf(Token(0, line.length, TokenType.STRING))
            return TokenizeResult(tokens, endState)
        }

        val indent = leadingSpaces(line)
        if (indent <= 3) {
            val afterIndent = indent
            openingFence(line, afterIndent)?.let { fence ->
                return TokenizeResult(listOf(Token(0, line.length, TokenType.STRING)), fence)
            }
            if (isAtxHeading(line, afterIndent)) {
                return TokenizeResult(listOf(Token(0, line.length, TokenType.KEYWORD)), LineState.Default)
            }
            if (afterIndent < line.length && line[afterIndent] == '>') {
                return TokenizeResult(listOf(Token(0, line.length, TokenType.COMMENT)), LineState.Default)
            }
        }

        return TokenizeResult(tokenizeInline(line), LineState.Default)
    }

    private fun leadingSpaces(line: String): Int {
        var i = 0
        while (i < line.length && line[i] == ' ') i++
        return i
    }

    /** A fence opener at [from]: 3+ backticks or tildes. Returns the resulting state or null. */
    private fun openingFence(line: String, from: Int): LineState.MdFencedCode? {
        if (from >= line.length) return null
        val c = line[from]
        if (c != '`' && c != '~') return null
        var i = from
        while (i < line.length && line[i] == c) i++
        val runLength = i - from
        if (runLength < 3) return null
        return LineState.MdFencedCode(fenceChar = c, fenceLength = runLength)
    }

    private fun isClosingFence(line: String, state: LineState.MdFencedCode): Boolean {
        val from = leadingSpaces(line)
        if (from > 3 || from >= line.length) return false
        var i = from
        while (i < line.length && line[i] == state.fenceChar) i++
        if (i - from < state.fenceLength) return false
        while (i < line.length) {
            if (line[i] != ' ' && line[i] != '\t') return false
            i++
        }
        return true
    }

    private fun isAtxHeading(line: String, from: Int): Boolean {
        var i = from
        while (i < line.length && line[i] == '#') i++
        val hashes = i - from
        if (hashes !in 1..6) return false
        return i >= line.length || line[i] == ' ' || line[i] == '\t'
    }

    private fun tokenizeInline(line: String): List<Token> {
        val tokens = mutableListOf<Token>()
        var i = 0
        while (i < line.length) {
            val c = line[i]
            when {
                c == '\\' -> i += 2
                c == '`' -> {
                    var runEnd = i
                    while (runEnd < line.length && line[runEnd] == '`') runEnd++
                    val runLength = runEnd - i
                    val close = findBacktickRun(line, runEnd, runLength)
                    if (close != -1) {
                        tokens += Token(i, close + runLength, TokenType.STRING)
                        i = close + runLength
                    } else {
                        i = runEnd
                    }
                }
                (c == '*' || c == '_') -> {
                    val consumed = scanEmphasis(line, i, tokens)
                    i += consumed
                }
                else -> i++
            }
        }
        return tokens
    }

    /** Next run of EXACTLY [length] backticks at/after [from] (CommonMark code-span close rule). */
    private fun findBacktickRun(line: String, from: Int, length: Int): Int {
        var i = from
        while (i < line.length) {
            if (line[i] == '`') {
                var runEnd = i
                while (runEnd < line.length && line[runEnd] == '`') runEnd++
                if (runEnd - i == length) return i
                i = runEnd
            } else {
                i++
            }
        }
        return -1
    }

    /** Emits a bold/italic token at [i] when a same-line closer exists; returns chars consumed. */
    private fun scanEmphasis(line: String, i: Int, tokens: MutableList<Token>): Int {
        val c = line[i]
        val isDouble = i + 1 < line.length && line[i + 1] == c
        val markerLength = if (isDouble) 2 else 1
        val contentStart = i + markerLength
        // Underscore emphasis doesn't open intraword (snake_case stays literal)
        if (c == '_' && i > 0 && (line[i - 1].isLetterOrDigit() || line[i - 1] == '_') && !isDouble) return 1
        if (contentStart >= line.length || line[contentStart] == ' ') return markerLength
        val marker = if (isDouble) "$c$c" else "$c"
        var close = line.indexOf(marker, startIndex = contentStart)
        // A closer directly after the opener (empty content) isn't emphasis
        while (close == contentStart) {
            close = line.indexOf(marker, startIndex = close + marker.length)
        }
        if (close == -1) return markerLength
        tokens += Token(i, close + markerLength, TokenType.EMPHASIS)
        return (close + markerLength) - i
    }
}
