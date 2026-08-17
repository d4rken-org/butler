package eu.darken.butler.editor.core

import eu.darken.butler.common.files.GatewaySwitch
import eu.darken.butler.common.files.LocalPath
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import testhelpers.BaseTest
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream

class PasteFileReaderTest : BaseTest() {

    private val path = LocalPath.build(File("/tmp/paste-test", "clip.txt"))

    private fun readerFor(stream: InputStream): PasteFileReader {
        val gateway = mockk<GatewaySwitch>().apply {
            coEvery { useRes(any<suspend (Any) -> String>()) } coAnswers {
                firstArg<suspend (Any) -> String>().invoke(this@apply)
            }
            coEvery { openInputStream(any()) } returns stream
        }
        return PasteFileReader(gateway)
    }

    private val decoder = PasteFileReader(mockk())

    // ==================== decodeBytes ====================

    @Test
    fun `valid UTF-8 decodes as UTF-8`() {
        decoder.decodeBytes("Grüße, 世界".toByteArray(Charsets.UTF_8)) shouldBe "Grüße, 世界"
    }

    @Test
    fun `malformed UTF-8 falls back to ISO-8859-1 instead of replacement chars`() {
        // "café" in ISO-8859-1: 0xE9 is not valid UTF-8 here
        val latin1 = byteArrayOf(0x63, 0x61, 0x66, 0xE9.toByte())
        decoder.decodeBytes(latin1) shouldBe "café"
    }

    @Test
    fun `UTF-8 BOM is stripped`() {
        val bytes = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + "Hello".toByteArray()
        decoder.decodeBytes(bytes) shouldBe "Hello"
    }

    @Test
    fun `UTF-16LE text with BOM decodes despite its null bytes`() {
        val bytes = byteArrayOf(0xFF.toByte(), 0xFE.toByte()) +
            "Hello".flatMap { listOf(it.code.toByte(), 0.toByte()) }.toByteArray()
        decoder.decodeBytes(bytes) shouldBe "Hello"
    }

    @Test
    fun `UTF-16BE text with BOM decodes despite its null bytes`() {
        val bytes = byteArrayOf(0xFE.toByte(), 0xFF.toByte()) +
            "Hi".flatMap { listOf(0.toByte(), it.code.toByte()) }.toByteArray()
        decoder.decodeBytes(bytes) shouldBe "Hi"
    }

    @Test
    fun `null bytes without a UTF-16 BOM are rejected as binary`() {
        val bytes = byteArrayOf(0x48, 0x00, 0x65)
        val error = shouldThrow<PasteBinaryException> { decoder.decodeBytes(bytes) }
        error.message shouldContain "binary"
    }

    @Test
    fun `null-free content with many control bytes is rejected as binary`() {
        // The ISO-8859-1 fallback would otherwise decode this to control-char garbage.
        val bytes = byteArrayOf(0x01, 0x02, 0x03, 0x04, 0x05, 0x61, 0x62, 0x63)
        shouldThrow<PasteBinaryException> { decoder.decodeBytes(bytes) }
    }

    @Test
    fun `non-UTF-8 content with many C1 control bytes is rejected as binary`() {
        // 0x80..0x83 are C1 controls; they fail strict UTF-8 and, via the ISO-8859-1 fallback,
        // must be caught by the decoded-text heuristic rather than passed through.
        val bytes = byteArrayOf(0x80.toByte(), 0x81.toByte(), 0x82.toByte(), 0x83.toByte(), 0x61, 0x62, 0x63)
        shouldThrow<PasteBinaryException> { decoder.decodeBytes(bytes) }
    }

    @Test
    fun `tabs newlines and carriage returns are not treated as binary`() {
        val text = "line1\n\tindented\r\nline3\n"
        decoder.decodeBytes(text.toByteArray(Charsets.UTF_8)) shouldBe text
    }

    @Test
    fun `ANSI escape sequences in log text are not treated as binary`() {
        // Captured terminal logs legitimately contain ESC (0x1B); they must paste, not be rejected.
        val text = "\u001B[31mERROR\u001B[0m something failed\n\u001B[32mOK\u001B[0m"
        decoder.decodeBytes(text.toByteArray(Charsets.UTF_8)) shouldBe text
    }

    // ==================== read (streaming cap) ====================

    @Test
    fun `content under the cap reads fully`() = runTest {
        val reader = readerFor(ByteArrayInputStream("Hello World".toByteArray()))

        reader.read(path).getOrThrow() shouldBe "Hello World"
    }

    @Test
    fun `content over the cap is rejected regardless of reported size`() = runTest {
        val oversized = ByteArray((PasteFileReader.MAX_PASTE_FILE_SIZE + 1).toInt()) { 'a'.code.toByte() }
        val reader = readerFor(ByteArrayInputStream(oversized))

        val result = reader.read(path)

        result.isFailure shouldBe true
        result.exceptionOrNull().shouldBeInstanceOf<PasteTooLargeException>()
            .maxBytes shouldBe PasteFileReader.MAX_PASTE_FILE_SIZE
    }

    // ==================== read (stream entry point) ====================

    @Test
    fun `a stream is read and decoded like a file`() = runTest {
        val bytes = byteArrayOf(0xFF.toByte(), 0xFE.toByte()) +
            "Hello".flatMap { listOf(it.code.toByte(), 0.toByte()) }.toByteArray()

        decoder.read { ByteArrayInputStream(bytes) }.getOrThrow() shouldBe "Hello"
    }

    @Test
    fun `a stream over the cap is rejected`() = runTest {
        val oversized = ByteArray((PasteFileReader.MAX_PASTE_FILE_SIZE + 1).toInt()) { 'a'.code.toByte() }

        val result = decoder.read { ByteArrayInputStream(oversized) }

        result.isFailure shouldBe true
        result.exceptionOrNull().shouldBeInstanceOf<PasteTooLargeException>()
            .maxBytes shouldBe PasteFileReader.MAX_PASTE_FILE_SIZE
    }
}
