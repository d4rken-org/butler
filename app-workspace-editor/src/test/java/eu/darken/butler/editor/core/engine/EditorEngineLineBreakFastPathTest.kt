package eu.darken.butler.editor.core.engine

import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

/**
 * Edits carrying a lone '\r' - a real line break in CR and MIXED documents, which the engine hands
 * to the buffer verbatim - must leave the published window describing the NEW line structure, not a
 * '\r' spliced into the line that was edited.
 */
class EditorEngineLineBreakFastPathTest : EditorEngineTestBase() {

    @Test
    fun `a short CR insert refreshes instead of patching the visible line`() = runTest {
        val engine = createEngine("Hello\rWorld")
        engine.totalLines.value shouldBe 2L
        engine.visibleContent.value.text shouldBe "Hello\nWorld"

        engine.setCursorPosition(TextPosition(offset = 0, line = 0, column = 5))
        engine.insertText("\r")

        engine.totalLines.value shouldBe 3L
        engine.cursorPosition.value.line shouldBe 1L
        // Window 0..1 of "Hello\r\rWorld": the new empty line, NOT a '\r' spliced into line 0
        engine.visibleRange.value shouldBe 0L..1L
        engine.visibleContent.value.text shouldBe "Hello\n"
    }

    @Test
    fun `a short CR insert into a mixed document refreshes as well`() = runTest {
        val engine = createEngine("a\nb\rc")
        engine.totalLines.value shouldBe 3L
        engine.visibleContent.value.text shouldBe "a\nb\nc"

        engine.setCursorPosition(TextPosition(offset = 0, line = 1, column = 1))
        engine.insertText("X\rY")

        engine.totalLines.value shouldBe 4L
        engine.cursorPosition.value.line shouldBe 2L
        engine.visibleContent.value.text shouldBe "a\nbX\nY"
    }

    @Test
    fun `deleting a CR at the cursor refreshes instead of patching the visible line`() = runTest {
        val engine = createEngine("ab\rcd")
        engine.totalLines.value shouldBe 2L

        // Cursor at the start of line 1, backspacing over the '\r' that separates the lines
        engine.setCursorPosition(TextPosition(offset = 0, line = 1, column = 0))
        engine.deleteAtCursor(1).shouldBeInstanceOf<EditorEngine.EditOutcome.Applied>().removedText shouldBe "\r"

        engine.totalLines.value shouldBe 1L
        engine.cursorPosition.value.line shouldBe 0L
        engine.visibleContent.value.text shouldBe "abcd"
    }
}
