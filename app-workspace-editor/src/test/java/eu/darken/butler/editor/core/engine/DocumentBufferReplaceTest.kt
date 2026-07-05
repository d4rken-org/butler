package eu.darken.butler.editor.core.engine

import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.Test

/**
 * Tests for DocumentBuffer replace operations, especially multi-chunk replace scenarios.
 * Replace operations are composite (delete + insert) and need thorough testing to ensure
 * boundary corrections from delete phase don't interfere with insert phase.
 */
class DocumentBufferReplaceTest : DocumentBufferTestBase() {

    @Test
    fun `replaceText within single chunk works correctly`() = runTest {
        // Given: Buffer with content in single chunk
        val content = "Hello World"
        val buffer = createBuffer(content)

        // When: Replace "World" with "Universe"
        val result = buffer.replaceText(
            startPosition = TextPosition(6L, 0, 6),
            endPosition = TextPosition(11L, 0, 11),
            newText = "Universe"
        )

        // Then: Replace succeeded
        result.isSuccess shouldBe true

        // And: Content updated correctly
        val newContent = buffer.getTextForRange(0, 0).getOrThrow()
        newContent shouldBe "Hello Universe"
    }

    @Test
    fun `replaceText spanning 2 chunks maintains correct boundaries`() = runTest {
        // Given: Content spanning 2 chunks (100 bytes each)
        val chunk1 = "A".repeat(100)
        val chunk2 = "B".repeat(100)
        val content = chunk1 + chunk2
        val buffer = createBuffer(content, blockSize = 100)

        // When: Replace text spanning both chunks (bytes 50-150)
        // This should: delete from middle of chunk_0 through middle of chunk_1,
        // merge them, then insert new text
        val result = buffer.replaceText(
            startPosition = TextPosition(50L, 0, 50),
            endPosition = TextPosition(150L, 0, 150),
            newText = "REPLACED"
        )

        // Then: Replace succeeded
        result.isSuccess shouldBe true

        // And: Content is correct (first 50 A's + "REPLACED" + last 50 B's)
        val newContent = buffer.getTextForRange(0, 0).getOrThrow()
        newContent shouldBe "A".repeat(50) + "REPLACED" + "B".repeat(50)
        newContent.length shouldBe 108

        // And: Can still perform operations (boundaries are valid)
        val position = buffer.findPosition(50L)
        position.offset shouldBe 50L
    }

    @Test
    fun `replaceText spanning 3 chunks removes middle chunk entirely`() = runTest {
        // Given: Content spanning 3 chunks
        val chunk1 = "A".repeat(100)
        val chunk2 = "B".repeat(100)
        val chunk3 = "C".repeat(100)
        val content = chunk1 + chunk2 + chunk3
        val buffer = createBuffer(content, blockSize = 100)

        // When: Replace text spanning all 3 chunks (bytes 50-250)
        // This should: delete middle chunk entirely, merge first and last chunks
        val result = buffer.replaceText(
            startPosition = TextPosition(50L, 0, 50),
            endPosition = TextPosition(250L, 0, 250),
            newText = "REPLACED"
        )

        // Then: Replace succeeded
        result.isSuccess shouldBe true

        // And: Content is correct
        val newContent = buffer.getTextForRange(0, 0).getOrThrow()
        newContent shouldBe "A".repeat(50) + "REPLACED" + "C".repeat(50)
        newContent.length shouldBe 108

        // And: Boundaries are correct (can read from different offsets)
        buffer.getText(0L, 50L).getOrThrow() shouldBe "A".repeat(50)
        buffer.getText(50L, 58L).getOrThrow() shouldBe "REPLACED"
        buffer.getText(58L, 108L).getOrThrow() shouldBe "C".repeat(50)
    }

    @Test
    fun `replaceText at exact chunk boundary works correctly`() = runTest {
        // Given: Content with exact chunk boundaries
        val chunk1 = "A".repeat(100)
        val chunk2 = "B".repeat(100)
        val content = chunk1 + chunk2
        val buffer = createBuffer(content, blockSize = 100)

        // When: Replace starting exactly at chunk boundary (offset 100)
        val result = buffer.replaceText(
            startPosition = TextPosition(100L, 0, 100),
            endPosition = TextPosition(150L, 0, 150),
            newText = "BOUNDARY"
        )

        // Then: Replace succeeded
        result.isSuccess shouldBe true

        // And: Content is correct
        val newContent = buffer.getTextForRange(0, 0).getOrThrow()
        newContent shouldBe "A".repeat(100) + "BOUNDARY" + "B".repeat(50)
        newContent.length shouldBe 158
    }

    @Test
    fun `replaceText with newlines across chunks updates line count correctly`() = runTest {
        // Given: Single-line content spanning 2 chunks (simpler for position calculations)
        val chunk1 = "A".repeat(100)
        val chunk2 = "B".repeat(100)
        val content = chunk1 + chunk2
        val buffer = createBuffer(content, blockSize = 100)

        val initialLineCount = buffer.totalLines.value
        initialLineCount shouldBe 1  // Single line initially

        // When: Replace text across chunks with content containing newlines
        val result = buffer.replaceText(
            startPosition = TextPosition(50L, 0, 50),
            endPosition = TextPosition(150L, 0, 150),
            newText = "NewLine1\nNewLine2\nNewLine3"
        )

        // Then: Replace succeeded
        result.isSuccess shouldBe true

        // And: Line count increased due to newlines
        buffer.totalLines.value shouldBe 3L

        // And: Content contains all lines (use totalLines-1 as endLine to get all lines)
        val newContent = buffer.getTextForRange(0, buffer.totalLines.value - 1).getOrThrow()
        newContent shouldContain "NewLine1"
        newContent shouldContain "NewLine2"
        newContent shouldContain "NewLine3"

        // And: Boundaries work correctly
        val position = buffer.findPosition(50L)
        position.offset shouldBe 50L
    }

    @Test
    fun `undo multi-chunk replace restores all original content`() = runTest {
        // Given: Content spanning 2 chunks
        val chunk1 = "A".repeat(100)
        val chunk2 = "B".repeat(100)
        val content = chunk1 + chunk2
        val buffer = createBuffer(content, blockSize = 100)

        // When: Replace across chunks
        // Note: Replace = delete + insert, creating TWO undo operations
        buffer.replaceText(
            startPosition = TextPosition(50L, 0, 50),
            endPosition = TextPosition(150L, 0, 150),
            newText = "REPLACED"
        )

        val afterReplace = buffer.getTextForRange(0, 0).getOrThrow()
        afterReplace shouldBe "A".repeat(50) + "REPLACED" + "B".repeat(50)

        // Then: First undo removes the insert (restores to post-delete state)
        val undoResult1 = buffer.undo()
        undoResult1.isSuccess shouldBe true

        val afterFirstUndo = buffer.getTextForRange(0, 0).getOrThrow()
        afterFirstUndo shouldBe "A".repeat(50) + "B".repeat(50)
        afterFirstUndo.length shouldBe 100

        // And: Second undo removes the delete (fully restores original)
        val undoResult2 = buffer.undo()
        undoResult2.isSuccess shouldBe true

        val fullyRestored = buffer.getTextForRange(0, 0).getOrThrow()
        fullyRestored shouldBe content
        fullyRestored.length shouldBe 200
    }

    @Test
    fun `replaceText entire file content works correctly`() = runTest {
        // Given: Buffer with multi-chunk content
        val content = "A".repeat(100) + "B".repeat(100)
        val buffer = createBuffer(content, blockSize = 100)

        // When: Replace entire file
        val result = buffer.replaceText(
            startPosition = TextPosition(0L, 0, 0),
            endPosition = TextPosition(200L, 0, 200),
            newText = "NEW CONTENT"
        )

        // Then: Replace succeeded
        result.isSuccess shouldBe true

        // And: Content is completely replaced
        val newContent = buffer.getTextForRange(0, 0).getOrThrow()
        newContent shouldBe "NEW CONTENT"
        newContent.length shouldBe 11
    }

    @Test
    fun `replaceText empty string acts as delete`() = runTest {
        // Given: Content spanning 2 chunks
        val content = "A".repeat(100) + "B".repeat(100)
        val buffer = createBuffer(content, blockSize = 100)

        // When: Replace with empty string (essentially a delete)
        val result = buffer.replaceText(
            startPosition = TextPosition(50L, 0, 50),
            endPosition = TextPosition(150L, 0, 150),
            newText = ""
        )

        // Then: Replace succeeded
        result.isSuccess shouldBe true

        // And: Content is deleted
        val newContent = buffer.getTextForRange(0, 0).getOrThrow()
        newContent shouldBe "A".repeat(50) + "B".repeat(50)
        newContent.length shouldBe 100
    }

    @Test
    fun `concurrent reads never observe the intermediate delete-only state`() = runTest {
        val before = "AAAA" + "B".repeat(200) + "CCCC"
        val after = "AAAA" + "XXX" + "CCCC"
        val buffer = createBuffer(before, blockSize = 50)

        withContext(Dispatchers.Default) {
            val replaceJob = launch {
                buffer.replaceText(
                    startPosition = TextPosition(4L, 0, 4),
                    endPosition = TextPosition(204L, 0, 204),
                    newText = "XXX",
                ).getOrThrow()
            }
            repeat(50) {
                val snapshot = buffer.getFullText().getOrThrow()
                (snapshot == before || snapshot == after) shouldBe true
            }
            replaceJob.join()
        }
        buffer.getFullText().getOrThrow() shouldBe after
    }

    @Test
    fun `replaceText with large text spanning multiple chunks works correctly`() = runTest {
        // Given: Small content
        val content = "OLD"
        val buffer = createBuffer(content, blockSize = 100)

        // When: Replace with large text (would span multiple chunks)
        val largeText = "X".repeat(250)  // 2.5 chunks worth
        val result = buffer.replaceText(
            startPosition = TextPosition(0L, 0, 0),
            endPosition = TextPosition(3L, 0, 3),
            newText = largeText
        )

        // Then: Replace succeeded
        result.isSuccess shouldBe true

        // And: Content is correct
        val newContent = buffer.getTextForRange(0, 0).getOrThrow()
        newContent shouldBe largeText
        newContent.length shouldBe 250
    }
}
