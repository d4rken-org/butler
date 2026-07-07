package eu.darken.butler.common.files.text

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import testhelpers.BaseTest

class CharsetDetectorTest : BaseTest() {

    private val utf8Bom = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())
    private val utf16LeBom = byteArrayOf(0xFF.toByte(), 0xFE.toByte())
    private val utf16BeBom = byteArrayOf(0xFE.toByte(), 0xFF.toByte())

    // ==================== BOM detection ====================

    @Test
    fun `detect UTF-8 BOM`() {
        val detection = CharsetDetector.detect(utf8Bom + "Hello".toByteArray(Charsets.UTF_8))

        detection.charset shouldBe Charsets.UTF_8
        detection.hasBom shouldBe true
        detection.bomBytes shouldBe utf8Bom
    }

    @Test
    fun `detect UTF-16LE BOM`() {
        val detection = CharsetDetector.detect(utf16LeBom + "Hello".toByteArray(Charsets.UTF_16LE))

        detection.charset shouldBe Charsets.UTF_16LE
        detection.hasBom shouldBe true
        detection.bomBytes shouldBe utf16LeBom
    }

    @Test
    fun `detect UTF-16BE BOM`() {
        val detection = CharsetDetector.detect(utf16BeBom + "Hello".toByteArray(Charsets.UTF_16BE))

        detection.charset shouldBe Charsets.UTF_16BE
        detection.hasBom shouldBe true
        detection.bomBytes shouldBe utf16BeBom
    }

    @Test
    fun `detectBom returns null when no BOM is present`() {
        CharsetDetector.detectBom("Hello".toByteArray(Charsets.UTF_8)) shouldBe null
    }

    // ==================== BOM-less defaults ====================

    @Test
    fun `valid UTF-8 without BOM defaults to UTF-8 with null bom`() {
        val detection = CharsetDetector.detect("Hello 中文 🚀".toByteArray(Charsets.UTF_8))

        detection.charset shouldBe Charsets.UTF_8
        detection.hasBom shouldBe false
        detection.bomBytes shouldBe null
    }

    @Test
    fun `invalid UTF-8 still defaults to UTF-8 with null bom`() {
        // 0xC3 without a continuation byte is malformed UTF-8
        val detection = CharsetDetector.detect(byteArrayOf(0xC3.toByte(), 0x28))

        detection.charset shouldBe Charsets.UTF_8
        detection.hasBom shouldBe false
        detection.bomBytes shouldBe null
    }

    @Test
    fun `empty array defaults to UTF-8 with null bom`() {
        val detection = CharsetDetector.detect(ByteArray(0))

        detection.charset shouldBe Charsets.UTF_8
        detection.hasBom shouldBe false
        detection.bomBytes shouldBe null
    }

    // ==================== isValidUtf8 ====================

    @Test
    fun `isValidUtf8 accepts plain ASCII`() {
        CharsetDetector.isValidUtf8("Hello World".toByteArray(Charsets.UTF_8)) shouldBe true
    }

    @Test
    fun `isValidUtf8 accepts multibyte sequences`() {
        CharsetDetector.isValidUtf8("中文 é 🚀".toByteArray(Charsets.UTF_8)) shouldBe true
    }

    @Test
    fun `isValidUtf8 rejects truncated multibyte sequence`() {
        // 中 = E4 B8 AD; drop the last byte
        val truncated = "中".toByteArray(Charsets.UTF_8).copyOfRange(0, 2)
        CharsetDetector.isValidUtf8(truncated) shouldBe false
    }

    @Test
    fun `isValidUtf8 rejects invalid lead byte`() {
        // 0xFF is never a valid UTF-8 lead byte
        CharsetDetector.isValidUtf8(byteArrayOf(0xFF.toByte(), 0x41)) shouldBe false
    }
}
