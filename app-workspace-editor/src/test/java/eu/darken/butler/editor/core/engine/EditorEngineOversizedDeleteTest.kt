package eu.darken.butler.editor.core.engine

import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

/**
 * Engine-level coverage for oversized (non-undoable) delete/replace. Such an edit is gated: the
 * engine resolves it into a [EditorEngine.PreparedMutation] and mutates NOTHING until the user
 * confirms it. Once submitted it applies as ONE unit, so there is never a misleading "undo" that
 * only reverts the inserted text while the huge deletion stays gone.
 */
class EditorEngineOversizedDeleteTest : EditorEngineTestBase() {

    // Over the floored threshold (MIN_UNDOABLE_EDIT_CHARS) once the undo budget is set tiny.
    private val overThreshold = DocumentBuffer.MIN_UNDOABLE_EDIT_CHARS.toInt() + 1

    @Test
    fun `typing over a huge selection is gated and applies non-undoably as one unit`() = runTest {
        val engine = createEngine("A".repeat(overThreshold), undoMaxMemoryBytes = 100)
        engine.selectAll()

        val gate = engine.performInsert("x").shouldBeInstanceOf<EditorEngine.EditOutcome.RequiresConfirmation>()
        // Nothing has happened yet - the dialog is up and the document is untouched
        engine.getFullContent() shouldBe "A".repeat(overThreshold)

        engine.submitPrepared(gate.prepared).shouldBeInstanceOf<EditorEngine.MutationResult.Applied>()

        engine.getFullContent() shouldBe "x"
        engine.selectionRange.value shouldBe null
        // Not a misleading partial undo: the whole replace was unrecorded
        engine.canUndo.first() shouldBe false
        engine.nonUndoableEditPending.first() shouldBe true
    }

    @Test
    fun `typing over a small selection stays undoable and is never gated`() = runTest {
        val engine = createEngine("Hello World", undoMaxMemoryBytes = 100)
        engine.setSelection(
            start = TextPosition(offset = 6, line = 0, column = 6),
            end = TextPosition(offset = 11, line = 0, column = 11),
        )

        engine.performInsert("Kotlin").shouldBeInstanceOf<EditorEngine.EditOutcome.Applied>()

        engine.getFullContent() shouldBe "Hello Kotlin"
        engine.canUndo.first() shouldBe true
        engine.nonUndoableEditPending.first() shouldBe false
    }

    @Test
    fun `deleting a huge selection is gated and applies non-undoably`() = runTest {
        val engine = createEngine("A".repeat(overThreshold), undoMaxMemoryBytes = 100)
        engine.selectAll()

        val gate = engine.performDeleteSelection().shouldBeInstanceOf<EditorEngine.EditOutcome.RequiresConfirmation>()
        engine.getFullContent() shouldBe "A".repeat(overThreshold)

        engine.submitPrepared(gate.prepared).shouldBeInstanceOf<EditorEngine.MutationResult.Applied>()

        engine.getFullContent() shouldBe ""
        engine.canUndo.first() shouldBe false
        engine.nonUndoableEditPending.first() shouldBe true
    }

    @Test
    fun `a confirmed edit whose document moved on mutates nothing`() = runTest {
        val engine = createEngine("A".repeat(overThreshold), undoMaxMemoryBytes = 100)
        engine.selectAll()
        val gate = engine.performDeleteSelection().shouldBeInstanceOf<EditorEngine.EditOutcome.RequiresConfirmation>()

        // Something else edited the document while the confirmation dialog was up
        engine.setCursorPosition(TextPosition(offset = 0, line = 0, column = 0))
        engine.performInsert("Z").shouldBeInstanceOf<EditorEngine.EditOutcome.Applied>()

        engine.submitPrepared(gate.prepared).shouldBeInstanceOf<EditorEngine.MutationResult.Conflict>()

        engine.getFullContent() shouldBe "Z" + "A".repeat(overThreshold)
    }

    private suspend fun EditorEngine.getFullContent(): String = fullContent()
}
