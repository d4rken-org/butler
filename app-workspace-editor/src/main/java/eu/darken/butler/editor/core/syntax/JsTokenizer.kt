package eu.darken.butler.editor.core.syntax

/**
 * JavaScript: line/block comments, single/double-quoted strings (including backslash
 * line-continuation), template literals, numbers, keywords. Cross-line state:
 * [LineState.JsBlockComment], [LineState.JsTemplateLiteral], [LineState.JsString].
 *
 * Documented simplifications: template literals are ONE string span - interior `${...}`
 * expressions are not re-tokenized, and a nested template's inner backtick closes the outer span
 * early (cosmetic). Regex literals are not recognized (a `//` inside one reads as a comment).
 */
internal class JsTokenizer : SyntaxTokenizer {

    override fun tokenize(line: String, startState: LineState): TokenizeResult {
        val tokens = mutableListOf<Token>()
        var i = 0
        when (startState) {
            LineState.JsBlockComment -> {
                val close = line.indexOf("*/")
                if (close == -1) {
                    if (line.isNotEmpty()) tokens += Token(0, line.length, TokenType.COMMENT)
                    return TokenizeResult(tokens, LineState.JsBlockComment)
                }
                tokens += Token(0, close + 2, TokenType.COMMENT)
                i = close + 2
            }
            LineState.JsTemplateLiteral -> {
                val close = scanTemplateEnd(line, 0)
                if (close == -1) {
                    if (line.isNotEmpty()) tokens += Token(0, line.length, TokenType.STRING)
                    return TokenizeResult(tokens, LineState.JsTemplateLiteral)
                }
                tokens += Token(0, close, TokenType.STRING)
                i = close
            }
            is LineState.JsString -> {
                val scan = scanQuotedBody(line, 0, startState.quote)
                if (scan.end > 0) tokens += Token(0, scan.end, TokenType.STRING)
                if (scan.continues) return TokenizeResult(tokens, startState)
                i = scan.end
            }
            else -> Unit // Foreign or Default states both scan from Default
        }

        while (i < line.length) {
            val c = line[i]
            when {
                c == '/' && i + 1 < line.length && line[i + 1] == '/' -> {
                    tokens += Token(i, line.length, TokenType.COMMENT)
                    i = line.length
                }
                c == '/' && i + 1 < line.length && line[i + 1] == '*' -> {
                    val close = line.indexOf("*/", startIndex = i + 2)
                    if (close == -1) {
                        tokens += Token(i, line.length, TokenType.COMMENT)
                        return TokenizeResult(tokens, LineState.JsBlockComment)
                    }
                    tokens += Token(i, close + 2, TokenType.COMMENT)
                    i = close + 2
                }
                c == '\'' || c == '"' -> {
                    val scan = scanQuotedBody(line, i + 1, c)
                    tokens += Token(i, scan.end, TokenType.STRING)
                    if (scan.continues) return TokenizeResult(tokens, LineState.JsString(c))
                    i = scan.end
                }
                c == '`' -> {
                    val close = scanTemplateEnd(line, i + 1)
                    if (close == -1) {
                        tokens += Token(i, line.length, TokenType.STRING)
                        return TokenizeResult(tokens, LineState.JsTemplateLiteral)
                    }
                    tokens += Token(i, close, TokenType.STRING)
                    i = close
                }
                c.isDigit() || (c == '.' && i + 1 < line.length && line[i + 1].isDigit()) -> {
                    val end = scanNumberEnd(line, i)
                    tokens += Token(i, end, TokenType.NUMBER)
                    i = end
                }
                isWordStart(c) -> {
                    var end = i
                    while (end < line.length && isWordPart(line[end])) end++
                    if (line.substring(i, end) in KEYWORDS) {
                        tokens += Token(i, end, TokenType.KEYWORD)
                    }
                    i = end
                }
                else -> i++
            }
        }
        return TokenizeResult(tokens, LineState.Default)
    }

    private data class QuotedScan(val end: Int, val continues: Boolean)

    /**
     * Scans a '/"-string body from [from]. [QuotedScan.end] is the exclusive end of the string
     * span on this line; [QuotedScan.continues] marks a trailing line-continuation backslash
     * (the string resumes on the next line). Unterminated without continuation runs to line end.
     */
    private fun scanQuotedBody(line: String, from: Int, quote: Char): QuotedScan {
        var i = from
        while (i < line.length) {
            when (line[i]) {
                '\\' -> {
                    if (i == line.length - 1) return QuotedScan(line.length, continues = true)
                    i += 2
                }
                quote -> return QuotedScan(i + 1, continues = false)
                else -> i++
            }
        }
        return QuotedScan(line.length, continues = false)
    }

    /** Index AFTER the closing backtick from [from], or -1 when the template stays open. */
    private fun scanTemplateEnd(line: String, from: Int): Int {
        var i = from
        while (i < line.length) {
            when (line[i]) {
                '\\' -> i += 2
                '`' -> return i + 1
                else -> i++
            }
        }
        return -1
    }

    private fun scanNumberEnd(line: String, start: Int): Int {
        var i = start
        // Loose but bounded: covers ints, floats, hex/oct/bin prefixes, exponents, separators,
        // BigInt. A dot only continues the literal when a digit follows, so member access on
        // literals (`0xff.toString()`, `1..toString()`) isn't swallowed into the number.
        while (i < line.length) {
            val c = line[i]
            when {
                c == '.' -> {
                    if (i + 1 < line.length && line[i + 1].isDigit()) i++ else return i
                }
                c.isLetterOrDigit() || c == '_' -> {
                    if ((c == 'e' || c == 'E') && i > start &&
                        i + 1 < line.length && (line[i + 1] == '+' || line[i + 1] == '-')
                    ) {
                        i++
                    }
                    i++
                }
                else -> return i
            }
        }
        return i
    }

    private fun isWordStart(c: Char) = c.isLetter() || c == '_' || c == '$'
    private fun isWordPart(c: Char) = c.isLetterOrDigit() || c == '_' || c == '$'

    companion object {
        private val KEYWORDS = setOf(
            "abstract", "arguments", "as", "async", "await", "break", "case", "catch", "class",
            "const", "continue", "debugger", "default", "delete", "do", "else", "enum", "export",
            "extends", "false", "finally", "for", "from", "function", "get", "if", "implements",
            "import", "in", "instanceof", "interface", "let", "new", "null", "of", "private",
            "protected", "public", "return", "set", "static", "super", "switch", "this", "throw",
            "true", "try", "typeof", "undefined", "var", "void", "while", "with", "yield",
        )
    }
}
