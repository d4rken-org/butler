package eu.darken.butler.editor.core.syntax

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class JsonTokenizerTest {

    private val tokenizer = JsonTokenizer()

    private fun tokenize(line: String, state: LineState = LineState.Default): TokenizeResult =
        tokenizer.tokenize(line, state).also {
            it.tokens.isValidTokenizationOf(line) shouldBe true
        }

    private fun spans(line: String, state: LineState = LineState.Default): List<Pair<String, TokenType>> =
        tokenize(line, state).tokens.map { line.substring(it.start, it.end) to it.type }

    @Test
    fun `strings with escapes`() {
        spans("""{"key": "va\"l"}""") shouldBe listOf(
            "\"key\"" to TokenType.STRING,
            "\"va\\\"l\"" to TokenType.STRING,
        )
    }

    @Test
    fun `numbers including negatives and exponents`() {
        spans("[1, -2.5, 3e10, 1E-5]") shouldBe listOf(
            "1" to TokenType.NUMBER,
            "-2.5" to TokenType.NUMBER,
            "3e10" to TokenType.NUMBER,
            "1E-5" to TokenType.NUMBER,
        )
    }

    @Test
    fun `keyword literals`() {
        spans("""{"a": true, "b": false, "c": null}""").filter { it.second == TokenType.KEYWORD } shouldBe listOf(
            "true" to TokenType.KEYWORD,
            "false" to TokenType.KEYWORD,
            "null" to TokenType.KEYWORD,
        )
    }

    @Test
    fun `non-keyword words stay unstyled`() {
        spans("truex nullish") shouldBe emptyList()
    }

    @Test
    fun `unterminated string runs to end of line without throwing`() {
        spans("""{"broken: "unterminated""").last().second shouldBe TokenType.STRING
    }

    @Test
    fun `stateless - foreign states behave as Default and endState is always Default`() {
        val line = """{"a": 1}"""
        val foreign = tokenize(line, LineState.JsBlockComment)
        foreign.tokens shouldBe tokenize(line).tokens
        foreign.endState shouldBe LineState.Default
    }

    @Test
    fun `empty line`() {
        tokenize("") shouldBe TokenizeResult(emptyList(), LineState.Default)
    }
}
