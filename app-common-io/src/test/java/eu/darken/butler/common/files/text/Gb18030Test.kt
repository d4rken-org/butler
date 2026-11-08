package eu.darken.butler.common.files.text

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import testhelpers.BaseTest

class Gb18030Test : BaseTest() {

    private fun bytes(vararg values: Int) = ByteArray(values.size) { values[it].toByte() }

    private fun gb(text: String) = text.toByteArray(TextCharsets.GB18030)

    /** Measured on the JDK; `Gb18030CodecDeviceTest` holds the same table against the device's charset. */
    private val fixtures = listOf(
        "A" to bytes(0x41),
        "你" to bytes(0xC4, 0xE3),
        "丂" to bytes(0x81, 0x40),
        "ʘ" to bytes(0x81, 0x30, 0xB2, 0x37),
        "�" to bytes(0x84, 0x31, 0xA4, 0x37),
        "😀" to bytes(0x94, 0x39, 0xFC, 0x36),
        "𐀀" to bytes(0x90, 0x30, 0x81, 0x30),
        "􏿿" to bytes(0xE3, 0x32, 0x9A, 0x35),
    )

    @Test
    fun `fixtures encode as expected on the JDK`() {
        fixtures.forEach { (text, expected) -> gb(text) shouldBe expected }
        gb("你好世界") shouldBe bytes(0xC4, 0xE3, 0xBA, 0xC3, 0xCA, 0xC0, 0xBD, 0xE7)
    }

    @Test
    fun `sequence length and utf-16 units match every fixture`() {
        fixtures.forEach { (text, encoded) ->
            Gb18030.sequenceLength(encoded, 0) shouldBe encoded.size
            Gb18030.utf16Units(encoded, 0) shouldBe text.length
        }
    }

    @Test
    fun `sequences are measured at their index`() {
        val encoded = gb("a你ʘ😀b")

        Gb18030.sequenceLength(encoded, 0) shouldBe 1
        Gb18030.sequenceLength(encoded, 1) shouldBe 2
        Gb18030.sequenceLength(encoded, 3) shouldBe 4
        Gb18030.sequenceLength(encoded, 7) shouldBe 4
        Gb18030.utf16Units(encoded, 7) shouldBe 2
        Gb18030.sequenceLength(encoded, 11) shouldBe 1
    }

    @Test
    fun `malformed sequences have no length`() {
        listOf(
            bytes(0x80),
            bytes(0xFF),
            bytes(0x81, 0x20),
            bytes(0x81, 0x30, 0x20),
            bytes(0x81, 0x30, 0x81, 0x20),
            bytes(0x81, 0x7F),
            bytes(0x81, 0xFF),
        ).forEach {
            Gb18030.sequenceLength(it, 0) shouldBe 0
            Gb18030.utf16Units(it, 0) shouldBe 0
        }
    }

    @Test
    fun `sequences cut off by the end have no length`() {
        Gb18030.sequenceLength(bytes(0x81), 0) shouldBe 0
        Gb18030.sequenceLength(bytes(0x81, 0x30), 0) shouldBe 0
        Gb18030.sequenceLength(bytes(0x81, 0x30, 0x81), 0) shouldBe 0
        Gb18030.sequenceLength(bytes(0xC4, 0xE3), 0, end = 1) shouldBe 0
        Gb18030.sequenceLength(bytes(0x41), 1) shouldBe 0
    }

    @Test
    fun `an incomplete tail is measured`() {
        val ab = bytes(0x41, 0x42)

        Gb18030.incompleteTailLength(ab + bytes(0xC4)) shouldBe 1
        Gb18030.incompleteTailLength(ab + bytes(0x81, 0x30)) shouldBe 2
        Gb18030.incompleteTailLength(ab + bytes(0x94, 0x39, 0xFC)) shouldBe 3
    }

    @Test
    fun `a complete or malformed tail is not incomplete`() {
        Gb18030.incompleteTailLength(ByteArray(0)) shouldBe 0
        Gb18030.incompleteTailLength(gb("abc")) shouldBe 0
        Gb18030.incompleteTailLength(gb("你好")) shouldBe 0
        Gb18030.incompleteTailLength(gb("a😀")) shouldBe 0
        Gb18030.incompleteTailLength(bytes(0x41, 0x81, 0x30, 0x20)) shouldBe 0
        Gb18030.incompleteTailLength(bytes(0x41, 0x81, 0x30, 0x81, 0x20)) shouldBe 0
    }

    /** `E3` closes 你 here, but it is also a lead byte: only a forward scan tells the two apart. */
    @Test
    fun `a trail byte in the lead range is not mistaken for a lead`() {
        val text = gb("你好世界".repeat(100))

        Gb18030.incompleteTailLength(text) shouldBe 0
        Gb18030.incompleteTailLength(text.copyOf(text.size - 1)) shouldBe 1
        Gb18030.incompleteTailLength(gb("hello 你")) shouldBe 0
        Gb18030.incompleteTailLength(gb("hello 你好").let { it.copyOf(it.size - 1) }) shouldBe 1
    }

    @Test
    fun `the tail is measured within start and end`() {
        val encoded = gb("你好")

        Gb18030.incompleteTailLength(encoded, end = 3) shouldBe 1
        Gb18030.incompleteTailLength(encoded, start = 2) shouldBe 0
        Gb18030.incompleteTailLength(encoded, start = 2, end = 3) shouldBe 1
    }
}
