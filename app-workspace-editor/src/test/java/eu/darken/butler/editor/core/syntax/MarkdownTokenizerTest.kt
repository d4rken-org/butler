package eu.darken.butler.editor.core.syntax

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class MarkdownTokenizerTest {

    private val tokenizer = MarkdownTokenizer()

    private fun tokenize(line: String, state: LineState = LineState.Default): TokenizeResult =
        tokenizer.tokenize(line, state).also {
            it.tokens.isValidTokenizationOf(line) shouldBe true
        }

    private fun spans(line: String, state: LineState = LineState.Default): List<Pair<String, TokenType>> =
        tokenize(line, state).tokens.map { line.substring(it.start, it.end) to it.type }

    @Test
    fun `atx headings`() {
        spans("# Title") shouldBe listOf("# Title" to TokenType.KEYWORD)
        spans("  ## Indented heading") shouldBe listOf("  ## Indented heading" to TokenType.KEYWORD)
        spans("###### six") shouldBe listOf("###### six" to TokenType.KEYWORD)
        spans("#NoSpace") shouldBe emptyList()
        spans("####### seven hashes") shouldBe emptyList()
    }

    @Test
    fun `fenced code block open body close`() {
        val open = tokenize("```js")
        open.tokens shouldBe listOf(Token(0, 5, TokenType.STRING))
        open.endState shouldBe LineState.MdFencedCode(fenceChar = '`', fenceLength = 3)

        val body = tokenize("const x = 1", open.endState)
        body.tokens shouldBe listOf(Token(0, 11, TokenType.STRING))
        body.endState shouldBe open.endState

        tokenize("```", open.endState).endState shouldBe LineState.Default
    }

    @Test
    fun `fence close rules - same char and at least opening length`() {
        val backtick3 = LineState.MdFencedCode(fenceChar = '`', fenceLength = 3)
        tokenize("~~~", backtick3).endState shouldBe backtick3
        tokenize("`````", backtick3).endState shouldBe LineState.Default
        tokenize("``` trailing text", backtick3).endState shouldBe backtick3

        val tilde4 = tokenize("~~~~").endState
        tilde4 shouldBe LineState.MdFencedCode(fenceChar = '~', fenceLength = 4)
        tokenize("~~~", tilde4).endState shouldBe tilde4
        tokenize("~~~~", tilde4).endState shouldBe LineState.Default
    }

    @Test
    fun `inline code spans with matching run lengths`() {
        spans("a `code` b") shouldBe listOf("`code`" to TokenType.STRING)
        spans("x ``a`b`` y") shouldBe listOf("``a`b``" to TokenType.STRING)
        spans("unmatched `open") shouldBe emptyList()
    }

    @Test
    fun `bold and italic`() {
        spans("**bold** x") shouldBe listOf("**bold**" to TokenType.EMPHASIS)
        spans("*it* rest") shouldBe listOf("*it*" to TokenType.EMPHASIS)
        spans("some _em_ here") shouldBe listOf("_em_" to TokenType.EMPHASIS)
    }

    @Test
    fun `underscore emphasis does not open intraword`() {
        spans("snake_case_value") shouldBe emptyList()
    }

    @Test
    fun `escaped markers are literal`() {
        spans("\\*not\\*") shouldBe emptyList()
    }

    @Test
    fun `blockquote`() {
        spans("> quoted text") shouldBe listOf("> quoted text" to TokenType.COMMENT)
    }

    @Test
    fun `foreign state behaves as Default`() {
        val line = "# Title"
        tokenize(line, LineState.JsTemplateLiteral).tokens shouldBe tokenize(line).tokens
    }
}
