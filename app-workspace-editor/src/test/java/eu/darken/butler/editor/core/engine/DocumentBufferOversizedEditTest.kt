package eu.darken.butler.editor.core.engine

import eu.darken.butler.editor.core.engine.text.BlockIndexBuilder
import eu.darken.butler.editor.core.sources.InMemoryDataSource
import eu.darken.butler.workspace.core.Workspace
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

/**
 * A delete/replace whose removed span exceeds [DocumentBuffer.maxUndoableEditChars] is applied
 * WITHOUT materializing the removed text (which would OOM on a giant single line) and WITHOUT an
 * undo entry - history is cleared, [DocumentBuffer.nonUndoableEditPending] is raised, and the doc
 * is left modified. These tests pin that behavior and the threshold derivation.
 */
class DocumentBufferOversizedEditTest {

    private val workspaceId = Workspace.Id()

    /** [maxUndoMemoryBytes] = 100 floors the threshold to [DocumentBuffer.MIN_UNDOABLE_EDIT_CHARS]. */
    private suspend fun createBuffer(
        content: String,
        maxUndoMemoryBytes: Long = 100,
    ): DocumentBuffer {
        val dataSource = InMemoryDataSource(workspaceId, content)
        dataSource.open()
        val buffer = DocumentBuffer(
            workspaceId = workspaceId,
            dataSource = dataSource,
            maxUndoStackSize = 100,
            maxUndoMemoryBytes = maxUndoMemoryBytes,
            blockSize = BlockIndexBuilder.DEFAULT_BLOCK_SIZE,
            assertions = true,
        )
        buffer.initialize().getOrThrow()
        return buffer
    }

    private suspend fun DocumentBuffer.pos(offset: Long) = findPosition(offset)

    // Just over the floored threshold of MIN_UNDOABLE_EDIT_CHARS (1,000,000).
    private val overThreshold = DocumentBuffer.MIN_UNDOABLE_EDIT_CHARS.toInt() + 1

    @Test
    fun `threshold is half the undo budget, coerced into range`() = runTest {
        createBuffer("hi", maxUndoMemoryBytes = 10_485_760).maxUndoableEditChars shouldBe 5_242_880L
        // Below the floor -> clamped up to MIN
        createBuffer("hi", maxUndoMemoryBytes = 100).maxUndoableEditChars shouldBe
            DocumentBuffer.MIN_UNDOABLE_EDIT_CHARS
        // Above the ceiling -> clamped down to MAX
        createBuffer("hi", maxUndoMemoryBytes = 200_000_000).maxUndoableEditChars shouldBe
            DocumentBuffer.MAX_UNDOABLE_EDIT_CHARS
    }

    @Test
    fun `delete below threshold stays recorded and undoable`() = runTest {
        val buffer = createBuffer("A".repeat(1000))

        val deleted = buffer.deleteText(buffer.pos(0), buffer.pos(500)).getOrThrow()

        deleted shouldBe "A".repeat(500) // materialized normally
        buffer.canUndo.value shouldBe true
        buffer.nonUndoableEditPending.value shouldBe false

        buffer.undo().getOrThrow() shouldNotBe null
        buffer.getText(0, buffer.totalLength.value).getOrThrow() shouldBe "A".repeat(1000)
    }

    @Test
    fun `delete above threshold is non-undoable and clears history`() = runTest {
        val buffer = createBuffer("A".repeat(overThreshold))

        val result = buffer.deleteText(buffer.pos(0), buffer.pos(overThreshold.toLong()))

        result.getOrThrow() shouldBe "" // NOT materialized
        buffer.totalLength.value shouldBe 0L // content actually deleted
        buffer.canUndo.value shouldBe false
        buffer.canRedo.value shouldBe false
        buffer.isModified.value shouldBe true
        buffer.nonUndoableEditPending.value shouldBe true
        buffer.undo().getOrThrow() shouldBe null // nothing to undo
    }

    @Test
    fun `oversized delete wipes prior undo history`() = runTest {
        val buffer = createBuffer("A".repeat(overThreshold))
        // A normal recorded edit first
        buffer.insertText(buffer.pos(0), "hi").getOrThrow()
        buffer.canUndo.value shouldBe true

        // An oversized delete of the whole (now slightly longer) document
        buffer.deleteText(buffer.pos(0), buffer.pos(buffer.totalLength.value))

        buffer.canUndo.value shouldBe false
        buffer.undo().getOrThrow() shouldBe null // the earlier insert is gone too

        // A following recorded edit clears the pending flag and restores undoability
        buffer.insertText(buffer.pos(0), "x").getOrThrow()
        buffer.nonUndoableEditPending.value shouldBe false
        buffer.canUndo.value shouldBe true
    }

    @Test
    fun `oversized delete clears the redo branch`() = runTest {
        val buffer = createBuffer("A".repeat(overThreshold))
        buffer.insertText(buffer.pos(0), "hi").getOrThrow()
        buffer.undo().getOrThrow() // insert now on the redo stack
        buffer.canRedo.value shouldBe true

        buffer.deleteText(buffer.pos(0), buffer.pos(buffer.totalLength.value))

        buffer.canRedo.value shouldBe false
        buffer.redo().getOrThrow() shouldBe null // the old branch can't be resurrected
    }

    @Test
    fun `replace above threshold inserts new text and is non-undoable`() = runTest {
        val buffer = createBuffer("A".repeat(overThreshold))

        buffer.replaceText(buffer.pos(0), buffer.pos(overThreshold.toLong()), "small").getOrThrow()

        buffer.getText(0, buffer.totalLength.value).getOrThrow() shouldBe "small"
        buffer.canUndo.value shouldBe false
        buffer.isModified.value shouldBe true
        buffer.nonUndoableEditPending.value shouldBe true
    }

    @Test
    fun `oversized replace with out-of-range end fails without mutating`() = runTest {
        val buffer = createBuffer("A".repeat(overThreshold))

        // end offset past document length, span still over the threshold
        val result = buffer.replaceText(
            TextPosition(0, 0, 0),
            TextPosition(overThreshold.toLong() * 2, 0, 0),
            "x",
        )

        result.isFailure shouldBe true
        buffer.totalLength.value shouldBe overThreshold.toLong() // untouched
        buffer.isModified.value shouldBe false
        buffer.canUndo.value shouldBe false
    }
}
