package eu.darken.butler.common.files.text

import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test
import testhelpers.BaseTest
import java.nio.charset.Charset

/**
 * The decode rules themselves are also exercised through `PasteFileReaderTest`, which owns the
 * paste-specific behaviour. What is pinned here is the part that wrapper drops: which charset the
 * decode settled on, which is what a caller has to report to the user.
 */
class TextDecoderTest : BaseTest() {

    private val utf8Bom = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())
    private val utf16LeBom = byteArrayOf(0xFF.toByte(), 0xFE.toByte())

    private fun decoded(bytes: ByteArray, truncated: Boolean = false, charset: Charset? = null) =
        TextDecoder.decode(bytes, truncated, charset).shouldBeInstanceOf<TextDecoder.Result.Decoded>()

    @Test
    fun `plain utf-8 reports utf-8`() {
        val result = decoded("Grüße, 世界".toByteArray(Charsets.UTF_8))

        result.text shouldBe "Grüße, 世界"
        result.charset shouldBe Charsets.UTF_8
    }

    @Test
    fun `a utf-8 BOM is stripped and not part of the text`() {
        val result = decoded(utf8Bom + "Hello".toByteArray(Charsets.UTF_8))

        result.text shouldBe "Hello"
        result.charset shouldBe Charsets.UTF_8
    }

    /** UTF-16 text is full of 0x00, so the BOM has to be honoured before the null-byte check. */
    @Test
    fun `utf-16 survives the binary check and reports its own charset`() {
        val result = decoded(utf16LeBom + "Hi".toByteArray(Charsets.UTF_16LE))

        result.text shouldBe "Hi"
        result.charset shouldBe Charsets.UTF_16LE
    }

    @Test
    fun `legacy bytes fall back to latin-1 and say so`() {
        val result = decoded("café".toByteArray(Charsets.ISO_8859_1))

        result.text shouldBe "café"
        result.charset shouldBe Charsets.ISO_8859_1
    }

    @Test
    fun `null bytes are binary`() {
        TextDecoder.decode(byteArrayOf(0x48, 0x00, 0x49)) shouldBe TextDecoder.Result.Binary
    }

    @Test
    fun `control-character garbage is binary even without null bytes`() {
        val garbage = ByteArray(64) { (0x01 + (it % 8)).toByte() }

        TextDecoder.decode(garbage) shouldBe TextDecoder.Result.Binary
    }

    @Test
    fun `empty content decodes to empty text, not binary`() {
        val result = decoded(ByteArray(0))

        result.text shouldBe ""
    }

    /** Logs carry tabs and ANSI escapes; those must not read as binary. */
    @Test
    fun `text controls do not trip the binary guard`() {
        TextDecoder.looksBinary("col\tone\n\u001B[31mred\u001B[0m\r\n") shouldBe false
    }

    // ==================== GB18030 ====================

    private val gb18030 = TextCharsets.GB18030
    private val chinese = "你好世界，这是一个中文文本文件。"

    @Test
    fun `GBK chinese decodes as gb18030`() {
        val result = decoded(chinese.toByteArray(gb18030))

        result.text shouldBe chinese
        result.charset shouldBe gb18030
    }

    @Test
    fun `GBK chinese cut inside a sequence drops the partial character when truncated`() {
        val bytes = chinese.toByteArray(gb18030)

        val result = decoded(bytes.copyOf(bytes.size - 1), truncated = true)

        result.text shouldBe chinese.dropLast(1)
        result.charset shouldBe gb18030
    }

    @Test
    fun `latin-1 text that also decodes as gb18030 stays latin-1`() {
        CharsetDetectorTest.latin1Samples.forEach { text ->
            val result = decoded(text.toByteArray(Charsets.ISO_8859_1))

            result.text shouldBe text
            result.charset shouldBe Charsets.ISO_8859_1
        }
    }

    // ==================== truncation ====================

    @Test
    fun `utf-8 cut inside a sequence stays utf-8 without a replacement character`() {
        val text = "中文".repeat(10)
        val bytes = text.toByteArray(Charsets.UTF_8)

        val result = decoded(bytes.copyOf(bytes.size - 1), truncated = true)

        result.text shouldBe text.dropLast(1)
        result.text.contains('�') shouldBe false
        result.charset shouldBe Charsets.UTF_8
    }

    /** At the real end of the file a broken sequence is the file's own, and is shown as such. */
    @Test
    fun `malformed utf-8 at the end of the file falls back to latin-1`() {
        val bytes = "abc".toByteArray() + 0xE4.toByte()

        decoded(bytes).let {
            it.text shouldBe "abcä"
            it.charset shouldBe Charsets.ISO_8859_1
        }
        decoded(bytes, truncated = true).let {
            it.text shouldBe "abc"
            it.charset shouldBe Charsets.UTF_8
        }
    }

    @Test
    fun `utf-16 cut inside a code unit drops the partial unit when truncated`() {
        val bytes = utf16LeBom + "Hi".toByteArray(Charsets.UTF_16LE)

        decoded(bytes.copyOf(bytes.size - 1), truncated = true).text shouldBe "H"
    }

    // ==================== explicit charset ====================

    @Test
    fun `an explicit gb18030 decodes as gb18030`() {
        val result = decoded(chinese.toByteArray(gb18030), charset = gb18030)

        result.text shouldBe chinese
        result.charset shouldBe gb18030
    }

    @Test
    fun `an explicit latin-1 overrides utf-8 detection`() {
        val result = decoded("é".toByteArray(Charsets.UTF_8), charset = Charsets.ISO_8859_1)

        result.text shouldBe "Ã©"
        result.charset shouldBe Charsets.ISO_8859_1
    }

    @Test
    fun `an explicit utf-16LE decodes content without a BOM`() {
        val result = decoded("Hi 你".toByteArray(Charsets.UTF_16LE), charset = Charsets.UTF_16LE)

        result.text shouldBe "Hi 你"
        result.charset shouldBe Charsets.UTF_16LE
    }

    @Test
    fun `an explicit charset strips only a BOM of its own family`() {
        decoded(utf16LeBom + "Hi".toByteArray(Charsets.UTF_16LE), charset = Charsets.UTF_16LE).text shouldBe "Hi"
        decoded(utf8Bom + "Hi".toByteArray(), charset = Charsets.UTF_8).text shouldBe "Hi"
        decoded(utf8Bom + "Hi".toByteArray(), charset = Charsets.ISO_8859_1).text shouldBe "ï»¿Hi"
    }

    @Test
    fun `an explicit charset replaces malformed input and never reports binary`() {
        decoded(byteArrayOf(0x41, 0x80.toByte(), 0x42), charset = gb18030).text shouldBe "A�B"
        decoded(ByteArray(64) { (0x01 + (it % 8)).toByte() }, charset = Charsets.ISO_8859_1)
        decoded(byteArrayOf(0x48, 0x00, 0x49), charset = Charsets.UTF_8).text shouldBe "H\u0000I"
    }

    @Test
    fun `an explicit charset drops a cut sequence when truncated`() {
        val bytes = chinese.toByteArray(gb18030)

        decoded(bytes.copyOf(bytes.size - 1), truncated = true, charset = gb18030).text shouldBe chinese.dropLast(1)
    }
}
