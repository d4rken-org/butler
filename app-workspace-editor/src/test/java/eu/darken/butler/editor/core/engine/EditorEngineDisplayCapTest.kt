package eu.darken.butler.editor.core.engine

import eu.darken.butler.editor.ui.editor.text.CursorDirection
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

/**
 * Engine behavior over long lines with horizontal chunking (cap shrunk via the factory seam). The
 * display window SLIDES to follow the caret: the cursor reaches real columns past the cap, the
 * window's anchor (startColumns) tracks it, and edits stay offset-exact against the full line.
 * Replaces the former display-fence semantics (cursor clamped to the visible prefix).
 */
class EditorEngineDisplayCapTest : EditorEngineTestBase() {

    private val cap = 10

    // Line 0 is 16 chars (windowed at cap 10), line 1 is short
    private val content = "0123456789ABCDEF\nshort"

    @Test
    fun `open shows the capped window anchored at column 0`() = runTest {
        val engine = createEngine(content, displayLineCap = cap)

        engine.visibleContent.value.text shouldBe "0123456789\nshort"
        engine.visibleContent.value.truncatedLines shouldBe mapOf(0L to 6L)
        engine.visibleContent.value.startColumns shouldBe emptyMap()
    }

    @Test
    fun `line-end navigation reaches the real end and slides the window`() = runTest {
        val engine = createEngine(content, displayLineCap = cap)
        engine.setCursorPosition(TextPosition(offset = 0, line = 0, column = 0))

        engine.moveCursor(CursorDirection.LINE_END, extendSelection = false)

        engine.cursorPosition.value.column shouldBe 16
        engine.cursorPosition.value.offset shouldBe 16L
        // Window slid to keep the caret visible: shows the tail, anchored past column 0
        engine.visibleContent.value.text shouldBe "6789ABCDEF\nshort"
        engine.visibleContent.value.startColumns shouldBe mapOf(0L to 6L)
        engine.visibleContent.value.truncatedLines shouldBe emptyMap()
    }

    @Test
    fun `a cursor set past the cap is NOT clamped and the window follows it`() = runTest {
        val engine = createEngine(content, displayLineCap = cap)

        engine.setCursorPosition(TextPosition(offset = 0, line = 0, column = 14))

        engine.cursorPosition.value.column shouldBe 14
        engine.cursorPosition.value.offset shouldBe 14L
        engine.visibleContent.value.startColumns shouldBe mapOf(0L to 6L)
    }

    @Test
    fun `typing past the cap inserts at the real offset`() = runTest {
        val engine = createEngine(content, displayLineCap = cap)
        engine.setCursorPosition(TextPosition(offset = 0, line = 0, column = 0))
        engine.moveCursor(CursorDirection.LINE_END, extendSelection = false)

        engine.insertText("Z")

        // Z lands at the true line end, not the old cap boundary
        engine.textBuffer!!.getTextForLine(0).getOrThrow() shouldBe "0123456789ABCDEFZ"
    }

    @Test
    fun `left from a slid caret moves one real column back`() = runTest {
        val engine = createEngine(content, displayLineCap = cap)
        engine.setCursorPosition(TextPosition(offset = 0, line = 0, column = 14))

        engine.moveCursor(CursorDirection.LEFT, extendSelection = false)

        engine.cursorPosition.value.column shouldBe 13
        engine.cursorPosition.value.offset shouldBe 13L
    }

    @Test
    fun `backspace past the cap removes the real preceding char`() = runTest {
        val engine = createEngine(content, displayLineCap = cap)
        engine.setCursorPosition(TextPosition(offset = 0, line = 0, column = 14))

        engine.deleteAtCursor(1).getOrThrow() shouldBe "D"

        engine.textBuffer!!.getTextForLine(0).getOrThrow() shouldBe "0123456789ABCEF"
    }

    @Test
    fun `word-left within the window walks visible words`() = runTest {
        // Cursor stays inside the window (anchor 0): behavior identical to the un-windowed engine.
        val engine = createEngine("aa bb ccddeeffgghh\nshort", displayLineCap = cap)
        engine.setCursorPosition(TextPosition(offset = 0, line = 0, column = 5))

        engine.moveCursor(CursorDirection.WORD_LEFT, extendSelection = false)

        engine.cursorPosition.value.column shouldBe 3 // start of "bb"
    }

    @Test
    fun `goToLine then line-end reaches the real end of a mid-file long line`() = runTest {
        val midFile = "a\nb\n" + "x".repeat(50) + "\nc\nd"
        val engine = createEngine(midFile, displayLineCap = cap)

        engine.goToLine(2).getOrThrow()
        engine.cursorPosition.value.column shouldBe 0

        engine.moveCursor(CursorDirection.LINE_END, extendSelection = false)

        engine.cursorPosition.value.column shouldBe 50
    }

    @Test
    fun `typing after line-end on a mid-file long line inserts at the true end`() = runTest {
        val midFile = "a\nb\n" + "x".repeat(50) + "\nc\nd"
        val engine = createEngine(midFile, displayLineCap = cap)
        engine.goToLine(2).getOrThrow()

        engine.moveCursor(CursorDirection.LINE_END, extendSelection = false)
        engine.insertText("Z")

        engine.textBuffer!!.getTextForLine(2).getOrThrow() shouldBe "x".repeat(50) + "Z"
    }

    @Test
    fun `selectAll offsets stay exact on a truncated last line`() = runTest {
        val engine = createEngine("short\n" + "X".repeat(16), displayLineCap = cap)

        val (start, end) = engine.selectAll().getOrThrow()

        start.offset shouldBe 0L
        end.offset shouldBe 22L
        end.line shouldBe 1L
        end.column shouldBe 16
    }

    @Test
    fun `an edit on a windowed line bypasses the in-place fast path`() = runTest {
        val engine = createEngine(content, displayLineCap = cap)
        engine.setCursorPosition(TextPosition(offset = 0, line = 0, column = 2))

        engine.insertText("xy")

        // Full refresh (not an in-place patch): display matches the capped re-read
        engine.textBuffer!!.getTextForLine(0).getOrThrow() shouldBe "01xy23456789ABCDEF"
        engine.visibleContent.value.text shouldBe "01xy234567\nshort"
        engine.visibleContent.value.truncatedLines shouldBe mapOf(0L to 8L)
    }

    @Test
    fun `small deletes on untruncated lines still use the in-place fast path`() = runTest {
        val engine = createEngine("hello world\nsecond", displayLineCap = cap * 10)
        engine.setCursorPosition(TextPosition(offset = 0, line = 0, column = 5))

        engine.deleteAtCursor(1)

        engine.visibleContent.value.text shouldBe "hell world\nsecond"
        engine.visibleContent.value.truncatedLines shouldBe emptyMap()
    }
}
