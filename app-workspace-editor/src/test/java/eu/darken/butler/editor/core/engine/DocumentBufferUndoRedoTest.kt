package eu.darken.butler.editor.core.engine

import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

/**
 * Tests for DocumentBuffer undo/redo functionality including operation reversal,
 * redo stack management, and multi-chunk operation undo.
 */
class DocumentBufferUndoRedoTest : DocumentBufferTestBase() {

    @Test
    fun `canUndo returns false on empty buffer`() = runTest {
        // Given: New buffer with no edits
        val buffer = createBuffer("Initial content")

        // When/Then: Cannot undo before any edits
        val result = buffer.undo()
        result.isSuccess shouldBe true
        result.getOrThrow() shouldBe null  // No operation to undo
    }

    @Test
    fun `canRedo returns false before undo`() = runTest {
        // Given: Buffer with an edit
        val buffer = createBuffer("Hello")
        buffer.insertText(TextPosition(5, 0, 5), " World")

        // When/Then: Cannot redo before undo
        val result = buffer.redo()
        result.isSuccess shouldBe true
        result.getOrThrow() shouldBe null  // No operation to redo
    }

    @Test
    fun `canUndo and canRedo flows track edit undo and redo`() = runTest {
        val buffer = createBuffer("Hello")
        buffer.canUndo.value shouldBe false
        buffer.canRedo.value shouldBe false

        buffer.insertText(TextPosition(5, 0, 5), "!").getOrThrow()
        buffer.canUndo.value shouldBe true
        buffer.canRedo.value shouldBe false

        buffer.undo().getOrThrow()
        buffer.canUndo.value shouldBe false
        buffer.canRedo.value shouldBe true

        buffer.redo().getOrThrow()
        buffer.canUndo.value shouldBe true
        buffer.canRedo.value shouldBe false

        // A new edit clears the redo stack
        buffer.undo().getOrThrow()
        buffer.insertText(TextPosition(5, 0, 5), "?").getOrThrow()
        buffer.canUndo.value shouldBe true
        buffer.canRedo.value shouldBe false

        buffer.release().getOrThrow()
        buffer.canUndo.value shouldBe false
        buffer.canRedo.value shouldBe false
    }

    @Test
    fun `undo insert operation restores original text`() = runTest {
        // Given: Buffer with original content
        val buffer = createBuffer("Hello")
        val originalContent = buffer.getTextForRange(0, 0).getOrThrow()

        // When: Insert text then undo
        buffer.insertText(TextPosition(5, 0, 5), " World")
        val afterInsert = buffer.getTextForRange(0, 0).getOrThrow()
        afterInsert shouldBe "Hello World"

        val undoResult = buffer.undo()
        undoResult.isSuccess shouldBe true

        // Then: Content restored to original
        val afterUndo = buffer.getTextForRange(0, 0).getOrThrow()
        afterUndo shouldBe originalContent
    }

    @Test
    fun `undo delete operation restores deleted text`() = runTest {
        // Given: Buffer with content
        val buffer = createBuffer("Hello World")
        val originalContent = buffer.getTextForRange(0, 0).getOrThrow()

        // When: Delete text then undo
        buffer.deleteText(
            startPosition = TextPosition(5, 0, 5),
            endPosition = TextPosition(11, 0, 11)
        )
        val afterDelete = buffer.getTextForRange(0, 0).getOrThrow()
        afterDelete shouldBe "Hello"

        val undoResult = buffer.undo()
        undoResult.isSuccess shouldBe true

        // Then: Deleted text restored
        val afterUndo = buffer.getTextForRange(0, 0).getOrThrow()
        afterUndo shouldBe originalContent
    }

    @Test
    fun `redo after undo reapplies operation`() = runTest {
        // Given: Buffer with edit and undo
        val buffer = createBuffer("Hello")
        buffer.insertText(TextPosition(5, 0, 5), " World")
        val contentAfterInsert = buffer.getTextForRange(0, 0).getOrThrow()

        buffer.undo()
        val contentAfterUndo = buffer.getTextForRange(0, 0).getOrThrow()
        contentAfterUndo shouldBe "Hello"

        // When: Redo
        val redoResult = buffer.redo()
        redoResult.isSuccess shouldBe true

        // Then: Edit reapplied
        val afterRedo = buffer.getTextForRange(0, 0).getOrThrow()
        afterRedo shouldBe contentAfterInsert
    }

    @Test
    fun `multiple undo operations work in reverse order`() = runTest {
        // Given: Buffer with multiple edits
        val buffer = createBuffer("A")
        buffer.insertText(TextPosition(1, 0, 1), "B")  // "AB"
        buffer.insertText(TextPosition(2, 0, 2), "C")  // "ABC"
        buffer.insertText(TextPosition(3, 0, 3), "D")  // "ABCD"

        buffer.getTextForRange(0, 0).getOrThrow() shouldBe "ABCD"

        // When: Undo three times
        buffer.undo()  // Remove D
        buffer.getTextForRange(0, 0).getOrThrow() shouldBe "ABC"

        buffer.undo()  // Remove C
        buffer.getTextForRange(0, 0).getOrThrow() shouldBe "AB"

        buffer.undo()  // Remove B
        val final = buffer.getTextForRange(0, 0).getOrThrow()

        // Then: Back to original
        final shouldBe "A"
    }

    @Test
    fun `redo stack clears after new edit`() = runTest {
        // Given: Buffer with undo history
        val buffer = createBuffer("Hello")
        buffer.insertText(TextPosition(5, 0, 5), " World")
        buffer.undo()

        // When: Make new edit
        buffer.insertText(TextPosition(5, 0, 5), "!")
        buffer.getTextForRange(0, 0).getOrThrow() shouldBe "Hello!"

        // Then: Cannot redo previous operation
        val redoResult = buffer.redo()
        redoResult.isSuccess shouldBe true
        redoResult.getOrThrow() shouldBe null

        // And: Content remains with new edit
        buffer.getTextForRange(0, 0).getOrThrow() shouldBe "Hello!"
    }

    @Test
    fun `undo-redo-undo cycle maintains consistency`() = runTest {
        // Given: Buffer with edit
        val buffer = createBuffer("Test")
        buffer.insertText(TextPosition(4, 0, 4), "ing")

        buffer.getTextForRange(0, 0).getOrThrow() shouldBe "Testing"

        // When: Undo
        buffer.undo()
        buffer.getTextForRange(0, 0).getOrThrow() shouldBe "Test"

        // Then: Redo
        buffer.redo()
        buffer.getTextForRange(0, 0).getOrThrow() shouldBe "Testing"

        // And: Undo again
        buffer.undo()
        val final = buffer.getTextForRange(0, 0).getOrThrow()
        final shouldBe "Test"
    }

    @Test
    fun `undo multi-chunk delete restores all content`() = runTest {
        // Given: Content spanning multiple chunks
        val chunk1 = "A".repeat(100)
        val chunk2 = "B".repeat(100)
        val content = chunk1 + chunk2
        val buffer = createBuffer(content, blockSize = 100)

        // When: Delete across chunks then undo
        buffer.deleteText(
            startPosition = TextPosition(50, 0, 50),
            endPosition = TextPosition(150, 0, 150)
        )
        buffer.getTextForRange(0, 0).getOrThrow().length shouldBe 100

        val undoResult = buffer.undo()
        undoResult.isSuccess shouldBe true

        // Then: All content restored
        val restored = buffer.getTextForRange(0, 0).getOrThrow()
        restored shouldBe content
        restored.length shouldBe 200
    }

    @Test
    fun `undo and redo preserve line counts correctly`() = runTest {
        // Given: Multi-line buffer
        val buffer = createBuffer("Line 1\nLine 2\nLine 3")
        buffer.totalLines.value shouldBe 3L

        // When: Insert newlines then undo
        buffer.insertText(TextPosition(6, 0, 6), "\nNew Line\n")
        buffer.totalLines.value shouldBe 5L

        buffer.undo()

        // Then: Line count restored
        buffer.totalLines.value shouldBe 3L

        // And: Redo restores added lines
        buffer.redo()
        buffer.totalLines.value shouldBe 5L
    }
}
