package eu.darken.butler.editor.core.syntax

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class JsTokenizerTest {

    private val tokenizer = JsTokenizer()

    private fun tokenize(line: String, state: LineState = LineState.Default): TokenizeResult =
        tokenizer.tokenize(line, state).also {
            it.tokens.isValidTokenizationOf(line) shouldBe true
        }

    private fun spans(line: String, state: LineState = LineState.Default): List<Pair<String, TokenType>> =
        tokenize(line, state).tokens.map { line.substring(it.start, it.end) to it.type }

    @Test
    fun `keywords and numbers`() {
        spans("const x = 42;") shouldBe listOf(
            "const" to TokenType.KEYWORD,
            "42" to TokenType.NUMBER,
        )
    }

    @Test
    fun `identifiers are not keywords`() {
        spans("constant xlet = value") shouldBe emptyList()
    }

    @Test
    fun `line comment`() {
        spans("let a = 1 // trailing") shouldBe listOf(
            "let" to TokenType.KEYWORD,
            "1" to TokenType.NUMBER,
            "// trailing" to TokenType.COMMENT,
        )
    }

    @Test
    fun `single-line block comments including multiple per line`() {
        spans("/*a*/ if /*b*/") shouldBe listOf(
            "/*a*/" to TokenType.COMMENT,
            "if" to TokenType.KEYWORD,
            "/*b*/" to TokenType.COMMENT,
        )
    }

    @Test
    fun `block comment state threads across lines`() {
        val first = tokenize("code /* open")
        first.tokens.map { "code /* open".substring(it.start, it.end) } shouldBe listOf("/* open")
        first.endState shouldBe LineState.JsBlockComment

        val middle = tokenize("still inside", LineState.JsBlockComment)
        middle.tokens shouldBe listOf(Token(0, 12, TokenType.COMMENT))
        middle.endState shouldBe LineState.JsBlockComment

        val last = tokenize("done */ const x", LineState.JsBlockComment)
        last.tokens.map { "done */ const x".substring(it.start, it.end) to it.type } shouldBe listOf(
            "done */" to TokenType.COMMENT,
            "const" to TokenType.KEYWORD,
        )
        last.endState shouldBe LineState.Default
    }

    @Test
    fun `quoted strings with escapes never span lines`() {
        spans("""let s = 'a\'b';""") shouldBe listOf(
            "let" to TokenType.KEYWORD,
            """'a\'b'""" to TokenType.STRING,
        )
        // Unterminated ordinary string: styled to line end but no cross-line state
        tokenize("let s = 'oops").endState shouldBe LineState.Default
    }

    @Test
    fun `template literal state threads across lines`() {
        val first = tokenize("const s = `start")
        first.tokens.map { "const s = `start".substring(it.start, it.end) to it.type } shouldBe listOf(
            "const" to TokenType.KEYWORD,
            "`start" to TokenType.STRING,
        )
        first.endState shouldBe LineState.JsTemplateLiteral

        val middle = tokenize("mid", LineState.JsTemplateLiteral)
        middle.tokens shouldBe listOf(Token(0, 3, TokenType.STRING))
        middle.endState shouldBe LineState.JsTemplateLiteral

        val last = tokenize("end` + 42", LineState.JsTemplateLiteral)
        last.tokens.map { "end` + 42".substring(it.start, it.end) to it.type } shouldBe listOf(
            "end`" to TokenType.STRING,
            "42" to TokenType.NUMBER,
        )
        last.endState shouldBe LineState.Default
    }

    @Test
    fun `nested template literal closes early - pinned simplification`() {
        // The inner template's backtick ends the outer span; cosmetic, documented limitation
        spans("`a\${`b`}c`") shouldBe listOf(
            "`a\${`" to TokenType.STRING,
            "`}c`" to TokenType.STRING,
        )
    }

    @Test
    fun `numeric variants`() {
        spans("foo(1_000, 0xFF, .5)").map { it.first } shouldBe listOf("1_000", "0xFF", ".5")
    }

    @Test
    fun `foreign state behaves as Default`() {
        val line = "const x = 1"
        tokenize(line, LineState.BashSingleQuote).tokens shouldBe tokenize(line).tokens
    }

    @Test
    fun `backslash-continued string threads across lines`() {
        val first = tokenize("let s = 'abc\\")
        first.tokens.map { "let s = 'abc\\".substring(it.start, it.end) to it.type } shouldBe listOf(
            "let" to TokenType.KEYWORD,
            "'abc\\" to TokenType.STRING,
        )
        first.endState shouldBe LineState.JsString('\'')

        val second = tokenize("def' + 1", LineState.JsString('\''))
        second.tokens.map { "def' + 1".substring(it.start, it.end) to it.type } shouldBe listOf(
            "def'" to TokenType.STRING,
            "1" to TokenType.NUMBER,
        )
        second.endState shouldBe LineState.Default

        // An escaped backslash at line end is NOT a continuation
        tokenize("let s = 'a\\\\").endState shouldBe LineState.Default
    }

    @Test
    fun `numbers stop before member access`() {
        spans("0xff.toString() + 1..toString()").filter { it.second == TokenType.NUMBER } shouldBe listOf(
            "0xff" to TokenType.NUMBER,
            "1" to TokenType.NUMBER,
        )
    }

    @Test
    fun `trailing template escape does not throw or loop`() {
        tokenize("`abc\\").endState shouldBe LineState.JsTemplateLiteral
    }
}
