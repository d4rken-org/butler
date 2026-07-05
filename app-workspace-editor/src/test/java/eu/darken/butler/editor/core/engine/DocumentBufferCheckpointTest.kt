package eu.darken.butler.editor.core.engine

import eu.darken.butler.editor.core.engine.text.BlockIndexBuilder
import eu.darken.butler.editor.core.sources.InMemoryDataSource
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

/**
 * Save-checkpoint semantics: isModified compares edit generations against the generation
 * recorded at save time, so undoing back to the saved state clears the flag - unless the
 * saved state became unreachable (evicted undo entry or discarded redo region).
 */
class DocumentBufferCheckpointTest : DocumentBufferTestBase() {

    private suspend fun limitedBuffer(content: String, maxUndoStackSize: Int): DocumentBuffer {
        val dataSource = InMemoryDataSource(workspaceId, content)
        dataSource.open()
        val buffer = DocumentBuffer(
            workspaceId = workspaceId,
            dataSource = dataSource,
            maxUndoStackSize = maxUndoStackSize,
            maxUndoMemoryBytes = 10_485_760,
            blockSize = BlockIndexBuilder.DEFAULT_BLOCK_SIZE,
            assertions = true,
        )
        buffer.initialize().getOrThrow()
        return buffer
    }

    @Test
    fun `undo back to save point clears isModified`() = runTest {
        val buffer = createBuffer("base")
        buffer.insertText(TextPosition(0, 0, 0), "1").getOrThrow()
        buffer.saveFile().isSuccess shouldBe true
        buffer.isModified.value shouldBe false

        buffer.insertText(TextPosition(0, 0, 0), "2").getOrThrow()
        buffer.isModified.value shouldBe true

        buffer.undo().getOrThrow()
        buffer.isModified.value shouldBe false
        buffer.getFullText().getOrThrow() shouldBe "1base"

        buffer.redo().getOrThrow()
        buffer.isModified.value shouldBe true
        buffer.getFullText().getOrThrow() shouldBe "21base"
    }

    @Test
    fun `undo past save point marks modified and saving there works`() = runTest {
        val buffer = createBuffer("base")
        buffer.insertText(TextPosition(0, 0, 0), "1").getOrThrow()
        buffer.insertText(TextPosition(0, 0, 0), "2").getOrThrow()
        buffer.saveFile().isSuccess shouldBe true
        buffer.isModified.value shouldBe false

        buffer.undo().getOrThrow()
        buffer.isModified.value shouldBe true
        buffer.undo().getOrThrow()
        buffer.isModified.value shouldBe true
        buffer.getFullText().getOrThrow() shouldBe "base"

        buffer.saveFile().isSuccess shouldBe true
        buffer.isModified.value shouldBe false

        buffer.redo().getOrThrow()
        buffer.isModified.value shouldBe true
        buffer.getFullText().getOrThrow() shouldBe "1base"
    }

    @Test
    fun `evicted save point stays modified after undoing everything`() = runTest {
        val buffer = limitedBuffer("base", maxUndoStackSize = 2)
        buffer.insertText(TextPosition(0, 0, 0), "1").getOrThrow()
        buffer.saveFile().isSuccess shouldBe true

        buffer.insertText(TextPosition(0, 0, 0), "2").getOrThrow()
        buffer.insertText(TextPosition(0, 0, 0), "3").getOrThrow()
        // This eviction drops the entry whose pre-state is the saved generation
        buffer.insertText(TextPosition(0, 0, 0), "4").getOrThrow()

        buffer.undo().getOrThrow()
        buffer.isModified.value shouldBe true
        buffer.undo().getOrThrow()
        buffer.isModified.value shouldBe true
        buffer.canUndo() shouldBe false
    }

    @Test
    fun `no-op edits do not mark the buffer modified`() = runTest {
        val buffer = createBuffer("abc")
        buffer.insertText(TextPosition(1, 0, 1), "").getOrThrow()
        buffer.deleteText(TextPosition(2, 0, 2), TextPosition(2, 0, 2)).getOrThrow()
        buffer.isModified.value shouldBe false
        buffer.canUndo() shouldBe false
        buffer.getFullText().getOrThrow() shouldBe "abc"
    }

    @Test
    fun `discarded redo region keeps modified even at matching content`() = runTest {
        val buffer = createBuffer("base")
        buffer.insertText(TextPosition(0, 0, 0), "1").getOrThrow()
        buffer.insertText(TextPosition(0, 0, 0), "2").getOrThrow()
        buffer.saveFile().isSuccess shouldBe true

        buffer.undo().getOrThrow()
        // New edit discards the redo region containing the saved state
        buffer.insertText(TextPosition(0, 0, 0), "3").getOrThrow()
        buffer.isModified.value shouldBe true

        buffer.undo().getOrThrow()
        buffer.isModified.value shouldBe true
        buffer.undo().getOrThrow()
        buffer.isModified.value shouldBe true
    }
}
