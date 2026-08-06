package eu.darken.butler.editor.core.engine

import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

/**
 * The document's trailing empty line as the engine sees it: undo/redo across an end-of-document
 * newline must publish the cursor BEFORE refreshing the window, or the refresh grows the loaded
 * range against the pre-operation cursor and the field diverges from the engine.
 */
class EditorEngineTrailingLineTest : EditorEngineTestBase() {

    @Test
    fun `undo and redo of an end-of-document newline keep cursor, range and content in step`() = runTest {
        val engine = createEngine("Hello")

        engine.applyDelta(start = pos(0, 5), newText = "\n", caret = pos(1, 0))
            .shouldBeInstanceOf<EditorEngine.MutationResult.Applied>()
        engine.totalLines.value shouldBe 2L
        engine.cursorPosition.value.line shouldBe 1L
        engine.visibleRange.value.last shouldBe 1L
        engine.visibleContent.value.text shouldBe "Hello\n"

        engine.performUndo().getOrThrow()
        engine.totalLines.value shouldBe 1L
        engine.cursorPosition.value.line shouldBe 0L
        engine.visibleRange.value.last shouldBe 0L
        engine.visibleContent.value.text shouldBe "Hello"

        engine.performRedo().getOrThrow()
        engine.totalLines.value shouldBe 2L
        engine.cursorPosition.value.line shouldBe 1L
        engine.visibleRange.value.last shouldBe 1L
        engine.visibleContent.value.text shouldBe "Hello\n"
        engine.fullContent() shouldBe "Hello\n"
    }

    @ParameterizedTest
    @ValueSource(strings = ["\n", "\r\n", "\r"])
    fun `undo of a deleted end-of-document break restores cursor, range and content`(
        terminator: String,
    ) = runTest {
        val engine = createEngine("Hello$terminator")
        engine.totalLines.value shouldBe 2L

        // Backspace the trailing break away; the field always reports it as '\n'
        engine.applyDelta(start = pos(0, 5), end = pos(1, 0), oldText = "\n", caret = pos(0, 5))
            .shouldBeInstanceOf<EditorEngine.MutationResult.Applied>()
        engine.totalLines.value shouldBe 1L
        engine.visibleContent.value.text shouldBe "Hello"

        engine.performUndo().getOrThrow()
        engine.totalLines.value shouldBe 2L
        engine.cursorPosition.value.line shouldBe 1L
        engine.cursorPosition.value.column shouldBe 0
        engine.visibleRange.value.last shouldBe 1L
        engine.visibleContent.value.text shouldBe "Hello\n"
        engine.fullContent() shouldBe "Hello$terminator"
    }

    @Test
    fun `select all spans the trailing empty line`() = runTest {
        val engine = createEngine("Hello\n")

        val (start, end) = engine.selectAll().getOrThrow()

        start shouldBe TextPosition(0, 0, 0)
        end.offset shouldBe 6L
        end.line shouldBe 1L
        end.column shouldBe 0
    }
}
