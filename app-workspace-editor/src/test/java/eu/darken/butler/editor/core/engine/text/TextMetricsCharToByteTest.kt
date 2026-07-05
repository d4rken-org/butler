package eu.darken.butler.editor.core.engine.text

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import testhelpers.BaseTest
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import kotlin.random.Random

/**
 * Boundary-prefix-equality property for the structural [TextMetrics.charToByteInBlock] walk:
 * the reference is ONE full-block decode (naive per-prefix decodes are a WRONG reference - the
 * first byte of a valid "é" decodes to a replacement in isolation). For every code-point
 * boundary c, decoding the first walkByte(c) bytes in isolation must equal the first c chars
 * of the full decode.
 */
class TextMetricsCharToByteTest : BaseTest() {

    private fun decodeIsolated(bytes: ByteArray, charset: Charset = Charsets.UTF_8): String {
        val decoder = charset.newDecoder()
            .onMalformedInput(CodingErrorAction.REPLACE)
            .onUnmappableCharacter(CodingErrorAction.REPLACE)
        val out = CharBuffer.allocate(bytes.size + 2)
        decoder.decode(ByteBuffer.wrap(bytes), out, true)
        decoder.flush(out)
        out.flip()
        return out.toString()
    }

    private fun assertWalkBoundaries(bytes: ByteArray, charset: Charset = Charsets.UTF_8) {
        val full = decodeIsolated(bytes, charset)
        for (c in 0..full.length) {
            // Mid-surrogate-pair offsets are not code-point boundaries
            if (c in 1 until full.length && full[c - 1].isHighSurrogate() && full[c].isLowSurrogate()) continue
            val byteOffset = TextMetrics.charToByteInBlock(bytes, charset, c)
            decodeIsolated(bytes.copyOfRange(0, byteOffset), charset) shouldBe full.substring(0, c)
        }
    }

    @Test
    fun `valid multibyte content at all boundaries`() {
        assertWalkBoundaries("aé中😀z €".toByteArray())
    }

    @Test
    fun `lone continuation bytes`() {
        assertWalkBoundaries(byteArrayOf(0x61, 0x80.toByte(), 0x80.toByte(), 0x80.toByte(), 0x62))
    }

    @Test
    fun `truncated sequences mid-block and at EOF`() {
        // E1 80 (2 of 3) resynced by ASCII; C3 truncated at EOF
        assertWalkBoundaries(byteArrayOf(0xE1.toByte(), 0x80.toByte(), 0x41, 0xC3.toByte()))
        // Truncated 4-byte sequence then a valid 3-byte sequence
        assertWalkBoundaries(byteArrayOf(0xF0.toByte(), 0x9F.toByte(), 0x98.toByte()) + "中".toByteArray())
    }

    @Test
    fun `invalid lead bytes`() {
        assertWalkBoundaries(byteArrayOf(0x61, 0xC0.toByte(), 0xC1.toByte(), 0xFE.toByte(), 0xFF.toByte(), 0x62))
    }

    @Test
    fun `overlong encodings are malformed`() {
        // C0 AF is an overlong '/', E0 80 80 an overlong NUL
        assertWalkBoundaries(byteArrayOf(0xC0.toByte(), 0xAF.toByte(), 0x61))
        assertWalkBoundaries(byteArrayOf(0xE0.toByte(), 0x80.toByte(), 0x80.toByte(), 0x61))
    }

    @Test
    fun `UTF-8-encoded surrogates are malformed`() {
        assertWalkBoundaries(byteArrayOf(0xED.toByte(), 0xA0.toByte(), 0x80.toByte(), 0x61))
    }

    @Test
    fun `code points beyond U+10FFFF are malformed`() {
        assertWalkBoundaries(byteArrayOf(0xF5.toByte(), 0x80.toByte(), 0x80.toByte(), 0x80.toByte(), 0x61))
        assertWalkBoundaries(byteArrayOf(0xF4.toByte(), 0x90.toByte(), 0x80.toByte(), 0x80.toByte(), 0x61))
    }

    @Test
    fun `random mixed garbage and valid content`() {
        val random = Random(11)
        val pieces = listOf(
            "abc".toByteArray(), "é".toByteArray(), "中".toByteArray(), "😀".toByteArray(),
            byteArrayOf(0x80.toByte()), byteArrayOf(0xE1.toByte(), 0x80.toByte()),
            byteArrayOf(0xC0.toByte()), byteArrayOf(0xF5.toByte()), byteArrayOf(0xED.toByte(), 0xA0.toByte()),
        )
        repeat(25) {
            val bytes = (0 until random.nextInt(2, 12))
                .flatMap { pieces.random(random).toList() }
                .toByteArray()
            assertWalkBoundaries(bytes)
        }
    }

    @Test
    fun `UTF-16 mapping is structural including the odd tail`() {
        val bytes = "abc中".toByteArray(Charsets.UTF_16LE) + byteArrayOf(0x41)
        // The dangling odd byte decodes to one replacement char at 2*4=8..9, clamped to size
        assertWalkBoundaries(bytes, Charsets.UTF_16LE)
        TextMetrics.charToByteInBlock(bytes, Charsets.UTF_16LE, 5) shouldBe bytes.size
        assertWalkBoundaries("xyz€".toByteArray(Charsets.UTF_16BE), Charsets.UTF_16BE)
    }

    @Test
    fun `single-byte charsets map by identity`() {
        val bytes = byteArrayOf(0x63, 0x61, 0x66, 0xE9.toByte(), 0x80.toByte(), 0xFF.toByte())
        assertWalkBoundaries(bytes, Charsets.ISO_8859_1)
        assertWalkBoundaries(bytes, Charset.forName("windows-1252"))
        assertWalkBoundaries(bytes, Charsets.US_ASCII)
    }

    @Test
    fun `unvetted charsets are rejected loudly`() {
        shouldThrow<IllegalStateException> {
            TextMetrics.charToByteInBlock("abc".toByteArray(), Charset.forName("Shift_JIS"), 1)
        }
    }
}
