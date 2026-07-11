package eu.darken.butler.editor.core.syntax

import eu.darken.butler.editor.core.syntax.LineState.BashHeredoc.HeredocSpec
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class BashTokenizerTest {

    private val tokenizer = BashTokenizer()

    private fun tokenize(line: String, state: LineState = LineState.Default): TokenizeResult =
        tokenizer.tokenize(line, state).also {
            it.tokens.isValidTokenizationOf(line) shouldBe true
        }

    private fun spans(line: String, state: LineState = LineState.Default): List<Pair<String, TokenType>> =
        tokenize(line, state).tokens.map { line.substring(it.start, it.end) to it.type }

    @Test
    fun `comment only at word start`() {
        spans("# full line") shouldBe listOf("# full line" to TokenType.COMMENT)
        spans("echo hi # trailing").last() shouldBe ("# trailing" to TokenType.COMMENT)
        spans("echo foo#bar") shouldBe emptyList()
    }

    @Test
    fun `keywords at word boundaries`() {
        spans("if true; then") shouldBe listOf(
            "if" to TokenType.KEYWORD,
            "then" to TokenType.KEYWORD,
        )
        spans("gift shift0") shouldBe emptyList()
    }

    @Test
    fun `single-quoted string spans lines`() {
        val first = tokenize("echo 'start")
        first.tokens.map { "echo 'start".substring(it.start, it.end) } shouldBe listOf("'start")
        first.endState shouldBe LineState.BashSingleQuote

        val second = tokenize("end' # c", LineState.BashSingleQuote)
        second.tokens.map { "end' # c".substring(it.start, it.end) to it.type } shouldBe listOf(
            "end'" to TokenType.STRING,
            "# c" to TokenType.COMMENT,
        )
        second.endState shouldBe LineState.Default
    }

    @Test
    fun `double-quoted string with variable segments`() {
        spans("""echo "a ${'$'}NAME b"""") shouldBe listOf(
            "\"a " to TokenType.STRING,
            "\$NAME" to TokenType.EMPHASIS,
            " b\"" to TokenType.STRING,
        )
    }

    @Test
    fun `double-quoted string spans lines`() {
        val first = tokenize("echo \"open")
        first.endState shouldBe LineState.BashDoubleQuote

        val second = tokenize("closed\" rest", LineState.BashDoubleQuote)
        second.tokens.map { "closed\" rest".substring(it.start, it.end) to it.type } shouldBe listOf(
            "closed\"" to TokenType.STRING,
        )
        second.endState shouldBe LineState.Default
    }

    @Test
    fun `variable reference forms`() {
        spans("\${PATH}x").first() shouldBe ("\${PATH}" to TokenType.EMPHASIS)
        spans("$1 $?") shouldBe listOf(
            "$1" to TokenType.EMPHASIS,
            "$?" to TokenType.EMPHASIS,
        )
    }

    @Test
    fun `heredoc open body and terminator`() {
        val open = tokenize("cat <<EOF")
        open.tokens shouldBe emptyList()
        open.endState shouldBe LineState.BashHeredoc(listOf(HeredocSpec("EOF", stripTabs = false)))

        val body = tokenize("hello \$WORLD", open.endState)
        body.tokens shouldBe listOf(Token(0, 12, TokenType.STRING))
        body.endState shouldBe open.endState

        tokenize("EOF", open.endState).endState shouldBe LineState.Default
    }

    @Test
    fun `heredoc dash variant strips tabs on terminator match`() {
        val open = tokenize("cat <<-EOF")
        open.endState shouldBe LineState.BashHeredoc(listOf(HeredocSpec("EOF", stripTabs = true)))
        tokenize("\t\tEOF", open.endState).endState shouldBe LineState.Default
    }

    @Test
    fun `heredoc delimiter may contain hyphens and dots`() {
        val open = tokenize("cat <<END-MARK.txt")
        open.endState shouldBe LineState.BashHeredoc(listOf(HeredocSpec("END-MARK.txt", stripTabs = false)))
        tokenize("END-MARK.txt", open.endState).endState shouldBe LineState.Default
    }

    @Test
    fun `quoted heredoc delimiter is captured without quotes`() {
        tokenize("cat <<'STOP'").endState shouldBe
            LineState.BashHeredoc(listOf(HeredocSpec("STOP", stripTabs = false)))
    }

    @Test
    fun `multiple heredocs consumed in order`() {
        val open = tokenize("cat <<A <<B")
        open.endState shouldBe LineState.BashHeredoc(
            listOf(HeredocSpec("A", stripTabs = false), HeredocSpec("B", stripTabs = false)),
        )
        val afterFirst = tokenize("A", open.endState)
        afterFirst.endState shouldBe LineState.BashHeredoc(listOf(HeredocSpec("B", stripTabs = false)))
        tokenize("B", afterFirst.endState).endState shouldBe LineState.Default
    }

    @Test
    fun `here-string is not a heredoc`() {
        val result = tokenize("cat <<<\"str\"")
        result.endState shouldBe LineState.Default
        result.tokens.map { "cat <<<\"str\"".substring(it.start, it.end) } shouldBe listOf("\"str\"")
    }

    @Test
    fun `foreign state behaves as Default`() {
        val line = "echo \$HOME"
        tokenize(line, LineState.JsBlockComment).tokens shouldBe tokenize(line).tokens
    }
}
