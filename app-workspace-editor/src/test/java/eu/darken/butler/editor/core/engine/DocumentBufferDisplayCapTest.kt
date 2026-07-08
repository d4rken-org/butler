package eu.darken.butler.editor.core.engine

import io.kotest.matchers.maps.shouldBeEmpty
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

/**
 * The display-read API's per-line cap ([DocumentBuffer.maxDisplayLineChars], shrunk via the
 * factory seam): slices, hidden counts, and their consistency across edits. Full-fidelity
 * reads ([DocumentBuffer.getTextForLine] etc.) are intentionally NOT capped.
 */
class DocumentBufferDisplayCapTest : DocumentBufferTestBase() {

    private val cap = 10

    @Test
    fun `line under the cap is returned whole`() = runTest {
        val buffer = createBuffer("short", maxDisplayLineChars = cap)

        val slice = buffer.getLineSlice(0).getOrThrow()
        slice.text shouldBe "short"
        slice.startColumn shouldBe 0L
        slice.hiddenChars shouldBe 0L
    }

    @Test
    fun `line exactly at the cap is not truncated`() = runTest {
        val buffer = createBuffer("0123456789", maxDisplayLineChars = cap)

        val slice = buffer.getLineSlice(0).getOrThrow()
        slice.text shouldBe "0123456789"
        slice.hiddenChars shouldBe 0L
    }

    @Test
    fun `line over the cap is sliced with an exact hidden count`() = runTest {
        val buffer = createBuffer("0123456789ABCDEF", maxDisplayLineChars = cap)

        val slice = buffer.getLineSlice(0).getOrThrow()
        slice.text shouldBe "0123456789"
        slice.hiddenChars shouldBe 6L
    }

    @Test
    fun `slice never ends on a split surrogate pair`() = runTest {
        // The emoji's high surrogate sits exactly at the cap boundary (index 9-10)
        val buffer = createBuffer("012345678😀xyz", maxDisplayLineChars = cap)

        val slice = buffer.getLineSlice(0).getOrThrow()
        slice.text shouldBe "012345678"
        slice.hiddenChars shouldBe 5L
    }

    @Test
    fun `an untruncated line matches the full-fidelity read - no backoff without truncation`() = runTest {
        // The data source round-trips through bytes, so a lone surrogate arrives as U+FFFD;
        // whatever the buffer holds, the slice must be IDENTICAL to the uncapped read
        val buffer = createBuffer("abc" + '\uD83D', maxDisplayLineChars = cap)

        val slice = buffer.getLineSlice(0).getOrThrow()
        slice.text shouldBe buffer.getTextForLine(0).getOrThrow()
        slice.hiddenChars shouldBe 0L
    }

    @Test
    fun `display window maps only truncated lines with exact hidden counts`() = runTest {
        val buffer = createBuffer(
            "short\n0123456789ABCDEF\nmid\n0123456789XY",
            maxDisplayLineChars = cap,
        )

        val window = buffer.getDisplayRange(0, 3).getOrThrow()
        window.text shouldBe "short\n0123456789\nmid\n0123456789"
        window.truncatedLines shouldBe mapOf(1L to 6L, 3L to 2L)
    }

    @Test
    fun `display window of untruncated lines has an empty map`() = runTest {
        val buffer = createBuffer("aaa\nbbb", maxDisplayLineChars = cap)

        val window = buffer.getDisplayRange(0, 1).getOrThrow()
        window.text shouldBe "aaa\nbbb"
        window.truncatedLines.shouldBeEmpty()
    }

    @Test
    fun `CRLF break chars are excluded from lengths and slices`() = runTest {
        val buffer = createBuffer("abc\r\n0123456789ABCDEF\r\nx", maxDisplayLineChars = cap)

        buffer.getLineLength(0).getOrThrow() shouldBe 3L
        buffer.getLineLength(1).getOrThrow() shouldBe 16L
        val slice = buffer.getLineSlice(1).getOrThrow()
        slice.text shouldBe "0123456789"
        slice.hiddenChars shouldBe 6L
    }

    @Test
    fun `prefix edit shifts a char across the cap boundary consistently`() = runTest {
        val buffer = createBuffer("0123456789ABCDEF", maxDisplayLineChars = cap)

        buffer.deleteText(TextPosition(0, 0, 0), TextPosition(1, 0, 1)).getOrThrow()

        val slice = buffer.getLineSlice(0).getOrThrow()
        slice.text shouldBe "123456789A"
        slice.hiddenChars shouldBe 5L
        buffer.getTextForLine(0).getOrThrow() shouldBe "123456789ABCDEF"
    }

    @Test
    fun `getLineLength is exact on a truncated line`() = runTest {
        val buffer = createBuffer("x".repeat(12_345), maxDisplayLineChars = cap)

        buffer.getLineLength(0).getOrThrow() shouldBe 12_345L
    }

    @Test
    fun `range reads preserve separators of leading empty lines`() = runTest {
        val buffer = createBuffer("\n\nabc\ndef", maxDisplayLineChars = cap)

        buffer.getDisplayRange(0, 3).getOrThrow().text shouldBe "\n\nabc\ndef"
        buffer.getTextForRange(0, 3).getOrThrow() shouldBe "\n\nabc\ndef"
    }

    @Test
    fun `hasLongLines is not stamped for in-memory sources`() = runTest {
        val buffer = createBuffer("x".repeat(100), maxDisplayLineChars = cap)

        // Memory sources carry no hasLongLines flag; the cap itself still applies
        buffer.contentSource.value shouldBe ContentSource.Memory(size = 100L)
        buffer.getLineSlice(0).getOrThrow().hiddenChars shouldBe 90L
    }
}
