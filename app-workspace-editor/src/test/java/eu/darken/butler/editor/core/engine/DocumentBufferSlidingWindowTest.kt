package eu.darken.butler.editor.core.engine

import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

/**
 * The windowed display-read API (non-zero anchor column): sliding the [DocumentBuffer.maxDisplayLineChars]
 * window along a long line via [DocumentBuffer.getLineSlice]/[DocumentBuffer.getDisplayRange] anchors.
 * Complements [DocumentBufferDisplayCapTest] (anchor 0, the default) - here the anchor is > 0.
 */
class DocumentBufferSlidingWindowTest : DocumentBufferTestBase() {

    private val cap = 10

    @Test
    fun `an anchor on a line that fits whole clamps back to column 0`() = runTest {
        val buffer = createBuffer("short", maxDisplayLineChars = cap)

        val slice = buffer.getLineSlice(0, anchorColumn = 100).getOrThrow()
        slice.text shouldBe "short"
        slice.startColumn shouldBe 0L
        slice.hiddenChars shouldBe 0L
    }

    @Test
    fun `an anchor mid-line slides the window with hidden counts on both sides`() = runTest {
        val buffer = createBuffer("0123456789ABCDEF", maxDisplayLineChars = cap)

        val slice = buffer.getLineSlice(0, anchorColumn = 3).getOrThrow()
        slice.text shouldBe "3456789ABC"
        slice.startColumn shouldBe 3L
        slice.hiddenChars shouldBe 3L
    }

    @Test
    fun `an anchor past the last full window clamps so the window never hangs off the end`() = runTest {
        val buffer = createBuffer("0123456789ABCDEF", maxDisplayLineChars = cap)

        // realLength 16, cap 10 -> the furthest a full-width window can start is column 6.
        val slice = buffer.getLineSlice(0, anchorColumn = 100).getOrThrow()
        slice.text shouldBe "6789ABCDEF"
        slice.startColumn shouldBe 6L
        slice.hiddenChars shouldBe 0L
    }

    @Test
    fun `a window never begins on a split surrogate pair`() = runTest {
        // Emoji occupies raw columns 6-7 (high, low). Anchoring at 7 would begin on the low half.
        val buffer = createBuffer("012345😀6789ABCDEF", maxDisplayLineChars = cap)

        val slice = buffer.getLineSlice(0, anchorColumn = 7).getOrThrow()
        slice.text.first().isLowSurrogate() shouldBe false
        // The leading nudge advances past the low surrogate to the next whole code point.
        slice.text shouldBe "6789ABCDE"
        slice.startColumn shouldBe 8L
        slice.hiddenChars shouldBe 1L
    }

    @Test
    fun `anchor 0 is identical to the default single-argument slice`() = runTest {
        val buffer = createBuffer("0123456789ABCDEF", maxDisplayLineChars = cap)

        buffer.getLineSlice(0, anchorColumn = 0).getOrThrow() shouldBe buffer.getLineSlice(0).getOrThrow()
    }

    @Test
    fun `display range applies per-line column anchors and reports start columns`() = runTest {
        val buffer = createBuffer("short\n0123456789ABCDEF", maxDisplayLineChars = cap)

        val window = buffer.getDisplayRange(0, 1, columnAnchors = mapOf(1L to 3L)).getOrThrow()
        window.text shouldBe "short\n3456789ABC"
        window.truncatedLines shouldBe mapOf(1L to 3L)
        window.startColumns shouldBe mapOf(1L to 3L)
    }

    @Test
    fun `display range without anchors leaves start columns empty`() = runTest {
        val buffer = createBuffer("0123456789ABCDEF\nshort", maxDisplayLineChars = cap)

        val window = buffer.getDisplayRange(0, 1).getOrThrow()
        window.text shouldBe "0123456789\nshort"
        window.truncatedLines shouldBe mapOf(0L to 6L)
        window.startColumns shouldBe emptyMap()
    }
}
