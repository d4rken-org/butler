package eu.darken.butler.editor.core.engine.text

import eu.darken.butler.editor.core.engine.LineEnding
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import okio.Buffer
import org.junit.jupiter.api.Test
import testhelpers.BaseTest
import java.nio.charset.Charset

class BlockOriginalDocumentTest : BaseTest() {

    private suspend fun docOf(
        content: String,
        charset: Charset = Charsets.UTF_8,
        blockSize: Int = 4,
        maxCachedBlocks: Int = 2,
    ): BlockOriginalDocument {
        val bytes = content.toByteArray(charset)
        val index = BlockIndexBuilder(blockSize).build(Buffer().write(bytes), charset)
        return BlockOriginalDocument(index, charset, maxCachedBlocks) { start, len ->
            bytes.copyOfRange(start.toInt(), start.toInt() + len)
        }
    }

    @Test
    fun `readChars over mixed width content`() = runTest {
        val content = "aé中\nx中é"
        val doc = docOf(content)
        doc.charLength shouldBe content.length.toLong()
        for (start in 0..content.length) {
            for (end in start..content.length) {
                doc.readChars(start.toLong(), end.toLong()) shouldBe content.substring(start, end)
            }
        }
    }

    @Test
    fun `charToByte matches encoded prefix length`() = runTest {
        val content = "aé中\nx中é"
        for (charset in listOf(Charsets.UTF_8, Charsets.UTF_16LE, Charsets.UTF_16BE)) {
            val doc = docOf(content, charset = charset)
            for (i in 0..content.length) {
                doc.charToByte(i.toLong()) shouldBe content.substring(0, i).toByteArray(charset).size.toLong()
            }
        }
    }

    @Test
    fun `charToByte at code point boundaries around surrogate pairs`() = runTest {
        val content = "a😀b中"
        for (charset in listOf(Charsets.UTF_8, Charsets.UTF_16LE)) {
            val doc = docOf(content, charset = charset)
            for (i in listOf(0, 1, 3, 4, 5)) {
                doc.charToByte(i.toLong()) shouldBe content.substring(0, i).toByteArray(charset).size.toLong()
            }
        }
    }

    @Test
    fun `countLineBreaks matches isolated range reference`() = runTest {
        val content = "ab\r\ncd\ne\rf\r\ng"
        val doc = docOf(content)
        for (start in 0..content.length) {
            for (end in start..content.length) {
                doc.countLineBreaks(start.toLong(), end.toLong()) shouldBe
                    TextMetrics.countBreaks(content, start, end).toLong()
            }
        }
    }

    @Test
    fun `findNthLineBreak matches isolated range reference`() = runTest {
        val content = "ab\r\ncd\ne\rf\r\ng"
        val doc = docOf(content)
        for (start in 0 until content.length) {
            for (end in (start + 1)..content.length) {
                val breaks = TextMetrics.countBreaks(content, start, end)
                for (n in 1..breaks) {
                    doc.findNthLineBreak(start.toLong(), end.toLong(), n.toLong()) shouldBe
                        (start + TextMetrics.endOfNthBreak(content, n, start, end)).toLong()
                }
            }
        }
    }

    @Test
    fun `whole document break count with seam CRLFs`() = runTest {
        val content = "a\r\nb\r\nc\nd\re"
        val doc = docOf(content)
        doc.lineBreakCount shouldBe TextMetrics.countBreaks(content).toLong()
    }

    @Test
    fun `single-slot cache stays correct across evictions`() = runTest {
        val content = "0123456789".repeat(20) + "\nend"
        val doc = docOf(content, blockSize = 8, maxCachedBlocks = 1)
        doc.readChars(0, doc.charLength) shouldBe content
        doc.readChars(5, 15) shouldBe content.substring(5, 15)
        doc.readChars(150, 200) shouldBe content.substring(150, 200)
    }

    @Test
    fun `offsets beyond Int MAX are not truncated`() = runTest {
        // Virtual ~4.4GB document of UTF-16LE 'a's: 70k blocks of 64KB
        val blockCount = 70_000
        val blockBytes = 64 * 1024
        val blockChars = blockBytes / 2
        val blocks = buildList {
            var byteStart = 0L
            var charStart = 0L
            repeat(blockCount) {
                add(
                    BlockIndex.Block(
                        byteStart = byteStart,
                        byteLen = blockBytes,
                        charStart = charStart,
                        charCount = blockChars,
                        lineBreakCount = 0,
                        startsWithLf = false,
                        endsWithCr = false,
                        endsWithBreak = false,
                    ),
                )
                byteStart += blockBytes
                charStart += blockChars
            }
        }
        val index = BlockIndex(blocks, LineEnding.LF)
        val requestedStarts = mutableListOf<Long>()
        val doc = BlockOriginalDocument(index, Charsets.UTF_16LE, maxCachedBlocks = 2) { start, len ->
            requestedStarts.add(start)
            ByteArray(len) { i -> if (i % 2 == 0) 'a'.code.toByte() else 0 }
        }

        doc.byteLength shouldBe blockCount.toLong() * blockBytes
        doc.charLength shouldBe blockCount.toLong() * blockChars

        val hugeChar = 2_200_000_123L
        doc.charToByte(hugeChar) shouldBe hugeChar * 2
        doc.readChars(hugeChar, hugeChar + 5) shouldBe "aaaaa"
        requestedStarts.all { it >= 0 }.shouldBeTrue()
        (requestedStarts.max() > Int.MAX_VALUE.toLong()).shouldBeTrue()
    }
}
