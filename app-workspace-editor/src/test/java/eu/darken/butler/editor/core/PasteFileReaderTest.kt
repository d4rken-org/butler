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
        val error = shouldThrow<IllegalArgumentException> { decoder.decodeBytes(bytes) }
        error.message shouldContain "binary"
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
        result.exceptionOrNull().shouldBeInstanceOf<IllegalArgumentException>()
            .message shouldContain "too large"
    }
}
