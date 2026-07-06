package eu.darken.butler.editor.core.engine.text

import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class LineBreakTransformerTest {

    private fun convert(target: String, vararg chunks: String): String {
        val transformer = LineBreakTransformer(target)
        val sb = StringBuilder()
        chunks.forEach { sb.append(transformer.transform(it)) }
        transformer.flushTrailing()?.let { sb.append(it) }
        return sb.toString()
    }

    @Test
    fun `converts CRLF to LF`() {
        convert("\n", "a\r\nb\r\nc") shouldBe "a\nb\nc"
    }

    @Test
    fun `converts LF to CRLF`() {
        convert("\r\n", "a\nb\nc") shouldBe "a\r\nb\r\nc"
    }

    @Test
    fun `converts mixed breaks to a uniform target`() {
        convert("\n", "a\r\nb\nc\rd") shouldBe "a\nb\nc\nd"
    }

    @Test
    fun `already-conforming text is unchanged`() {
        convert("\r\n", "a\r\nb\r\n") shouldBe "a\r\nb\r\n"
    }

    @Test
    fun `CRLF split across chunks converts exactly once`() {
        convert("\n", "a\r", "\nb") shouldBe "a\nb"
        convert("\r\n", "a\r", "\nb") shouldBe "a\r\nb"
    }

    @Test
    fun `lone CR at a chunk boundary followed by text is a break`() {
        convert("\n", "a\r", "b") shouldBe "a\nb"
    }

    @Test
    fun `document-final bare CR is a break`() {
        convert("\r\n", "a\r") shouldBe "a\r\n"
    }

    @Test
    fun `flushTrailing is null without a pending CR`() {
        val transformer = LineBreakTransformer("\n")
        transformer.transform("abc")
        transformer.flushTrailing().shouldBeNull()
    }

    @Test
    fun `consecutive breaks each convert`() {
        convert("\r\n", "\n\n\r\r\n") shouldBe "\r\n\r\n\r\n\r\n"
    }

    @Test
    fun `empty chunks are passed through`() {
        convert("\n", "", "a\r", "", "\nb") shouldBe "a\nb"
    }
}
