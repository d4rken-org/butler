package eu.darken.butler.editor.core.engine

import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

/**
 * Engine-level coverage for oversized (non-undoable) delete/replace. The key regression is that a
 * type/paste over a huge selection routes through [DocumentBuffer.replaceText] as ONE unit, so the
 * whole edit is non-undoable together - never leaving a misleading "undo" that only reverts the
 * inserted text while the huge deletion stays gone.
 */
class EditorEngineOversizedDeleteTest : EditorEngineTestBase() {

    // Over the floored threshold (MIN_UNDOABLE_EDIT_CHARS) once the undo budget is set tiny.
    private val overThreshold = DocumentBuffer.MIN_UNDOABLE_EDIT_CHARS.toInt() + 1

    @Test
    fun `typing over a huge selection is non-undoable as one unit`() = runTest {
        val engine = createEngine("A".repeat(overThreshold), undoMaxMemoryBytes = 100)
        engine.selectAll()

        engine.insertText("x")

        engine.getFullContent() shouldBe "x"
        engine.selectionRange.value shouldBe null
        // Not a misleading partial undo: the whole replace was unrecorded
        engine.canUndo.first() shouldBe false
        engine.nonUndoableEditPending.first() shouldBe true
    }

    @Test
    fun `typing over a small selection stays undoable`() = runTest {
        val engine = createEngine("Hello World", undoMaxMemoryBytes = 100)
        engine.setSelection(
            start = TextPosition(offset = 6, line = 0, column = 6),
            end = TextPosition(offset = 11, line = 0, column = 11),
        )

        engine.insertText("Kotlin")

        engine.getFullContent() shouldBe "Hello Kotlin"
        engine.canUndo.first() shouldBe true
        engine.nonUndoableEditPending.first() shouldBe false
    }

    @Test
    fun `deleting a huge selection is non-undoable`() = runTest {
        val engine = createEngine("A".repeat(overThreshold), undoMaxMemoryBytes = 100)
        engine.selectAll()

        engine.deleteSelection()

        engine.getFullContent() shouldBe ""
        engine.canUndo.first() shouldBe false
        engine.nonUndoableEditPending.first() shouldBe true
    }

    private suspend fun EditorEngine.getFullContent(): String {
        val state = this.state.value as EditorState.Loaded
        return state.resources.textBuffer.getText(0, state.resources.textBuffer.totalLength.value).getOrThrow()
    }
}
