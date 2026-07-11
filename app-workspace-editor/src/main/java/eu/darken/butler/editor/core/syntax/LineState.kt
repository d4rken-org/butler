package eu.darken.butler.editor.core.syntax

/**
 * Lexer state carried across line boundaries (multi-line constructs). Each tokenizer only owns a
 * subset; receiving a foreign state (e.g. [JsBlockComment] passed to the bash tokenizer) must be
 * treated as [Default] - deterministic, never an error.
 */
sealed interface LineState {

    data object Default : LineState

    /** Inside an unclosed JS block comment. */
    data object JsBlockComment : LineState

    /** Inside a JS `template literal` that hasn't closed yet. */
    data object JsTemplateLiteral : LineState

    /** Inside a JS '/" string continued across the line break by a trailing backslash. */
    data class JsString(val quote: Char) : LineState

    /**
     * Inside a markdown fenced code block. Per CommonMark the closing fence must use the same
     * [fenceChar] and be at least [fenceLength] long.
     */
    data class MdFencedCode(val fenceChar: Char, val fenceLength: Int) : LineState

    /** Inside a bash '...' single-quoted string spanning lines (no escapes exist inside). */
    data object BashSingleQuote : LineState

    /** Inside a bash "..." double-quoted string spanning lines. */
    data object BashDoubleQuote : LineState

    /**
     * One or more heredoc bodies are pending, consumed in order. Each body runs until a line
     * matching its terminator (leading tabs stripped first for `<<-`).
     */
    data class BashHeredoc(val pending: List<HeredocSpec>) : LineState {
        data class HeredocSpec(val terminator: String, val stripTabs: Boolean)
    }
}
