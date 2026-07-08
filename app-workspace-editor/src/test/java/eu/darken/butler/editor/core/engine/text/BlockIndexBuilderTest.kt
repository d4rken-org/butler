package eu.darken.butler.editor.core.engine.text

import eu.darken.butler.common.files.text.CharsetDetector
import eu.darken.butler.editor.core.engine.LineEnding
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import okio.Buffer
import org.junit.jupiter.api.Test
import testhelpers.BaseTest
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest

class BlockIndexBuilderTest : BaseTest() {

    private suspend fun build(bytes: ByteArray, charset: Charset = Charsets.UTF_8, blockSize: Int = 8): BlockIndex =
        BlockIndexBuilder(blockSize).build(Buffer().write(bytes), charset)

    private fun decodeIsolated(bytes: ByteArray, charset: Charset): String {
        val decoder = charset.newDecoder()
            .onMalformedInput(CodingErrorAction.REPLACE)
            .onUnmappableCharacter(CodingErrorAction.REPLACE)
        val charBuffer = CharBuffer.allocate(bytes.size + 2)
        decoder.decode(ByteBuffer.wrap(bytes), charBuffer, true)
        decoder.flush(charBuffer)
        charBuffer.flip()
        return charBuffer.toString()
    }

    /** Decodes every block's byte range in isolation and checks all per-block bookkeeping. */
    private fun BlockIndex.verifyAgainst(bytes: ByteArray, charset: Charset): String {
        var expectedByteStart = 0L
        var expectedCharStart = 0L
        val full = StringBuilder()
        for (block in blocks) {
            block.byteStart shouldBe expectedByteStart
            block.charStart shouldBe expectedCharStart
            block.byteLen shouldBeGreaterThan 0
            block.charCount shouldBeGreaterThan 0
            val text = decodeIsolated(
                bytes.copyOfRange(block.byteStart.toInt(), (block.byteStart + block.byteLen).toInt()),
                charset,
            )
            text.length shouldBe block.charCount
            block.lineBreakCount shouldBe TextMetrics.countBreaks(text)
            block.startsWithLf shouldBe TextMetrics.startsWithLf(text)
            block.endsWithCr shouldBe TextMetrics.endsWithCr(text)
            block.endsWithBreak shouldBe TextMetrics.endsWithBreak(text)
            full.append(text)
            expectedByteStart += block.byteLen
            expectedCharStart += block.charCount
        }
        byteLength shouldBe bytes.size.toLong()
        charLength shouldBe full.length.toLong()
        lineBreakCount shouldBe TextMetrics.countBreaks(full).toLong()
        return full.toString()
    }

    @Test
    fun `CJK content snaps every block edge`() = runTest {
        val content = "中文中文中文"
        val bytes = content.toByteArray(Charsets.UTF_8)
        bytes.size shouldBe 18
        val index = build(bytes, blockSize = 8)
        index.verifyAgainst(bytes, Charsets.UTF_8) shouldBe content
        index.blocks.size shouldBeGreaterThan 1
    }

    @Test
    fun `emoji straddling a block edge is carried whole`() = runTest {
        val content = "aa😀bb"
        val bytes = content.toByteArray(Charsets.UTF_8)
        val index = build(bytes, blockSize = 4)
        val reconstructed = index.verifyAgainst(bytes, Charsets.UTF_8)
        reconstructed shouldBe content
        for (block in index.blocks.dropLast(1)) {
            val text = decodeIsolated(
                bytes.copyOfRange(block.byteStart.toInt(), (block.byteStart + block.byteLen).toInt()),
                Charsets.UTF_8,
            )
            text.last().isHighSurrogate().shouldBeFalse()
        }
    }

    @Test
    fun `UTF-16LE with tiny blocks carries surrogate halves`() = runTest {
        val content = "a😀b😁c"
        val bytes = content.toByteArray(Charsets.UTF_16LE)
        for (blockSize in listOf(4, 6, 8)) {
            val index = build(bytes, charset = Charsets.UTF_16LE, blockSize = blockSize)
            index.verifyAgainst(bytes, Charsets.UTF_16LE) shouldBe content
            for (block in index.blocks.dropLast(1)) {
                val text = decodeIsolated(
                    bytes.copyOfRange(block.byteStart.toInt(), (block.byteStart + block.byteLen).toInt()),
                    Charsets.UTF_16LE,
                )
                text.last().isHighSurrogate().shouldBeFalse()
            }
        }
    }

    @Test
    fun `UTF-16BE with tiny blocks carries surrogate halves`() = runTest {
        val content = "中😀\r\nx"
        val bytes = content.toByteArray(Charsets.UTF_16BE)
        val index = build(bytes, charset = Charsets.UTF_16BE, blockSize = 4)
        index.verifyAgainst(bytes, Charsets.UTF_16BE) shouldBe content
    }

    @Test
    fun `UTF-16 content behind a BOM indexes post-BOM bytes`() = runTest {
        val content = "Hi\r\n中"
        val withBom = byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + content.toByteArray(Charsets.UTF_16LE)
        val detection = CharsetDetector.detect(withBom)
        detection.charset shouldBe Charsets.UTF_16LE
        detection.bomSize shouldBe 2
        val logical = withBom.copyOfRange(detection.bomSize, withBom.size)
        val index = build(logical, charset = detection.charset, blockSize = 6)
        index.verifyAgainst(logical, Charsets.UTF_16LE) shouldBe content
    }

    @Test
    fun `BOM-only file yields empty index`() = runTest {
        val bytes = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())
        val detection = CharsetDetector.detect(bytes)
        detection.charset shouldBe Charsets.UTF_8
        detection.bomSize shouldBe 3
        val index = build(bytes.copyOfRange(3, 3))
        index.blocks.shouldBeEmpty()
        index.charLength shouldBe 0L
        index.byteLength shouldBe 0L
        index.lineEnding shouldBe LineEnding.LF
    }

    @Test
    fun `file ending mid-sequence decodes with replacement`() = runTest {
        val truncated = "ab中".toByteArray(Charsets.UTF_8).copyOfRange(0, 4)
        val expected = String(truncated, Charsets.UTF_8)
        val index = build(truncated, blockSize = 4)
        index.verifyAgainst(truncated, Charsets.UTF_8) shouldBe expected
        index.charLength shouldBe expected.length.toLong()
    }

    @Test
    fun `CRLF split across block edge counts once`() = runTest {
        val content = "aaaaaaa\r\nbb"
        val bytes = content.toByteArray(Charsets.UTF_8)
        val index = build(bytes, blockSize = 8)
        index.blocks[0].endsWithCr.shouldBeTrue()
        index.blocks[1].startsWithLf.shouldBeTrue()
        index.lineBreakCount shouldBe 1L
        index.lineEnding shouldBe LineEnding.CRLF
        index.verifyAgainst(bytes, Charsets.UTF_8) shouldBe content
    }

    @Test
    fun `line ending detection`() = runTest {
        build("a\nb\nc".toByteArray()).lineEnding shouldBe LineEnding.LF
        build("a\r\nb\r\nc".toByteArray()).lineEnding shouldBe LineEnding.CRLF
        build("a\rb\rc".toByteArray()).lineEnding shouldBe LineEnding.CR
        build("a\nb\r\nc\rd".toByteArray()).lineEnding shouldBe LineEnding.MIXED
        build("no breaks".toByteArray()).lineEnding shouldBe LineEnding.LF
    }

    @Test
    fun `empty input yields empty index`() = runTest {
        val index = build(ByteArray(0))
        index.blocks.shouldBeEmpty()
        index.charLength shouldBe 0L
        index.lineEnding shouldBe LineEnding.LF
    }

    @Test
    fun `progress reports monotonically up to total bytes`() = runTest {
        val bytes = "0123456789".repeat(10).toByteArray()
        val reports = mutableListOf<Long>()
        BlockIndexBuilder(blockSize = 16).build(Buffer().write(bytes), Charsets.UTF_8) { reports.add(it) }
        reports shouldBe reports.sorted()
        reports.last() shouldBe bytes.size.toLong()
    }

    @Test
    fun `block edges never end mid UTF-8 sequence across odd block sizes`() = runTest {
        // Regression: Android's ICU decoder consumes partial sequences at buffer ends, so edge
        // snapping must come from byte inspection, not decoder underflow
        val content = "中文行1 日本語のテキスト x".repeat(50)
        val bytes = content.toByteArray(Charsets.UTF_8)
        for (blockSize in listOf(4, 5, 7, 8, 16, 61, 64)) {
            val index = build(bytes, blockSize = blockSize)
            index.verifyAgainst(bytes, Charsets.UTF_8) shouldBe content
        }
    }

    @Test
    fun `unpaired high surrogate at block edge decodes consistently`() = runTest {
        // UTF-16LE 'a', lone \uD800, 'A' - crafted raw since encoders replace lone surrogates
        val bytes = byteArrayOf(0x61, 0x00, 0x00, 0xD8.toByte(), 0x41, 0x00)
        val index = build(bytes, charset = Charsets.UTF_16LE, blockSize = 4)
        index.verifyAgainst(bytes, Charsets.UTF_16LE) shouldBe String(bytes, Charsets.UTF_16LE)
    }

    @Test
    fun `unpaired high surrogate at EOF decodes consistently`() = runTest {
        val bytes = byteArrayOf(0x61, 0x00, 0x00, 0xD8.toByte())
        val index = build(bytes, charset = Charsets.UTF_16LE, blockSize = 4)
        index.verifyAgainst(bytes, Charsets.UTF_16LE) shouldBe String(bytes, Charsets.UTF_16LE)
    }

    @Test
    fun `block index lookups`() = runTest {
        val content = "aaaa\nbbbb\ncccc\ndddd"
        val index = build(content.toByteArray(), blockSize = 4)
        for (offset in content.indices) {
            val block = index.blocks[index.blockForChar(offset.toLong())]
            (offset.toLong() in block.charStart until block.charStart + block.charCount).shouldBeTrue()
        }
        for (n in 1..3) {
            val block = index.blockForLineBreak(n.toLong())
            (index.breaksBeforeBlock(block) < n).shouldBeTrue()
            (index.breaksBeforeBlock(block + 1) >= n).shouldBeTrue()
        }
    }

    @Test
    fun `block digests match recomputed hashes`() = runTest {
        val bytes = ("0123456789".repeat(5) + "abc").toByteArray()
        val index = build(bytes, blockSize = 10)
        index.blockDigests.size shouldBe index.blocks.size
        index.blocks.forEachIndexed { i, block ->
            val blockBytes = bytes.copyOfRange(block.byteStart.toInt(), (block.byteStart + block.byteLen).toInt())
            val expected = BlockIndexBuilder.truncateDigest(
                MessageDigest.getInstance("SHA-256").digest(blockBytes),
            )
            index.blockDigests[i] shouldBe expected
        }
    }

    @Test
    fun `digest counts for empty and single-block inputs`() = runTest {
        build(ByteArray(0)).blockDigests.size shouldBe 0
        build("abc".toByteArray()).blockDigests.size shouldBe 1
    }

    @Test
    fun `max line length for a run straddling block edges`() = runTest {
        // blockSize 8: the 20-char line spans three decoded blocks; the run must carry across
        val content = "ab\n" + "x".repeat(20) + "\ncd"
        build(content.toByteArray(), blockSize = 8).maxLineLength shouldBe 20L
    }

    @Test
    fun `max line length with CRLF straddling a block edge does not double-end the run`() = runTest {
        // blockSize 8 splits "aaaaaaa\r" | "\nbb": the pending CR ends the run at the '\r';
        // resolving it in the next block must not end (or extend) anything again
        val content = "aaaaaaa\r\nbbb"
        val index = build(content.toByteArray(), blockSize = 8)
        index.maxLineLength shouldBe 7L
    }

    @Test
    fun `max line length counts the unterminated final line`() = runTest {
        build("ab\ncdef".toByteArray()).maxLineLength shouldBe 4L
    }

    @Test
    fun `max line length with lone CR breaks`() = runTest {
        build("aaa\rbb\rc".toByteArray()).maxLineLength shouldBe 3L
    }

    @Test
    fun `max line length of an empty file is zero`() = runTest {
        build(ByteArray(0)).maxLineLength shouldBe 0L
    }

    @Test
    fun `max line length of a breaks-only file is zero`() = runTest {
        build("\n\n\n".toByteArray()).maxLineLength shouldBe 0L
        build("\r\n\r\n".toByteArray()).maxLineLength shouldBe 0L
    }

    @Test
    fun `max line length with a trailing break`() = runTest {
        build("abc\n".toByteArray()).maxLineLength shouldBe 3L
    }

    @Test
    fun `max line length with consecutive empty lines`() = runTest {
        build("a\n\n\nbb".toByteArray()).maxLineLength shouldBe 2L
    }

    @Test
    fun `max line length with CRLF followed by an empty line`() = runTest {
        build("abc\r\n\r\nd".toByteArray()).maxLineLength shouldBe 3L
    }

    @Test
    fun `max line length with a pending CR at EOF`() = runTest {
        build("abcd\r".toByteArray()).maxLineLength shouldBe 4L
    }

    @Test
    fun `max line length for a single line without breaks`() = runTest {
        build("0123456789".repeat(5).toByteArray(), blockSize = 8).maxLineLength shouldBe 50L
    }
}
