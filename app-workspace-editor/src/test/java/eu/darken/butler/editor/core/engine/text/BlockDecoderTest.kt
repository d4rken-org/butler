package eu.darken.butler.editor.core.engine.text

import eu.darken.butler.common.files.text.TextCharsets
import io.kotest.assertions.withClue
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import okio.Buffer
import org.junit.jupiter.api.Test
import testhelpers.BaseTest
import java.nio.ByteBuffer
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

class BlockDecoderTest : BaseTest() {

    private fun bytes(vararg values: Int) = ByteArray(values.size) { values[it].toByte() }

    private fun gb(text: String) = text.toByteArray(TextCharsets.GB18030)

    /** A plain REPLACE decode of the whole input. */
    private fun replaceDecode(input: ByteArray, charset: Charset): String = charset.newDecoder()
        .onMalformedInput(CodingErrorAction.REPLACE)
        .onUnmappableCharacter(CodingErrorAction.REPLACE)
        .decode(ByteBuffer.wrap(input))
        .toString()

    /** Malformed or unassigned GB18030, each placed between valid text unless it has to end the file. */
    private val malformedGb18030 = mapOf(
        "81 20" to gb("a你") + bytes(0x81, 0x20) + gb("好\nb"),
        "81 30 20" to gb("a你") + bytes(0x81, 0x30, 0x20) + gb("好\nb"),
        "lone 81 at EOF" to gb("a你好\nb") + bytes(0x81),
        "80" to gb("a你") + bytes(0x80) + gb("好\nb"),
        "FF" to gb("a你") + bytes(0xFF) + gb("好\nb"),
        "unassigned 85 30 81 30" to gb("a你") + bytes(0x85, 0x30, 0x81, 0x30) + gb("好\nb"),
    )

    @Test
    fun `malformed GB18030 is flagged and replaced`() {
        for ((name, input) in malformedGb18030) {
            withClue(name) {
                val decoded = BlockDecoder.decode(input, TextCharsets.GB18030)
                decoded.hadUndecodable.shouldBeTrue()
                decoded.text.contains('�').shouldBeTrue()
                decoded.text shouldBe replaceDecode(input, TextCharsets.GB18030)
            }
        }
    }

    @Test
    fun `GB18030 with a structurally invalid byte is flagged whatever the decoder makes of it`() {
        val input = gb("你好") + bytes(0x80) + gb("世界")
        BlockDecoder.decode(input, TextCharsets.GB18030).hadUndecodable.shouldBeTrue()
    }

    @Test
    fun `GB18030 structure check`() {
        BlockDecoder.isStructurallyValidGb18030(gb("你好") + bytes(0x80) + gb("世界")).shouldBeFalse()
        BlockDecoder.isStructurallyValidGb18030(gb("你好世界")).shouldBeTrue()
    }

    @Test
    fun `valid text is not flagged in any charset`() {
        val text = "a你ʘ😀\r\nb"
        for (charset in listOf(TextCharsets.GB18030, Charsets.UTF_8, Charsets.UTF_16LE, Charsets.UTF_16BE)) {
            withClue(charset) {
                val decoded = BlockDecoder.decode(text.toByteArray(charset), charset)
                decoded.text shouldBe text
                decoded.hadUndecodable.shouldBeFalse()
            }
        }
        BlockDecoder.decode("café".toByteArray(Charsets.ISO_8859_1), Charsets.ISO_8859_1).hadUndecodable.shouldBeFalse()
    }

    @Test
    fun `a literal U+FFFD in valid text is not flagged`() = runTest {
        val cases = listOf(
            Charsets.UTF_8 to "a".toByteArray() + bytes(0xEF, 0xBF, 0xBD) + "b\nc".toByteArray(),
            TextCharsets.GB18030 to gb("a你") + bytes(0x84, 0x31, 0xA4, 0x37) + gb("好\nb"),
        )
        for ((charset, input) in cases) {
            withClue(charset) {
                val decoded = BlockDecoder.decode(input, charset)
                decoded.text.contains('�').shouldBeTrue()
                decoded.text shouldBe replaceDecode(input, charset)
                decoded.hadUndecodable.shouldBeFalse()

                for (blockSize in 4..17) {
                    withClue("block size $blockSize") {
                        verifyIndexAgainstCache(input, charset, blockSize).hasUndecodable.shouldBeFalse()
                    }
                }
            }
        }
    }

    private val undecodableByCharset = listOf(
        Charsets.UTF_8 to bytes(0x61, 0xC3, 0x28, 0x62),
        Charsets.UTF_16LE to bytes(0x61, 0x00, 0x62),
        Charsets.UTF_16BE to bytes(0x00, 0x61, 0xDC, 0x00),
        Charsets.US_ASCII to bytes(0x61, 0x80, 0x62),
        TextCharsets.WINDOWS_1252 to bytes(0x61, 0x81, 0x62),
    )

    @Test
    fun `undecodable bytes are flagged in every charset`() {
        for ((charset, input) in undecodableByCharset) {
            withClue(charset) {
                BlockDecoder.decode(input, charset).hadUndecodable.shouldBeTrue()
            }
        }
    }

    @Test
    fun `text equals a plain REPLACE decode for every malformed sample`() {
        val samples = malformedGb18030.map { (name, input) -> Triple(name, TextCharsets.GB18030, input) } +
            undecodableByCharset.map { (charset, input) -> Triple(charset.name(), charset, input) }
        for ((name, charset, input) in samples) {
            withClue(name) {
                BlockDecoder.decode(input, charset).text shouldBe replaceDecode(input, charset)
            }
        }
    }

    @Test
    fun `decodes only the requested length`() {
        val input = gb("你好") + bytes(0x81)
        val decoded = BlockDecoder.decode(input, TextCharsets.GB18030, length = 4)
        decoded.text shouldBe "你好"
        decoded.hadUndecodable.shouldBeFalse()
    }

    @Test
    fun `index and block cache agree on malformed GB18030 at every block size`() = runTest {
        for ((name, input) in malformedGb18030) {
            for (blockSize in 4..17) {
                withClue("$name, block size $blockSize") {
                    verifyIndexAgainstCache(input, TextCharsets.GB18030, blockSize)
                }
            }
        }
    }

    @Test
    fun `per-block flag marks only the blocks holding undecodable bytes`() = runTest {
        val cases = listOf(
            Charsets.UTF_8 to "abcd".toByteArray() + bytes(0xC3, 0x28) + "efgh".toByteArray(),
            Charsets.US_ASCII to "abcd".toByteArray() + bytes(0x80) + "efgh".toByteArray(),
            Charsets.UTF_16LE to "abcd".toByteArray(Charsets.UTF_16LE) + bytes(0x00, 0xDC) +
                "ef".toByteArray(Charsets.UTF_16LE),
            TextCharsets.GB18030 to gb("abcd") + bytes(0x80) + gb("efgh"),
        )
        for ((charset, input) in cases) {
            withClue(charset) {
                val index = verifyIndexAgainstCache(input, charset, blockSize = 4)
                index.hasUndecodable.shouldBeTrue()
                index.blocks.count { it.hasUndecodable } shouldBe 1
            }
        }
    }

    private suspend fun verifyIndexAgainstCache(input: ByteArray, charset: Charset, blockSize: Int): BlockIndex {
        val index = BlockIndexBuilder(blockSize).build(Buffer().write(input), charset)
        index.byteLength shouldBe input.size.toLong()
        for (block in index.blocks) {
            val blockBytes = input.copyOfRange(block.byteStart.toInt(), (block.byteStart + block.byteLen).toInt())
            val decoded = BlockDecoder.decode(blockBytes, charset)
            decoded.text.length shouldBe block.charCount
            decoded.hadUndecodable shouldBe block.hasUndecodable
        }
        // The cache loader checks every block's decoded length against the index
        val document = BlockOriginalDocument(index, charset, maxCachedBlocks = 2) { start, len ->
            input.copyOfRange(start.toInt(), start.toInt() + len)
        }
        document.readChars(0, document.charLength).length.toLong() shouldBe index.charLength
        return index
    }
}
