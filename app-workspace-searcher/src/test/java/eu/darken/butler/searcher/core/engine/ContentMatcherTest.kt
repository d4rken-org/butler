package eu.darken.butler.searcher.core.engine

import eu.darken.butler.common.files.APathLookup
import eu.darken.butler.common.files.GatewaySwitch
import eu.darken.butler.common.files.LocalPath
import eu.darken.butler.searcher.core.SearchItem
import eu.darken.butler.workspace.contracts.searcher.ContentQuery
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking
import okio.FileHandle
import org.junit.jupiter.api.Test
import testhelpers.BaseTest
import testhelpers.coroutine.TestDispatcherProvider

class ContentMatcherTest : BaseTest() {

    // --- detectBinary ---

    @Test
    fun `plain text is not binary`() {
        ContentMatcher.detectBinary("hello world".toByteArray()) shouldBe false
    }

    @Test
    fun `empty content is not binary`() {
        ContentMatcher.detectBinary(ByteArray(0)) shouldBe false
    }

    @Test
    fun `null bytes mean binary`() {
        ContentMatcher.detectBinary(byteArrayOf(0x50, 0x4B, 0x00, 0x01)) shouldBe true
    }

    @Test
    fun `utf16 text with BOM is not binary despite null bytes`() {
        val utf16 = "hello".toByteArray(Charsets.UTF_16LE)
        val withBom = byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + utf16
        withBom.contains(0.toByte()) shouldBe true
        ContentMatcher.detectBinary(withBom) shouldBe false
    }

    // --- decodeContent ---

    @Test
    fun `plain utf8 decodes as utf8`() {
        ContentMatcher.decodeContent("grüße".toByteArray(Charsets.UTF_8)) shouldBe "grüße"
    }

    @Test
    fun `utf8 BOM is stripped`() {
        val bytes = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + "hello".toByteArray()
        ContentMatcher.decodeContent(bytes) shouldBe "hello"
    }

    @Test
    fun `utf16le with BOM decodes correctly`() {
        val bytes = byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + "hello".toByteArray(Charsets.UTF_16LE)
        ContentMatcher.decodeContent(bytes) shouldBe "hello"
    }

    @Test
    fun `utf16be with BOM decodes correctly`() {
        val bytes = byteArrayOf(0xFE.toByte(), 0xFF.toByte()) + "hello".toByteArray(Charsets.UTF_16BE)
        ContentMatcher.decodeContent(bytes) shouldBe "hello"
    }

    @Test
    fun `invalid utf8 falls back to latin1`() {
        // 0xE9 alone is invalid UTF-8 but valid ISO-8859-1 ("é")
        val bytes = "caf".toByteArray() + byteArrayOf(0xE9.toByte())
        ContentMatcher.decodeContent(bytes) shouldBe "café"
    }

    @Test
    fun `empty content decodes to empty string`() {
        ContentMatcher.decodeContent(ByteArray(0)) shouldBe ""
    }

    @Test
    fun `truncated multibyte tail does not force latin1 fallback for the whole buffer`() {
        // "café" cut one byte into the two-byte "é" sequence, as happens at the read-buffer limit
        val cutMidChar = "café".toByteArray(Charsets.UTF_8).let { it.copyOf(it.size - 1) }
        ContentMatcher.decodeContent(cutMidChar, truncated = true) shouldBe "caf"
        // Without the truncation hint, behavior stays as before: strict validation fails → latin1
        ContentMatcher.decodeContent(cutMidChar, truncated = false) shouldBe "cafÃ"
    }

    // --- matchesContent via fake gateway ---

    private open class FakeFileHandle(
        private val data: ByteArray,
        private val maxBytesPerRead: Int = Int.MAX_VALUE,
    ) : FileHandle(false) {
        var maxOffsetRead: Long = 0
            private set

        override fun protectedRead(fileOffset: Long, array: ByteArray, arrayOffset: Int, byteCount: Int): Int {
            if (fileOffset >= data.size) return -1
            val toRead = minOf(byteCount.toLong(), data.size - fileOffset, maxBytesPerRead.toLong()).toInt()
            data.copyInto(array, arrayOffset, fileOffset.toInt(), fileOffset.toInt() + toRead)
            maxOffsetRead = maxOf(maxOffsetRead, fileOffset + toRead)
            return toRead
        }

        override fun protectedWrite(fileOffset: Long, array: ByteArray, arrayOffset: Int, byteCount: Int) =
            throw UnsupportedOperationException()

        override fun protectedFlush() = Unit
        override fun protectedResize(size: Long) = throw UnsupportedOperationException()
        override fun protectedSize(): Long = data.size.toLong()
        override fun protectedClose() = Unit
    }

    private class ExplodingFileHandle : FileHandle(false) {
        override fun protectedRead(fileOffset: Long, array: ByteArray, arrayOffset: Int, byteCount: Int): Int =
            throw IOException("boom")

        override fun protectedWrite(fileOffset: Long, array: ByteArray, arrayOffset: Int, byteCount: Int) =
            throw UnsupportedOperationException()

        override fun protectedFlush() = Unit
        override fun protectedResize(size: Long) = throw UnsupportedOperationException()
        override fun protectedSize(): Long = 0L
        override fun protectedClose() = Unit
    }

    private fun mockLookup(fileName: String, byteCount: Long): APathLookup<*> = mockk<APathLookup<LocalPath>> {
        every { name } returns fileName
        every { size } returns byteCount
        every { lookedUp } returns LocalPath.build("/test/$fileName")
    }

    private fun matcherWith(handleProvider: () -> FileHandle, openCounter: AtomicInteger): ContentMatcher {
        val gateway = mockk<GatewaySwitch>()
        coEvery { gateway.file(any(), any()) } answers {
            openCounter.incrementAndGet()
            handleProvider()
        }
        return ContentMatcher(gateway, TestDispatcherProvider())
    }

    private fun matcherWith(data: ByteArray, openCounter: AtomicInteger): ContentMatcher =
        matcherWith({ FakeFileHandle(data) }, openCounter)

    @Test
    fun `unknown extension file is matched with a single open`(): Unit = runBlocking {
        val data = "line one\nthe needle is here\nline three".toByteArray()
        val opens = AtomicInteger(0)
        val matcher = matcherWith(data, opens)

        val outcome = matcher.matchesContent(
            lookup = mockLookup("notes.unknownext", data.size.toLong()),
            query = ContentQuery(pattern = "needle"),
            includeBinaries = false,
        )

        outcome.shouldBeInstanceOf<ContentMatcher.Outcome.Match>()
        outcome.context.matchType shouldBe SearchItem.MatchContext.MatchType.CONTENT
        outcome.context.lineNumber shouldBe 2
        outcome.degraded.shouldBeFalse()
        opens.get() shouldBe 1
    }

    @Test
    fun `binary file with unknown extension is skipped after a single open`(): Unit = runBlocking {
        val data = byteArrayOf(0x50, 0x4B, 0x00, 0x01, 0x02) + "needle".toByteArray()
        val opens = AtomicInteger(0)
        val matcher = matcherWith(data, opens)

        val outcome = matcher.matchesContent(
            lookup = mockLookup("archive.unknownext", data.size.toLong()),
            query = ContentQuery(pattern = "needle"),
            includeBinaries = false,
        )

        outcome.shouldBeInstanceOf<ContentMatcher.Outcome.Skipped>()
        outcome.reason shouldBe ContentMatcher.Outcome.Skipped.Reason.BINARY
        opens.get() shouldBe 1
    }

    @Test
    fun `utf16le BOM file is streamed and matched across chunks`(): Unit = runBlocking {
        // More than one head-sample of UTF-16 content so streaming decode is exercised too
        val text = buildString {
            repeat(50) { append("some filler line $it\n") }
            append("the needle is here\n")
        }
        val data = byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + text.toByteArray(Charsets.UTF_16LE)
        val opens = AtomicInteger(0)
        val matcher = matcherWith(data, opens)

        val outcome = matcher.matchesContent(
            lookup = mockLookup("document.unknownext", data.size.toLong()),
            query = ContentQuery(pattern = "needle"),
            includeBinaries = false,
        )

        outcome.shouldBeInstanceOf<ContentMatcher.Outcome.Match>()
        outcome.context.matchedLine shouldBe "the needle is here"
        outcome.context.lineNumber shouldBe 51
        opens.get() shouldBe 1
    }

    @Test
    fun `short reads are filled by the read loop`(): Unit = runBlocking {
        // A source that trickles one byte per read call must still produce the full content
        val data = "line one\nthe needle is here".toByteArray()
        val opens = AtomicInteger(0)
        val matcher = matcherWith({ FakeFileHandle(data, maxBytesPerRead = 1) }, opens)

        val outcome = matcher.matchesContent(
            lookup = mockLookup("notes.unknownext", data.size.toLong()),
            query = ContentQuery(pattern = "needle"),
            includeBinaries = false,
        )

        outcome.shouldBeInstanceOf<ContentMatcher.Outcome.Match>()
        outcome.context.lineNumber shouldBe 2
        opens.get() shouldBe 1
    }

    @Test
    fun `oversized file is skipped without opening`(): Unit = runBlocking {
        val opens = AtomicInteger(0)
        val matcher = matcherWith("needle".toByteArray(), opens)

        val outcome = matcher.matchesContent(
            lookup = mockLookup("huge.txt", SearchConfig.MAX_CONTENT_FILE_SIZE + 1),
            query = ContentQuery(pattern = "needle"),
            includeBinaries = false,
        )

        outcome.shouldBeInstanceOf<ContentMatcher.Outcome.Skipped>()
        outcome.reason shouldBe ContentMatcher.Outcome.Skipped.Reason.TOO_LARGE
        opens.get() shouldBe 0
    }

    // --- streaming behavior ---

    @Test
    fun `match beyond the first 128KB is found`(): Unit = runBlocking {
        // The pre-streaming implementation only searched the first CONTENT_READ_BUFFER bytes
        val data = buildString {
            while (length < SearchConfig.CONTENT_READ_BUFFER + 64 * 1024) {
                append("filler line without anything interesting\n")
            }
            append("the needle is here\n")
        }.toByteArray()
        val opens = AtomicInteger(0)
        val matcher = matcherWith(data, opens)

        val outcome = matcher.matchesContent(
            lookup = mockLookup("big.txt", data.size.toLong()),
            query = ContentQuery(pattern = "needle"),
            includeBinaries = false,
        )

        outcome.shouldBeInstanceOf<ContentMatcher.Outcome.Match>()
        outcome.context.matchedLine shouldBe "the needle is here"
        outcome.degraded.shouldBeFalse()
    }

    @Test
    fun `match on a line spanning a chunk boundary is found`(): Unit = runBlocking {
        // The needle word straddles the head+CONTENT_READ_BUFFER decode boundary
        val boundary = ContentMatcher.BINARY_SNIFF_SIZE + SearchConfig.CONTENT_READ_BUFFER
        val fillerLine = "0123456789ABCDE\n" // 16 bytes
        val fillerCount = (boundary / fillerLine.length) - 1
        val data = buildString {
            repeat(fillerCount) { append(fillerLine) }
            append("x".repeat(13))
            append("needle straddles the boundary\n")
        }.toByteArray()
        val opens = AtomicInteger(0)
        val matcher = matcherWith(data, opens)

        val outcome = matcher.matchesContent(
            lookup = mockLookup("straddle.txt", data.size.toLong()),
            query = ContentQuery(pattern = "needle"),
            includeBinaries = false,
        )

        outcome.shouldBeInstanceOf<ContentMatcher.Outcome.Match>()
        outcome.context.matchedLine shouldBe "x".repeat(13) + "needle straddles the boundary"
        outcome.context.lineNumber shouldBe fillerCount + 1
    }

    @Test
    fun `crlf pair split across chunk boundary counts as one line break`(): Unit = runBlocking {
        // Head sample is exactly BINARY_SNIFF_SIZE bytes: place the CR as its last byte and the
        // LF as the first streamed byte — a broken implementation would count an extra line
        val data = ("A".repeat(ContentMatcher.BINARY_SNIFF_SIZE - 1) + "\r" + "\n" + "the needle is here\n")
            .toByteArray()
        val opens = AtomicInteger(0)
        val matcher = matcherWith(data, opens)

        val outcome = matcher.matchesContent(
            lookup = mockLookup("crlf.txt", data.size.toLong()),
            query = ContentQuery(pattern = "needle"),
            includeBinaries = false,
        )

        outcome.shouldBeInstanceOf<ContentMatcher.Outcome.Match>()
        outcome.context.lineNumber shouldBe 2
        outcome.context.matchedLine shouldBe "the needle is here"
    }

    @Test
    fun `four byte utf8 char split across chunks decodes without mojibake`(): Unit = runBlocking {
        // "😀" is 4 bytes (F0 9F 98 80); the head sample cuts it in half
        val line = "A".repeat(ContentMatcher.BINARY_SNIFF_SIZE - 2) + "😀 with a needle"
        val data = (line + "\n").toByteArray()
        val opens = AtomicInteger(0)
        val matcher = matcherWith(data, opens)

        val outcome = matcher.matchesContent(
            lookup = mockLookup("emoji.txt", data.size.toLong()),
            query = ContentQuery(pattern = "needle"),
            includeBinaries = false,
        )

        outcome.shouldBeInstanceOf<ContentMatcher.Outcome.Match>()
        outcome.context.matchedLine shouldBe line
        outcome.context.matchedLine!! shouldContain "😀"
        outcome.context.matchedLine!! shouldNotContain "�"
    }

    @Test
    fun `ascii head with latin1 high bytes later still matches ascii patterns`(): Unit = runBlocking {
        // The head is clean UTF-8, a later line has an invalid UTF-8 byte (0xE9 = é in latin1);
        // decoding must switch to ISO-8859-1 and keep matching instead of failing
        val prefix = buildString { repeat(60) { append("clean ascii filler line $it\n") } }
        val data = prefix.toByteArray() +
            "caf".toByteArray() + byteArrayOf(0xE9.toByte()) + " latin line\n".toByteArray() +
            "the needle is here\n".toByteArray()
        val opens = AtomicInteger(0)
        val matcher = matcherWith(data, opens)

        val outcome = matcher.matchesContent(
            lookup = mockLookup("mixed.txt", data.size.toLong()),
            query = ContentQuery(pattern = "needle"),
            includeBinaries = false,
        )

        outcome.shouldBeInstanceOf<ContentMatcher.Outcome.Match>()
        outcome.context.matchedLine shouldBe "the needle is here"
        outcome.context.lineNumber shouldBe 62
        outcome.context.contextBefore!!.last() shouldBe "café latin line"
    }

    @Test
    fun `line longer than the cap is matched within the window and flagged degraded`(): Unit = runBlocking {
        val data = ("needle near the start " + "A".repeat(SearchConfig.MAX_LINE_LENGTH + 1024) + "\n" + "after\n")
            .toByteArray()
        val opens = AtomicInteger(0)
        val matcher = matcherWith(data, opens)

        val outcome = matcher.matchesContent(
            lookup = mockLookup("longline.txt", data.size.toLong()),
            query = ContentQuery(pattern = "needle"),
            includeBinaries = false,
        )

        outcome.shouldBeInstanceOf<ContentMatcher.Outcome.Match>()
        outcome.degraded.shouldBeTrue()
        outcome.context.matchedLine!!.length shouldBe SearchConfig.MAX_LINE_LENGTH
    }

    @Test
    fun `long line without a match is degraded NoMatch`(): Unit = runBlocking {
        val data = ("A".repeat(SearchConfig.MAX_LINE_LENGTH + 1024) + "\n").toByteArray()
        val opens = AtomicInteger(0)
        val matcher = matcherWith(data, opens)

        val outcome = matcher.matchesContent(
            lookup = mockLookup("longline.txt", data.size.toLong()),
            query = ContentQuery(pattern = "needle"),
            includeBinaries = false,
        )

        outcome.shouldBeInstanceOf<ContentMatcher.Outcome.NoMatch>()
        outcome.degraded.shouldBeTrue()
    }

    @Test
    fun `reading stops shortly after the match and its context lines`(): Unit = runBlocking {
        val data = buildString {
            append("line one\n")
            append("line two\n")
            append("the needle is here\n")
            append("context after one\n")
            append("context after two\n")
            while (length < 1024 * 1024) append("never-read filler line\n")
        }.toByteArray()
        val opens = AtomicInteger(0)
        val handle = FakeFileHandle(data)
        val matcher = matcherWith({ handle }, opens)

        val outcome = matcher.matchesContent(
            lookup = mockLookup("early.txt", data.size.toLong()),
            query = ContentQuery(pattern = "needle"),
            includeBinaries = false,
        )

        outcome.shouldBeInstanceOf<ContentMatcher.Outcome.Match>()
        outcome.context.contextAfter shouldBe listOf("context after one", "context after two")
        // Early exit: only a fraction of the 1MB file may have been pulled from the source
        (handle.maxOffsetRead < 64 * 1024).shouldBeTrue()
    }

    @Test
    fun `read exception surfaces as Failed outcome`(): Unit = runBlocking {
        val opens = AtomicInteger(0)
        val matcher = matcherWith({ ExplodingFileHandle() }, opens)

        val outcome = matcher.matchesContent(
            lookup = mockLookup("broken.txt", 42L),
            query = ContentQuery(pattern = "needle"),
            includeBinaries = false,
        )

        outcome.shouldBeInstanceOf<ContentMatcher.Outcome.Failed>()
        outcome.error.shouldBeInstanceOf<IOException>()
        outcome.error.message shouldBe "boom"
    }
}
