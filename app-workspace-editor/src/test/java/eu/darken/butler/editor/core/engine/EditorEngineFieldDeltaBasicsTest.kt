package eu.darken.butler.editor.core.engine

import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

/**
 * Edit semantics of [EditorEngine.applyFieldDelta], the single-region edit all soft-keyboard input
 * flows through.
 *
 * Positions use placeholder offset=0; the engine re-resolves offsets from line/column via the
 * buffer, so tests only need to supply line/column (matching what the UI sends with virtual
 * scrolling). The token/conflict protocol itself is covered by [EditorEngineFieldDeltaTest].
 */
class EditorEngineFieldDeltaBasicsTest : EditorEngineTestBase() {

    @Test
    fun `replace range updates content cursor and clears selection`() = runTest {
        val engine = createEngine("Hello World")

        engine.applyDelta(pos(0, 6), pos(0, 11), oldText = "World", newText = "Kotlin", caret = pos(0, 12))

        engine.fullContent() shouldBe "Hello Kotlin"
        engine.cursorPosition.value.offset shouldBe 12L
        engine.cursorPosition.value.line shouldBe 0L
        engine.cursorPosition.value.column shouldBe 12
        engine.selectionRange.value shouldBe null
        (engine.state.value as EditorState.Loaded).isModified shouldBe true
    }

    @Test
    fun `pure insert via empty range`() = runTest {
        val engine = createEngine("abcd")

        engine.applyDelta(pos(0, 2), newText = "X", caret = pos(0, 3))

        engine.fullContent() shouldBe "abXcd"
        engine.cursorPosition.value.column shouldBe 3
    }

    @Test
    fun `pure delete via empty inserted text`() = runTest {
        val engine = createEngine("abcde")

        engine.applyDelta(pos(0, 1), pos(0, 4), oldText = "bcd", caret = pos(0, 1))

        engine.fullContent() shouldBe "ae"
        engine.cursorPosition.value.column shouldBe 1
    }

    @Test
    fun `out-of-range line from a diverged field conflicts, it does not throw`() = runTest {
        // Repro of the crash: the field diverged and asked for a line the buffer never had
        // (findOffset(line=8, total=2) threw IndexOutOfBoundsException).
        val engine = createEngine("first line\nsecond line")

        engine.applyDelta(pos(8, 0), newText = "\n", caret = pos(9, 0))
            .shouldBeInstanceOf<EditorEngine.MutationResult.Conflict>()

        engine.fullContent() shouldBe "first line\nsecond line"
        (engine.state.value as EditorState.Loaded).isModified shouldBe false
    }

    @Test
    fun `a stale deletion whose endpoints both clamp conflicts instead of deleting`() = runTest {
        // findOffset CLAMPS an out-of-range column, so both endpoints collapse onto the line end;
        // the text the field claims to remove is what exposes the divergence.
        val engine = createEngine("abc\ndef")

        engine.applyDelta(pos(0, 5), pos(0, 9), oldText = "xxxx", caret = pos(0, 5))
            .shouldBeInstanceOf<EditorEngine.MutationResult.Conflict>()

        engine.fullContent() shouldBe "abc\ndef"
        (engine.state.value as EditorState.Loaded).isModified shouldBe false
    }

    @Test
    fun `pressing enter at the end of the document leaves the caret on the new line`() = runTest {
        val engine = createEngine("Hello")

        engine.applyDelta(pos(0, 5), newText = "\n", caret = pos(1, 0))
            .shouldBeInstanceOf<EditorEngine.MutationResult.Applied>()

        engine.fullContent() shouldBe "Hello\n"
        engine.totalLines.value shouldBe 2L
        engine.cursorPosition.value.line shouldBe 1L
        engine.cursorPosition.value.column shouldBe 0
        // Both lines must be loaded, or the field and the engine diverge permanently
        engine.visibleContent.value.text shouldBe "Hello\n"
    }

    @Test
    fun `typing after an end-of-document newline still reaches the document`() = runTest {
        // The reported bug: after Enter the caret snapped back to line 0 and every later keystroke
        // resolved against a line the buffer did not have, so typing looked dead.
        val engine = createEngine("Hello")
        engine.applyDelta(pos(0, 5), newText = "\n", caret = pos(1, 0))
            .shouldBeInstanceOf<EditorEngine.MutationResult.Applied>()

        engine.applyDelta(pos(1, 0), newText = "X", caret = pos(1, 1))
            .shouldBeInstanceOf<EditorEngine.MutationResult.Applied>()

        engine.fullContent() shouldBe "Hello\nX"
    }

    @Test
    fun `replace with newline adds a line`() = runTest {
        val engine = createEngine("abc")

        engine.applyDelta(pos(0, 1), pos(0, 2), oldText = "b", newText = "X\nY", caret = pos(1, 1))

        engine.fullContent() shouldBe "aX\nYc"
        engine.totalLines.value shouldBe 2L
        engine.cursorPosition.value.line shouldBe 1L
        engine.cursorPosition.value.column shouldBe 1
    }

    @Test
    fun `cross-line delete joins lines`() = runTest {
        val engine = createEngine("abc\ndef")

        // Delete the range covering the newline: end of line 0 to start of line 1.
        engine.applyDelta(pos(0, 3), pos(1, 0), oldText = "\n", caret = pos(0, 3))

        engine.fullContent() shouldBe "abcdef"
        engine.totalLines.value shouldBe 1L
    }

    @Test
    fun `replace spanning the synthetic newline`() = runTest {
        val engine = createEngine("abc\ndef")

        // Replace "c\nd" (cols (0,2)..(1,1)) with "Z" -> "abZef".
        engine.applyDelta(pos(0, 2), pos(1, 1), oldText = "c\nd", newText = "Z", caret = pos(0, 3))

        engine.fullContent() shouldBe "abZef"
        engine.totalLines.value shouldBe 1L
    }

    @Test
    fun `replace tolerates reversed start and end`() = runTest {
        val engine = createEngine("Hello World")

        // start/end given in reverse order should still replace [6,11).
        engine.applyDelta(pos(0, 11), pos(0, 6), oldText = "World", newText = "Kotlin", caret = pos(0, 12))

        engine.fullContent() shouldBe "Hello Kotlin"
    }

    @Test
    fun `a field edit clears a pre-existing engine selection`() = runTest {
        val engine = createEngine("Hello World")
        engine.setSelection(start = TextPosition(0, 0, 0), end = TextPosition(5, 0, 5))
        engine.selectionRange.value shouldBe (TextPosition(0, 0, 0) to TextPosition(5, 0, 5))

        engine.applyDelta(pos(0, 6), pos(0, 11), oldText = "World", newText = "Kotlin", caret = pos(0, 12))

        engine.fullContent() shouldBe "Hello Kotlin"
        engine.selectionRange.value shouldBe null
    }

    @Test
    fun `undo and redo round-trip after equal-length replace`() = runTest {
        val engine = createEngine("teh quick")
        val original = engine.fullContent()

        // Autocorrect-style equal-length replace "eh" -> "he".
        engine.applyDelta(pos(0, 1), pos(0, 3), oldText = "eh", newText = "he", caret = pos(0, 3))
        val afterReplace = engine.fullContent()
        afterReplace shouldBe "the quick"

        // Undo all the way back (a genuine replace is delete+insert, so this may take more than one step).
        while (engine.canUndo()) engine.undo()
        engine.fullContent() shouldBe original

        // Redo all the way forward.
        while (engine.canRedo()) engine.redo()
        engine.fullContent() shouldBe afterReplace
    }

    @Test
    fun `splitting a line in a short doc loads the new line into visible content`() = runTest {
        // Regression: in a 1-line document the visible window is 0..0. Inserting a newline mid-line must
        // grow the loaded window to include the new line, otherwise the new line renders blank (its content
        // is never read into currentContent).
        val engine = createEngine("abcdef")
        engine.visibleContent.value.text shouldBe "abcdef"

        // Split after "abc": insert "\n" at (0,3), caret lands at start of the new line.
        engine.applyDelta(pos(0, 3), newText = "\n", caret = pos(1, 0))

        engine.fullContent() shouldBe "abc\ndef"
        engine.totalLines.value shouldBe 2L
        // Both lines must be present in the visible content that drives rendering.
        engine.visibleContent.value.text shouldBe "abc\ndef"
    }

    @Test
    fun `undo after pure insert restores in a single step`() = runTest {
        val engine = createEngine("abcd")

        engine.applyDelta(pos(0, 2), newText = "X", caret = pos(0, 3))
        engine.fullContent() shouldBe "abXcd"

        engine.canUndo() shouldBe true
        engine.undo()
        engine.fullContent() shouldBe "abcd"
        engine.canUndo() shouldBe false
    }
}
