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

    // ==================== detectEncoding ====================

    private val gb18030 = TextCharsets.GB18030
    private val chinese = "你好世界，这是一个中文文本文件。"

    private fun detected(bytes: ByteArray, truncated: Boolean = false) =
        CharsetDetector.detectEncoding(bytes, truncated)?.charset

    @Test
    fun `detectEncoding honours a BOM`() {
        CharsetDetector.detectEncoding(utf16LeBom + "Hi".toByteArray(Charsets.UTF_16LE), truncated = false) shouldBe
            CharsetDetection(Charsets.UTF_16LE, utf16LeBom)
    }

    @Test
    fun `detectEncoding prefers utf-8`() {
        detected("Hello 中文 🚀".toByteArray(Charsets.UTF_8)) shouldBe Charsets.UTF_8
        detected("plain ascii".toByteArray()) shouldBe Charsets.UTF_8
        detected(ByteArray(0)) shouldBe Charsets.UTF_8
    }

    @Test
    fun `utf-8 cut inside a sequence is still utf-8 when truncated`() {
        val bytes = "中文".repeat(10).toByteArray(Charsets.UTF_8)

        detected(bytes.copyOf(bytes.size - 1), truncated = true) shouldBe Charsets.UTF_8
        detected(bytes.copyOf(bytes.size - 2), truncated = true) shouldBe Charsets.UTF_8
    }

    @Test
    fun `GBK chinese is detected as gb18030`() {
        detected(chinese.toByteArray(gb18030)) shouldBe gb18030
    }

    @Test
    fun `GBK chinese cut inside a sequence is detected only when truncated`() {
        val bytes = chinese.toByteArray(gb18030)
        val cut = bytes.copyOf(bytes.size - 1)

        detected(cut, truncated = true) shouldBe gb18030
        detected(cut, truncated = false) shouldBe null
    }

    @Test
    fun `GBK chinese cut inside a four-byte sequence is detected when truncated`() {
        val bytes = (chinese + "\uD83D\uDE00").toByteArray(gb18030)

        (1..3).forEach { detected(bytes.copyOf(bytes.size - it), truncated = true) shouldBe gb18030 }
    }

    /** Each of these also decodes strictly as GB18030. */
    @Test
    fun `latin-1 text is not mistaken for gb18030`() {
        latin1Samples.forEach { text ->
            val bytes = text.toByteArray(Charsets.ISO_8859_1)
            detected(bytes, truncated = false) shouldBe null
            detected(bytes, truncated = true) shouldBe null
        }
    }

    /** `A3 A3` is a valid GB2312 pair (a full-width `#`), but not a hanzi. */
    @Test
    fun `a run of latin-1 pound signs is not gb18030`() {
        val bytes = ByteArray(4096) { 0xA3.toByte() }

        detected(bytes, truncated = false) shouldBe null
        detected(bytes, truncated = true) shouldBe null
    }

    @Test
    fun `fewer than four distinct hanzi are not enough`() {
        detected("哈".repeat(16).toByteArray(gb18030)) shouldBe null
        detected("你好世".repeat(5).toByteArray(gb18030)) shouldBe null
        detected("你好世界".repeat(2).toByteArray(gb18030)) shouldBe gb18030
    }

    @Test
    fun `fewer than eight GB2312 pairs are not enough`() {
        detected("你好世界你好世".toByteArray(gb18030)) shouldBe null
    }

    @Test
    fun `GB2312 pairs must make up nine in ten multi-byte sequences`() {
        val emoji = "\uD83D\uDE00"

        detected(("你好世界你好世界$emoji").toByteArray(gb18030)) shouldBe null
        detected(("你好世界你好世界中$emoji").toByteArray(gb18030)) shouldBe gb18030
    }

    @Test
    fun `bytes that are neither utf-8 nor gb18030 are not detected`() {
        detected(byteArrayOf(0x63, 0x61, 0x66, 0xE9.toByte())) shouldBe null
        detected(byteArrayOf(0x80.toByte(), 0x41)) shouldBe null
    }

    companion object {
        /** Latin-1 text that also decodes strictly as GB18030, plus one that does not. */
        val latin1Samples = listOf(
            "Grüße aus Köln, schöne Straße.",
            "pää",
            "Söö! Jää.",
            "«Été»",
            "Tervetuloa! Ääkköset: ää öö åå.",
            "Þjóðhátíð",
            "Hyvää päivää, tämä on testi.",
        )
    }
}
