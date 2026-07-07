package eu.darken.butler.searcher.core.engine

import eu.darken.butler.common.files.APathLookup
import eu.darken.butler.common.files.GatewaySwitch
import eu.darken.butler.common.files.LocalPath
import eu.darken.butler.searcher.core.SearchItem
import eu.darken.butler.workspace.contracts.searcher.ContentQuery
import eu.darken.butler.workspace.core.Workspace
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
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

    private class FakeFileHandle(
        private val data: ByteArray,
        private val maxBytesPerRead: Int = Int.MAX_VALUE,
    ) : FileHandle(false) {
        override fun protectedRead(fileOffset: Long, array: ByteArray, arrayOffset: Int, byteCount: Int): Int {
            if (fileOffset >= data.size) return -1
            val toRead = minOf(byteCount.toLong(), data.size - fileOffset, maxBytesPerRead.toLong()).toInt()
            data.copyInto(array, arrayOffset, fileOffset.toInt(), fileOffset.toInt() + toRead)
            return toRead
        }

        override fun protectedWrite(fileOffset: Long, array: ByteArray, arrayOffset: Int, byteCount: Int) =
            throw UnsupportedOperationException()

        override fun protectedFlush() = Unit
        override fun protectedResize(size: Long) = throw UnsupportedOperationException()
        override fun protectedSize(): Long = data.size.toLong()
        override fun protectedClose() = Unit
    }

    private fun mockLookup(fileName: String, byteCount: Long): APathLookup<*> = mockk<APathLookup<LocalPath>> {
        every { name } returns fileName
        every { size } returns byteCount
        every { lookedUp } returns LocalPath.build("/test/$fileName")
    }

    private fun matcherWith(data: ByteArray, openCounter: AtomicInteger): ContentMatcher {
        val gateway = mockk<GatewaySwitch>()
        coEvery { gateway.file(any(), any()) } answers {
            openCounter.incrementAndGet()
            FakeFileHandle(data)
        }
        return ContentMatcher(Workspace.Id(), gateway, TestDispatcherProvider())
    }

    @Test
    fun `unknown extension file is matched with a single open`(): Unit = runBlocking {
        val data = "line one\nthe needle is here\nline three".toByteArray()
        val opens = AtomicInteger(0)
        val matcher = matcherWith(data, opens)

        val match = matcher.matchesContent(
            lookup = mockLookup("notes.unknownext", data.size.toLong()),
            query = ContentQuery(pattern = "needle"),
            includeBinaries = false,
        )

        match.shouldNotBeNull()
        match.matchType shouldBe SearchItem.MatchContext.MatchType.CONTENT
        match.lineNumber shouldBe 2
        opens.get() shouldBe 1
    }

    @Test
    fun `binary file with unknown extension is skipped after a single open`(): Unit = runBlocking {
        val data = byteArrayOf(0x50, 0x4B, 0x00, 0x01, 0x02) + "needle".toByteArray()
        val opens = AtomicInteger(0)
        val matcher = matcherWith(data, opens)

        val match = matcher.matchesContent(
            lookup = mockLookup("archive.unknownext", data.size.toLong()),
            query = ContentQuery(pattern = "needle"),
            includeBinaries = false,
        )

        match.shouldBeNull()
        opens.get() shouldBe 1
    }

    @Test
    fun `utf16 text file becomes content searchable`(): Unit = runBlocking {
        val data = byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + "the needle is here".toByteArray(Charsets.UTF_16LE)
        val opens = AtomicInteger(0)
        val matcher = matcherWith(data, opens)

        val match = matcher.matchesContent(
            lookup = mockLookup("document.unknownext", data.size.toLong()),
            query = ContentQuery(pattern = "needle"),
            includeBinaries = false,
        )

        match.shouldNotBeNull()
        opens.get() shouldBe 1
    }

    @Test
    fun `short reads are filled by the read loop`(): Unit = runBlocking {
        // A source that trickles one byte per read call must still produce the full content
        val data = "line one\nthe needle is here".toByteArray()
        val opens = AtomicInteger(0)
        val gateway = mockk<GatewaySwitch>()
        coEvery { gateway.file(any(), any()) } answers {
            opens.incrementAndGet()
            FakeFileHandle(data, maxBytesPerRead = 1)
        }
        val matcher = ContentMatcher(Workspace.Id(), gateway, TestDispatcherProvider())

        val match = matcher.matchesContent(
            lookup = mockLookup("notes.unknownext", data.size.toLong()),
            query = ContentQuery(pattern = "needle"),
            includeBinaries = false,
        )

        match.shouldNotBeNull()
        match.lineNumber shouldBe 2
        opens.get() shouldBe 1
    }

    @Test
    fun `oversized file is skipped without opening`(): Unit = runBlocking {
        val opens = AtomicInteger(0)
        val matcher = matcherWith("needle".toByteArray(), opens)

        val match = matcher.matchesContent(
            lookup = mockLookup("huge.txt", SearchConfig.MAX_CONTENT_FILE_SIZE + 1),
            query = ContentQuery(pattern = "needle"),
            includeBinaries = false,
        )

        match.shouldBeNull()
        opens.get() shouldBe 0
    }
}
