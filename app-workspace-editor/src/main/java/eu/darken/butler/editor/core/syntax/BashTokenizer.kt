package eu.darken.butler.editor.core.syntax

import eu.darken.butler.editor.core.syntax.LineState.BashHeredoc.HeredocSpec

/**
 * Bash: comments (word-start `#` only - `foo#bar` is literal), single/double-quoted strings
 * (both may span lines), `$VAR`/`${VAR}` references, heredocs (`<<`, `<<-`, quoted delimiters,
 * multiple per line, `<<<` here-strings excluded), keywords.
 *
 * Documented simplification: a line that leaves BOTH an unterminated quote and pending heredocs
 * open (malformed shell anyway) carries the quote state; the heredoc specs are dropped.
 */
internal class BashTokenizer : SyntaxTokenizer {

    override fun tokenize(line: String, startState: LineState): TokenizeResult {
        val tokens = mutableListOf<Token>()
        var i = 0
        when (startState) {
            LineState.BashSingleQuote -> {
                val close = line.indexOf('\'')
                if (close == -1) {
                    if (line.isNotEmpty()) tokens += Token(0, line.length, TokenType.STRING)
                    return TokenizeResult(tokens, LineState.BashSingleQuote)
                }
                tokens += Token(0, close + 1, TokenType.STRING)
                i = close + 1
            }
            LineState.BashDoubleQuote -> {
                val close = scanDoubleQuoteBody(line, 0, tokens)
                if (close == -1) return TokenizeResult(tokens, LineState.BashDoubleQuote)
                i = close
            }
            is LineState.BashHeredoc -> {
                val spec = startState.pending.first()
                val candidate = if (spec.stripTabs) line.trimStart('\t') else line
                return if (candidate == spec.terminator) {
                    val rest = startState.pending.drop(1)
                    val endState = if (rest.isEmpty()) LineState.Default else LineState.BashHeredoc(rest)
                    TokenizeResult(emptyList(), endState)
                } else {
                    val bodyTokens = if (line.isEmpty()) emptyList() else listOf(Token(0, line.length, TokenType.STRING))
                    TokenizeResult(bodyTokens, startState)
                }
            }
            else -> Unit // Foreign or Default states both scan from Default
        }

        val pendingHeredocs = mutableListOf<HeredocSpec>()
        while (i < line.length) {
            val c = line[i]
            when {
                c == '\\' -> i += 2
                c == '#' && isCommentStart(line, i) -> {
                    tokens += Token(i, line.length, TokenType.COMMENT)
                    i = line.length
                }
                c == '\'' -> {
                    val close = line.indexOf('\'', startIndex = i + 1)
                    if (close == -1) {
                        tokens += Token(i, line.length, TokenType.STRING)
                        return TokenizeResult(tokens, LineState.BashSingleQuote)
                    }
                    tokens += Token(i, close + 1, TokenType.STRING)
                    i = close + 1
                }
                c == '"' -> {
                    val close = scanDoubleQuoteBody(line, i, tokens)
                    if (close == -1) return TokenizeResult(tokens, LineState.BashDoubleQuote)
                    i = close
                }
                c == '<' && line.startsWith("<<<", startIndex = i) -> i += 3
                c == '<' && line.startsWith("<<", startIndex = i) -> {
                    val parsed = parseHeredocIntro(line, i + 2)
                    if (parsed != null) {
                        pendingHeredocs += parsed.first
                        i = parsed.second
                    } else {
                        i += 2
                    }
                }
                c == '$' -> {
                    val end = scanVariableEnd(line, i)
                    if (end > i + 1) tokens += Token(i, end, TokenType.EMPHASIS)
                    i = end
                }
                c.isLetter() || c == '_' -> {
                    var end = i
                    while (end < line.length && (line[end].isLetterOrDigit() || line[end] == '_')) end++
                    if (line.substring(i, end) in KEYWORDS && isKeywordStartBoundary(line, i)) {
                        tokens += Token(i, end, TokenType.KEYWORD)
                    }
                    i = end
                }
                else -> i++
            }
        }
        val endState = if (pendingHeredocs.isNotEmpty()) LineState.BashHeredoc(pendingHeredocs) else LineState.Default
        return TokenizeResult(tokens, endState)
    }

    /** `#` starts a comment only at line start or after whitespace/command separators. */
    private fun isCommentStart(line: String, i: Int): Boolean =
        i == 0 || line[i - 1] in " \t;|&(){}"

    /**
     * Guards keyword matches against being the suffix of a larger word (`3if`, `$if`); the word
     * scan at the call site already guarantees the trailing boundary.
     */
    private fun isKeywordStartBoundary(line: String, start: Int): Boolean =
        start == 0 || !(line[start - 1].isLetterOrDigit() || line[start - 1] == '_' || line[start - 1] == '$')

    /**
     * Scans a double-quoted body starting at [from] (AT the opening quote, or at continuation
     * content when entering mid-string), emitting STRING segments split around $-references.
     * Returns the index after the closing quote, or -1 when the string stays open.
     */
    private fun scanDoubleQuoteBody(line: String, from: Int, tokens: MutableList<Token>): Int {
        var segStart = from
        var i = if (from < line.length && line[from] == '"') from + 1 else from
        while (i < line.length) {
            when {
                line[i] == '\\' -> i += 2
                line[i] == '"' -> {
                    tokens += Token(segStart, i + 1, TokenType.STRING)
                    return i + 1
                }
                line[i] == '$' -> {
                    val end = scanVariableEnd(line, i)
                    if (end > i + 1) {
                        if (i > segStart) tokens += Token(segStart, i, TokenType.STRING)
                        tokens += Token(i, end, TokenType.EMPHASIS)
                        segStart = end
                        i = end
                    } else {
                        i++
                    }
                }
                else -> i++
            }
        }
        if (segStart < line.length) tokens += Token(segStart, line.length, TokenType.STRING)
        return -1
    }

    /** End (exclusive) of a $-reference at [i] (`$`), or [i]+1 when `$` is followed by nothing usable. */
    private fun scanVariableEnd(line: String, i: Int): Int {
        var j = i + 1
        if (j >= line.length) return j
        return when {
            line[j] == '{' -> {
                val close = line.indexOf('}', startIndex = j + 1)
                if (close == -1) line.length else close + 1
            }
            line[j].isLetter() || line[j] == '_' -> {
                while (j < line.length && (line[j].isLetterOrDigit() || line[j] == '_')) j++
                j
            }
            line[j].isDigit() || line[j] in "?#@*$!-" -> j + 1 // single special/positional char
            else -> j
        }
    }

    /**
     * Parses a heredoc introduction after `<<` at [from]: optional `-`, optional spaces, then a
     * (possibly quoted or backslash-escaped) delimiter word. Returns spec + index after the
     * delimiter, or null when no valid delimiter follows.
     */
    private fun parseHeredocIntro(line: String, from: Int): Pair<HeredocSpec, Int>? {
        var i = from
        var stripTabs = false
        if (i < line.length && line[i] == '-') {
            stripTabs = true
            i++
        }
        while (i < line.length && (line[i] == ' ' || line[i] == '\t')) i++
        if (i >= line.length) return null
        var quote: Char? = null
        when (line[i]) {
            '\'', '"' -> {
                quote = line[i]
                i++
            }
            '\\' -> i++
        }
        val wordStart = i
        // Hyphens/dots are valid in delimiters (`<<END-MARK`); truncating them would record a
        // terminator that never matches and color the rest of the file as heredoc body.
        while (i < line.length && (line[i].isLetterOrDigit() || line[i] in "_-.")) i++
        if (i == wordStart) return null
        val terminator = line.substring(wordStart, i)
        if (quote != null && i < line.length && line[i] == quote) i++
        return HeredocSpec(terminator, stripTabs) to i
    }

    companion object {
        private val KEYWORDS = setOf(
            "alias", "break", "case", "continue", "coproc", "declare", "do", "done", "elif",
            "else", "esac", "eval", "exec", "exit", "export", "fi", "for", "function", "if",
            "in", "local", "readonly", "return", "select", "set", "shift", "source", "then",
            "time", "trap", "typeset", "unset", "until", "while",
        )
    }
}
