package eu.darken.butler.editor.core.engine

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import testhelpers.BaseTest

/**
 * The line/column walk shared by EditorEngine.computeEndPosition (undo/redo) and
 * DocumentBuffer.insertEndPosition (insert/replace) - the two drifted apart twice while each
 * carried its own LF-only copy.
 */
class EndPositionOfTest : BaseTest() {

    private val start = TextPosition(offset = 10L, line = 4, column = 7)

    @Test
    fun `text without a break only advances the column`() {
        val result = endPositionOf(start, "abc", endOffset = 13L)
        result shouldBe TextPosition(offset = 13L, line = 4, column = 10)
    }

    @Test
    fun `empty text stays put`() {
        endPositionOf(start, "", endOffset = 10L) shouldBe start
    }

    @ParameterizedTest
    @ValueSource(strings = ["\n", "\r\n", "\r"])
    fun `every kind of break advances one line and resets the column`(terminator: String) {
        val result = endPositionOf(start, "ab${terminator}cd", endOffset = 99L)
        result shouldBe TextPosition(offset = 99L, line = 5, column = 2)
    }

    @ParameterizedTest
    @ValueSource(strings = ["\n", "\r\n", "\r"])
    fun `a trailing break leaves the column at zero`(terminator: String) {
        val result = endPositionOf(start, "ab$terminator", endOffset = 99L)
        result shouldBe TextPosition(offset = 99L, line = 5, column = 0)
    }

    /** The case the LF-only scan under-counted: only the '\n's registered, the lone '\r' did not. */
    @Test
    fun `mixed endings each count as exactly one break`() {
        val result = endPositionOf(start, "a\rb\nc", endOffset = 99L)
        result shouldBe TextPosition(offset = 99L, line = 6, column = 1)
    }

    @Test
    fun `CRLF is one break, not two`() {
        val result = endPositionOf(start, "a\r\nb\r\nc", endOffset = 99L)
        result shouldBe TextPosition(offset = 99L, line = 6, column = 1)
    }

    @Test
    fun `a CR followed by a CR is two breaks`() {
        val result = endPositionOf(start, "a\r\rb", endOffset = 99L)
        result shouldBe TextPosition(offset = 99L, line = 6, column = 1)
    }

    @Test
    fun `an LF followed by a CR is two breaks`() {
        val result = endPositionOf(start, "a\n\rb", endOffset = 99L)
        result shouldBe TextPosition(offset = 99L, line = 6, column = 1)
    }

    @Test
    fun `columns count UTF-16 units, so a surrogate pair counts as two`() {
        val result = endPositionOf(start, "😀", endOffset = 12L)
        result shouldBe TextPosition(offset = 12L, line = 4, column = 9)
    }

    @Test
    fun `the endOffset is passed through untouched`() {
        endPositionOf(start, "a\rb", endOffset = 1234L).offset shouldBe 1234L
    }
}
