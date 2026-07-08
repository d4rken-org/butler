package eu.darken.butler.editor.core.engine

import eu.darken.butler.editor.ui.editor.text.CursorDirection
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

/**
 * Engine behavior over display-truncated lines (cap shrunk via the factory seam): the visible
 * window is capped, the cursor is display-bounded, edits stay offset-exact against the FULL
 * line, and the in-place fast paths bypass to a full refresh whenever the cap is involved.
 */
class EditorEngineDisplayCapTest : EditorEngineTestBase() {

    private val cap = 10

    // Line 0 is 16 chars (truncated at 10), line 1 is short
    private val content = "0123456789ABCDEF\nshort"

    @Test
    fun `open shows the capped window with the truncation map`() = runTest {
        val engine = createEngine(content, displayLineCap = cap)

        engine.visibleContent.value.text shouldBe "0123456789\nshort"
        engine.visibleContent.value.truncatedLines shouldBe mapOf(0L to 6L)
    }

    @Test
    fun `boundary-append inserts at the real offset without changing the display text`() = runTest {
        val engine = createEngine(content, displayLineCap = cap)

        engine.setCursorPosition(TextPosition(offset = 0, line = 0, column = cap))
        engine.insertText("X")

        // The typed char lands at the first hidden position: display unchanged, count +1
        engine.textBuffer!!.getTextForLine(0).getOrThrow() shouldBe "0123456789XABCDEF"
        engine.visibleContent.value.text shouldBe "0123456789\nshort"
        engine.visibleContent.value.truncatedLines shouldBe mapOf(0L to 7L)
    }

    @Test
    fun `backspace after a boundary-append pulls the typed char back`() = runTest {
        val engine = createEngine(content, displayLineCap = cap)
        engine.setCursorPosition(TextPosition(offset = 0, line = 0, column = cap))
        engine.insertText("X")

        engine.deleteAtCursor(1).getOrThrow() shouldBe "X"

        engine.textBuffer!!.getTextForLine(0).getOrThrow() shouldBe "0123456789ABCDEF"
        engine.visibleContent.value.truncatedLines shouldBe mapOf(0L to 6L)
    }

    @Test
    fun `line end navigation clamps to the visible prefix`() = runTest {
        val engine = createEngine(content, displayLineCap = cap)
        engine.setCursorPosition(TextPosition(offset = 0, line = 0, column = 0))

        engine.moveCursor(CursorDirection.LINE_END, extendSelection = false)

        engine.cursorPosition.value.column shouldBe cap
        engine.cursorPosition.value.offset shouldBe cap.toLong()
    }

    @Test
    fun `cursor set into the hidden region normalizes to the marker`() = runTest {
        val engine = createEngine(content, displayLineCap = cap)

        engine.setCursorPosition(TextPosition(offset = 0, line = 0, column = 14))

        engine.cursorPosition.value.column shouldBe cap
        engine.cursorPosition.value.offset shouldBe cap.toLong()
    }

    @Test
    fun `left after a hidden-column cursor set moves within the prefix`() = runTest {
        val engine = createEngine(content, displayLineCap = cap)
        engine.setCursorPosition(TextPosition(offset = 0, line = 0, column = 14))

        engine.moveCursor(CursorDirection.LEFT, extendSelection = false)

        // No invisible hidden-region walk: one step left of the marker
        engine.cursorPosition.value.column shouldBe cap - 1
        engine.cursorPosition.value.offset shouldBe (cap - 1).toLong()
    }

    @Test
    fun `word-left after a hidden-column cursor set stays within the prefix`() = runTest {
        val engine = createEngine("aa bb ccddeeffgghh\nshort", displayLineCap = cap)
        engine.setCursorPosition(TextPosition(offset = 0, line = 0, column = 16))

        engine.moveCursor(CursorDirection.WORD_LEFT, extendSelection = false)

        // Normalized to the marker (col 10), then word-left lands on the visible word start
        engine.cursorPosition.value.column shouldBe 6
    }

    @Test
    fun `deleteAtCursor after a hidden-column cursor set deletes at the marker`() = runTest {
        val engine = createEngine(content, displayLineCap = cap)
        engine.setCursorPosition(TextPosition(offset = 0, line = 0, column = 14))

        engine.deleteAtCursor(1).getOrThrow() shouldBe "9"

        // The last VISIBLE char went, not one deep in the hidden suffix
        engine.textBuffer!!.getTextForLine(0).getOrThrow() shouldBe "012345678ABCDEF"
    }

    @Test
    fun `selectAll offsets are exact on a truncated last line without a full-line read`() = runTest {
        val engine = createEngine("short\n" + "X".repeat(16), displayLineCap = cap)

        val (start, end) = engine.selectAll().getOrThrow()

        start.offset shouldBe 0L
        end.offset shouldBe 22L
        end.line shouldBe 1L
        // The selection END column stays REAL (UI clamps at render time)
        end.column shouldBe 16
    }

    @Test
    fun `paste into a truncated line matches the capped re-read`() = runTest {
        val engine = createEngine(content, displayLineCap = cap)
        engine.setCursorPosition(TextPosition(offset = 0, line = 0, column = 2))

        engine.insertText("xy")

        // The in-place fast path must bypass on truncated lines: display == capped re-read
        engine.textBuffer!!.getTextForLine(0).getOrThrow() shouldBe "01xy23456789ABCDEF"
        engine.visibleContent.value.text shouldBe "01xy234567\nshort"
        engine.visibleContent.value.truncatedLines shouldBe mapOf(0L to 8L)
    }

    @Test
    fun `cap-crossing paste on a near-cap line refreshes to a capped display`() = runTest {
        val engine = createEngine("01234567\nsecond", displayLineCap = cap)
        engine.setCursorPosition(TextPosition(offset = 0, line = 0, column = 8))

        engine.insertText("abcde")

        engine.textBuffer!!.getTextForLine(0).getOrThrow() shouldBe "01234567abcde"
        engine.visibleContent.value.text shouldBe "01234567ab\nsecond"
        engine.visibleContent.value.truncatedLines shouldBe mapOf(0L to 3L)
    }

    @Test
    fun `consecutive IME boundary keystrokes append in order`() = runTest {
        // The field is rebuilt to the capped line after each echo, so every boundary keystroke
        // arrives at column == cap; the engine must chain them AFTER each other via its cursor
        val engine = createEngine("0123456789uvwxyz\nshort", displayLineCap = cap)
        engine.setCursorPosition(TextPosition(offset = 0, line = 0, column = cap))

        engine.replaceText(uiPos(0, 10), uiPos(0, 10), "A", uiPos(0, 11))
        engine.replaceText(uiPos(0, 10), uiPos(0, 10), "B", uiPos(0, 11))

        engine.textBuffer!!.getTextForLine(0).getOrThrow() shouldBe "0123456789ABuvwxyz"
        engine.cursorPosition.value.offset shouldBe 12L
    }

    @Test
    fun `IME backspace at the boundary removes boundary-typed chars last-first`() = runTest {
        val engine = createEngine("0123456789uvwxyz\nshort", displayLineCap = cap)
        engine.setCursorPosition(TextPosition(offset = 0, line = 0, column = cap))
        engine.replaceText(uiPos(0, 10), uiPos(0, 10), "A", uiPos(0, 11))
        engine.replaceText(uiPos(0, 10), uiPos(0, 10), "B", uiPos(0, 11))

        // Rebuilt field backspaces diff as a delete of the last VISIBLE char; the engine
        // redirects it to the char before its cursor (the hidden 'B', then 'A', then '9')
        engine.replaceText(uiPos(0, 9), uiPos(0, 10), "", uiPos(0, 9))
        engine.textBuffer!!.getTextForLine(0).getOrThrow() shouldBe "0123456789Auvwxyz"

        engine.replaceText(uiPos(0, 9), uiPos(0, 10), "", uiPos(0, 9))
        engine.textBuffer!!.getTextForLine(0).getOrThrow() shouldBe "0123456789uvwxyz"

        engine.replaceText(uiPos(0, 9), uiPos(0, 10), "", uiPos(0, 9))
        engine.textBuffer!!.getTextForLine(0).getOrThrow() shouldBe "012345678uvwxyz"
    }

    @Test
    fun `genuine replaces at the boundary are never redirected`() = runTest {
        // Autocorrect rewrites text the IME actually SAW - even with a hidden-region cursor,
        // the replace must target the VISIBLE range it was computed from
        val engine = createEngine("0123456789uvwxyz\nshort", displayLineCap = cap)
        engine.setCursorPosition(TextPosition(offset = 0, line = 0, column = cap))
        engine.replaceText(uiPos(0, 10), uiPos(0, 10), "A", uiPos(0, 11))

        engine.replaceText(uiPos(0, 5), uiPos(0, 10), "XXXXX", uiPos(0, 10))

        engine.textBuffer!!.getTextForLine(0).getOrThrow() shouldBe "01234XXXXXAuvwxyz"
    }

    @Test
    fun `multi-char deletes ending at the boundary are never redirected`() = runTest {
        // A word-swipe or select-and-delete removes VISIBLE text the IME saw; it must not be
        // shifted into the hidden region even when the cursor sits deeper from boundary typing
        val engine = createEngine("0123456789uvwxyz\nshort", displayLineCap = cap)
        engine.setCursorPosition(TextPosition(offset = 0, line = 0, column = cap))
        engine.replaceText(uiPos(0, 10), uiPos(0, 10), "A", uiPos(0, 11))
        engine.replaceText(uiPos(0, 10), uiPos(0, 10), "B", uiPos(0, 11))

        engine.replaceText(uiPos(0, 4), uiPos(0, 10), "", uiPos(0, 4))

        engine.textBuffer!!.getTextForLine(0).getOrThrow() shouldBe "0123ABuvwxyz"
    }

    @Test
    fun `boundary keystroke with the cursor AT the marker inserts at the marker`() = runTest {
        // An explicitly placed cursor (tap, search nav) is normalized to the boundary; typing
        // there must insert at the marker's real offset, not chase any earlier hidden edits
        val engine = createEngine("0123456789uvwxyz\nshort", displayLineCap = cap)
        engine.setCursorPosition(TextPosition(offset = 0, line = 0, column = 14))

        engine.replaceText(uiPos(0, 10), uiPos(0, 10), "Z", uiPos(0, 11))

        engine.textBuffer!!.getTextForLine(0).getOrThrow() shouldBe "0123456789Zuvwxyz"
    }

    private fun uiPos(line: Long, column: Int) = TextPosition(offset = 0, line = line, column = column)

    @Test
    fun `small deletes on untruncated lines still use the in-place fast path result`() = runTest {
        val engine = createEngine("hello world\nsecond", displayLineCap = cap * 10)
        engine.setCursorPosition(TextPosition(offset = 0, line = 0, column = 5))

        engine.deleteAtCursor(1)

        engine.visibleContent.value.text shouldBe "hell world\nsecond"
        engine.visibleContent.value.truncatedLines shouldBe emptyMap()
    }
}
