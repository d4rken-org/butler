package eu.darken.butler.common.files.text

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import org.junit.Test
import org.junit.runner.RunWith
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction

/**
 * Android's GB18030 comes from ICU, not the JDK the host tests run on. Pins that the device codec
 * agrees with the byte values `Gb18030Test` measured on the JDK, and with [Gb18030]'s structure.
 */
@RunWith(AndroidJUnit4::class)
class Gb18030CodecDeviceTest {

    private val charset = TextCharsets.GB18030

    private fun bytes(vararg values: Int) = ByteArray(values.size) { values[it].toByte() }

    private val fixtures = listOf(
        "你好世界" to bytes(0xC4, 0xE3, 0xBA, 0xC3, 0xCA, 0xC0, 0xBD, 0xE7),
        "丂" to bytes(0x81, 0x40),
        "ʘ" to bytes(0x81, 0x30, 0xB2, 0x37),
        "😀" to bytes(0x94, 0x39, 0xFC, 0x36),
        "�" to bytes(0x84, 0x31, 0xA4, 0x37),
    )

    private val malformed = listOf(
        bytes(0x81, 0x20),
        bytes(0x81, 0x30, 0x20),
        bytes(0x41, 0x81),
        bytes(0x80),
        bytes(0xFF),
    )

    private fun strictDecode(bytes: ByteArray): String = charset.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
        .decode(ByteBuffer.wrap(bytes))
        .toString()

    @Test
    fun fixturesRoundTrip() {
        fixtures.forEach { (text, encoded) ->
            withClue(text) {
                text.toByteArray(charset) shouldBe encoded
                strictDecode(encoded) shouldBe text
            }
        }
    }

    @Test
    fun structureMatchesTheDeviceDecode() {
        fixtures.forEach { (text, encoded) ->
            var index = 0
            var units = 0
            while (index < encoded.size) {
                val length = Gb18030.sequenceLength(encoded, index)
                withClue("$text at $index") { (length > 0) shouldBe true }
                val sequence = encoded.copyOfRange(index, index + length)
                val unitCount = Gb18030.utf16Units(encoded, index)
                withClue("$text at $index") {
                    strictDecode(sequence).length shouldBe unitCount
                }
                units += unitCount
                index += length
            }
            withClue(text) { units shouldBe text.length }
        }
    }

    @Test
    fun malformedInputIsReported() {
        malformed.forEach { input ->
            withClue(input.joinToString(" ") { "%02X".format(it) }) {
                shouldThrow<CharacterCodingException> { strictDecode(input) }
            }
        }
    }

    @Test
    fun malformedInputHasNoStructure() {
        Gb18030.sequenceLength(bytes(0x81, 0x20), 0) shouldBe 0
        Gb18030.sequenceLength(bytes(0x81, 0x30, 0x20), 0) shouldBe 0
        Gb18030.sequenceLength(bytes(0x41, 0x81), 1) shouldBe 0
        Gb18030.incompleteTailLength(bytes(0x41, 0x81)) shouldBe 1
        Gb18030.sequenceLength(bytes(0x80), 0) shouldBe 0
        Gb18030.sequenceLength(bytes(0xFF), 0) shouldBe 0
    }
}
