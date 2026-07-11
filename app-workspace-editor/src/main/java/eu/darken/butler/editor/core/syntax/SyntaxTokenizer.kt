package eu.darken.butler.editor.core.syntax

/**
 * Per-line incremental tokenizer: a bounded forward scan over one line (no '\n') entering at
 * [tokenize]'s startState and reporting the state the NEXT line starts in. Implementations must
 * never throw on malformed input and must satisfy the [Token] output invariants.
 */
interface SyntaxTokenizer {
    fun tokenize(line: String, startState: LineState): TokenizeResult
}

data class TokenizeResult(
    val tokens: List<Token>,
    val endState: LineState,
) {
    companion object {
        val EMPTY = TokenizeResult(emptyList(), LineState.Default)
    }
}
