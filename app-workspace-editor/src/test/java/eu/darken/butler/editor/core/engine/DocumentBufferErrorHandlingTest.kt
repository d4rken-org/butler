package eu.darken.butler.editor.core.engine

import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

/**
 * Tests for DocumentBuffer error handling including invalid operations,
 * out-of-bounds access, and undo stack integrity after failed operations.
 */
class DocumentBufferErrorHandlingTest : DocumentBufferTestBase() {

    @Test
    fun `insertText with negative offset should fail gracefully`() = runTest {
        val buffer = createBuffer("Hello World")
        val originalContent = buffer.getTextForRange(0, 0).getOrThrow()

        val result = buffer.insertText(
            position = TextPosition(offset = -1L, line = 0, column = 0),
            text = "Invalid"
        )

        result.isFailure shouldBe true
        // State should remain unchanged
        buffer.getTextForRange(0, 0).getOrThrow() shouldBe originalContent
    }

    @Test
    fun `insertText beyond file size should fail gracefully`() = runTest {
        val buffer = createBuffer("Hello")
        val originalContent = buffer.getTextForRange(0, 0).getOrThrow()

        val result = buffer.insertText(
            position = TextPosition(offset = 1000L, line = 10, column = 10),
            text = "Invalid"
        )

        result.isFailure shouldBe true
        buffer.getTextForRange(0, 0).getOrThrow() shouldBe originalContent
    }

    @Test
    fun `deleteText with end before start should fail gracefully`() = runTest {
        val buffer = createBuffer("Hello World")
        val originalContent = buffer.getTextForRange(0, 0).getOrThrow()

        val result = buffer.deleteText(
            startPosition = TextPosition(offset = 10L, line = 0, column = 10),
            endPosition = TextPosition(offset = 5L, line = 0, column = 5)
        )

        result.isFailure shouldBe true
        buffer.getTextForRange(0, 0).getOrThrow() shouldBe originalContent
    }

    @Test
    fun `deleteText with negative offset should fail gracefully`() = runTest {
        val buffer = createBuffer("Hello World")
        val originalContent = buffer.getTextForRange(0, 0).getOrThrow()

        val result = buffer.deleteText(
            startPosition = TextPosition(offset = -1L, line = 0, column = 0),
            endPosition = TextPosition(offset = 5L, line = 0, column = 5)
        )

        result.isFailure shouldBe true
        buffer.getTextForRange(0, 0).getOrThrow() shouldBe originalContent
    }

    @Test
    fun `deleteText beyond file size should fail gracefully`() = runTest {
        val buffer = createBuffer("Hello")
        val originalContent = buffer.getTextForRange(0, 0).getOrThrow()

        val result = buffer.deleteText(
            startPosition = TextPosition(offset = 0L, line = 0, column = 0),
            endPosition = TextPosition(offset = 1000L, line = 10, column = 10)
        )

        result.isFailure shouldBe true
        buffer.getTextForRange(0, 0).getOrThrow() shouldBe originalContent
    }

    @Test
    fun `getTextForRange with negative line should fail gracefully`() = runTest {
        val buffer = createBuffer("Line 1\nLine 2\nLine 3")

        val result = buffer.getTextForRange(startLine = -1, endLine = 1)

        result.isFailure shouldBe true
    }

    @Test
    fun `getTextForRange with end before start should fail gracefully`() = runTest {
        val buffer = createBuffer("Line 1\nLine 2\nLine 3")

        val result = buffer.getTextForRange(startLine = 2, endLine = 0)

        result.isFailure shouldBe true
    }

    @Test
    fun `failed operations should not affect undo stack`() = runTest {
        val buffer = createBuffer("Hello")

        // Make a valid change
        buffer.insertText(TextPosition(5, 0, 5), " World")
        buffer.getTextForRange(0, 0).getOrThrow() shouldBe "Hello World"

        // Attempt invalid operation
        val failedResult = buffer.insertText(
            position = TextPosition(offset = -1L, line = 0, column = 0),
            text = "Invalid"
        )
        failedResult.isFailure shouldBe true

        // Undo should only undo the valid operation
        val undoResult = buffer.undo()
        undoResult.isSuccess shouldBe true
        buffer.getTextForRange(0, 0).getOrThrow() shouldBe "Hello"

        // Second undo should return null (stack empty)
        val secondUndo = buffer.undo()
        secondUndo.isSuccess shouldBe true
        secondUndo.getOrNull() shouldBe null
    }

    @Test
    fun `undo on empty stack should return success with null operation`() = runTest {
        val buffer = createBuffer("Hello")
        val originalContent = buffer.getTextForRange(0, 0).getOrThrow()

        val result = buffer.undo()

        result.isSuccess shouldBe true
        result.getOrNull() shouldBe null
        buffer.getTextForRange(0, 0).getOrThrow() shouldBe originalContent
    }

    @Test
    fun `redo on empty stack should return success with null operation`() = runTest {
        val buffer = createBuffer("Hello")
        val originalContent = buffer.getTextForRange(0, 0).getOrThrow()

        val result = buffer.redo()

        result.isSuccess shouldBe true
        result.getOrNull() shouldBe null
        buffer.getTextForRange(0, 0).getOrThrow() shouldBe originalContent
    }
}
