package eu.darken.butler.common.files.text

import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test
import testhelpers.BaseTest

/**
 * The decode rules themselves are also exercised through `PasteFileReaderTest`, which owns the
 * paste-specific behaviour. What is pinned here is the part that wrapper drops: which charset the
 * decode settled on, which is what a caller has to report to the user.
 */
class TextDecoderTest : BaseTest() {

    private val utf8Bom = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())
    private val utf16LeBom = byteArrayOf(0xFF.toByte(), 0xFE.toByte())

    private fun decoded(bytes: ByteArray) =
        TextDecoder.decode(bytes).shouldBeInstanceOf<TextDecoder.Result.Decoded>()

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
}
