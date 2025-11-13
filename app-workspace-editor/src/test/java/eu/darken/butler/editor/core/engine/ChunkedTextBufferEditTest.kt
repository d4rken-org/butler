package eu.darken.butler.editor.core.engine

import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

/**
 * Tests for ChunkedTextBuffer edit operations including insert, delete,
 * multi-chunk edits, and save functionality.
 */
class ChunkedTextBufferEditTest : ChunkedTextBufferTestBase() {

    // ==================== Basic Insert Operations ====================

    @Test
    fun `insertText at start of file`() = runTest {
        // Given: Buffer with content
        val content = "Hello World"
        val buffer = createBuffer(content)

        // When: Insert at start
        val position = TextPosition(offset = 0L, line = 0, column = 0)
        val result = buffer.insertText(position, "START ")

        // Then: Insert succeeded
        result.isSuccess shouldBe true

        // And: Content updated
        val newContent = buffer.getTextForRange(0, 0).getOrThrow()
        newContent shouldBe "START Hello World"

        // And: Modified flag set
        buffer.isModified.value shouldBe true
    }

    @Test
    fun `insertText at end of file`() = runTest {
        // Given: Buffer with content
        val content = "Hello World"
        val buffer = createBuffer(content)

        // When: Insert at end
        val position = TextPosition(offset = 11L, line = 0, column = 11)
        val result = buffer.insertText(position, " END")

        // Then: Insert succeeded
        result.isSuccess shouldBe true

        // And: Content updated
        val newContent = buffer.getTextForRange(0, 0).getOrThrow()
        newContent shouldBe "Hello World END"
    }

    @Test
    fun `insertText in middle of line`() = runTest {
        // Given: Buffer with content
        val content = "Hello World"
        val buffer = createBuffer(content)

        // When: Insert in middle (after "Hello")
        val position = TextPosition(offset = 5L, line = 0, column = 5)
        val result = buffer.insertText(position, " Beautiful")

        // Then: Insert succeeded
        result.isSuccess shouldBe true

        // And: Content updated
        val newContent = buffer.getTextForRange(0, 0).getOrThrow()
        newContent shouldBe "Hello Beautiful World"
    }

    @Test
    fun `insertText with newlines updates line count`() = runTest {
        // Given: Buffer with single line
        val content = "Hello World"
        val buffer = createBuffer(content)
        buffer.totalLines.value shouldBe 1

        // When: Insert text with newlines
        val position = TextPosition(offset = 5L, line = 0, column = 5)
        val result = buffer.insertText(position, "\nNew Line\n")

        // Then: Insert succeeded
        result.isSuccess shouldBe true

        // And: Line count updated
        buffer.totalLines.value shouldBe 3

        // And: Lines accessible
        buffer.getTextForLine(0).getOrThrow() shouldBe "Hello"
        buffer.getTextForLine(1).getOrThrow() shouldBe "New Line"
        buffer.getTextForLine(2).getOrThrow() shouldBe " World"
    }

    @Test
    fun `insertText returns new cursor position`() = runTest {
        // Given: Buffer with content
        val content = "Hello World"
        val buffer = createBuffer(content)

        // When: Insert text
        val position = TextPosition(offset = 5L, line = 0, column = 5)
        val result = buffer.insertText(position, " INSERTED")

        // Then: Returns new position after inserted text
        val newPosition = result.getOrThrow()
        newPosition.offset shouldBe 14L  // 5 + 9 (" INSERTED".length)
    }

    // ==================== Basic Delete Operations ====================

    @Test
    fun `deleteText single character`() = runTest {
        // Given: Buffer with content
        val content = "Hello World"
        val buffer = createBuffer(content)

        // When: Delete single char at position 5 (" ")
        val result = buffer.deleteText(
            startPosition = TextPosition(offset = 5L, line = 0, column = 5),
            endPosition = TextPosition(offset = 6L, line = 0, column = 6)
        )

        // Then: Delete succeeded
        result.isSuccess shouldBe true

        // And: Content updated
        val newContent = buffer.getTextForRange(0, 0).getOrThrow()
        newContent shouldBe "HelloWorld"

        // And: Modified flag set
        buffer.isModified.value shouldBe true
    }

    @Test
    fun `deleteText multiple characters`() = runTest {
        // Given: Buffer with content
        val content = "Hello Beautiful World"
        val buffer = createBuffer(content)

        // When: Delete "Beautiful " (10 chars)
        val result = buffer.deleteText(
            startPosition = TextPosition(offset = 6L, line = 0, column = 6),
            endPosition = TextPosition(offset = 16L, line = 0, column = 16)
        )

        // Then: Delete succeeded
        result.isSuccess shouldBe true

        // And: Content updated
        val newContent = buffer.getTextForRange(0, 0).getOrThrow()
        newContent shouldBe "Hello World"
    }

    @Test
    fun `deleteText with newlines updates line count`() = runTest {
        // Given: Buffer with multiple lines
        val content = "Line 1\nLine 2\nLine 3"
        val buffer = createBuffer(content)
        buffer.totalLines.value shouldBe 3

        // When: Delete middle line including its newline
        val result = buffer.deleteText(
            startPosition = TextPosition(offset = 7L, line = 1, column = 0),  // Start of "Line 2"
            endPosition = TextPosition(offset = 14L, line = 2, column = 0)    // Before "Line 3"
        )

        // Then: Delete succeeded
        result.isSuccess shouldBe true

        // And: Line count updated
        buffer.totalLines.value shouldBe 2

        // And: Content correct
        buffer.getTextForLine(0).getOrThrow() shouldBe "Line 1"
        buffer.getTextForLine(1).getOrThrow() shouldBe "Line 3"
    }

    // ==================== Multiple Edits and Save ====================

    @Test
    fun `multiple edits before save accumulate`() = runTest {
        // Given: Buffer with content
        val content = "Hello World"
        val buffer = createBuffer(content)

        // When: Multiple edits
        buffer.insertText(TextPosition(0L, 0, 0), "START ")
        buffer.insertText(TextPosition(17L, 0, 17), " END")

        // Then: Both edits present
        val newContent = buffer.getTextForRange(0, 0).getOrThrow()
        newContent shouldBe "START Hello World END"

        // And: Modified flag set
        buffer.isModified.value shouldBe true
    }

    @Test
    fun `saveFile after edit attempts save`() = runTest {
        // Given: Buffer with edits
        val content = "Original"
        val buffer = createBuffer(content)

        buffer.insertText(TextPosition(0L, 0, 0), "Modified ")

        // Then: isModified flag is set
        buffer.isModified.value shouldBe true

        // When: Save (InMemoryDataSource now supports save for testing)
        val result = buffer.saveFile()

        // Then: Save succeeds
        result.isSuccess shouldBe true

        // And: isModified flag is cleared after successful save
        buffer.isModified.value shouldBe false
    }

    @Test
    fun `saveFile with no modifications returns success (no-op)`() = runTest {
        // Given: Buffer with no edits
        val content = "Unchanged"
        val buffer = createBuffer(content)

        // When: Attempt save
        val result = buffer.saveFile()

        // Then: Succeeds (no dirty chunks to save, so it's a no-op)
        result.isSuccess shouldBe true

        // And: isModified remains false (no edits were made)
        buffer.isModified.value shouldBe false
    }

    // Note: Can't test successful save with InMemoryDataSource
    // FileDataSource save() functionality is tested in FileDataSourceTest

    // ==================== Multi-Chunk Delete Operations ====================

    @Test
    fun `deleteText spanning exactly 2 chunks merges correctly`() = runTest {
        // Given: Content spanning 2 small chunks (100 bytes each)
        val content = "A".repeat(100) + "B".repeat(100)  // 200 bytes total
        val buffer = createBuffer(content, chunkSize = 100L)

        // Verify we have 2 chunks
        val initialContent = buffer.getTextForRange(0, 0).getOrThrow()
        initialContent.length shouldBe 200

        // When: Delete from middle of chunk 1 to middle of chunk 2
        val startPos = TextPosition(offset = 50L, line = 0, column = 50)   // Middle of chunk 1
        val endPos = TextPosition(offset = 150L, line = 0, column = 150)  // Middle of chunk 2
        val result = buffer.deleteText(startPosition = startPos, endPosition = endPos)

        // Then: Delete succeeded
        result.isSuccess shouldBe true

        // And: Content is merged correctly (first 50 A's + last 50 B's)
        val newContent = buffer.getTextForRange(0, 0).getOrThrow()
        newContent shouldBe "A".repeat(50) + "B".repeat(50)
        newContent.length shouldBe 100

        // And: Buffer is modified
        buffer.isModified.value shouldBe true
    }

    @Test
    fun `deleteText spanning 3 chunks removes middle chunk entirely`() = runTest {
        // Given: Content spanning 3 small chunks
        val content = "A".repeat(100) + "B".repeat(100) + "C".repeat(100)  // 300 bytes
        val buffer = createBuffer(content, chunkSize = 100L)

        // When: Delete from chunk 1 through entire chunk 2 into chunk 3
        val startPos = TextPosition(offset = 50L, line = 0, column = 50)   // Middle of chunk 1
        val endPos = TextPosition(offset = 250L, line = 0, column = 250)  // Middle of chunk 3
        val result = buffer.deleteText(startPosition = startPos, endPosition = endPos)

        // Then: Delete succeeded
        result.isSuccess shouldBe true

        // And: Content is correct (first 50 A's + last 50 C's)
        val newContent = buffer.getTextForRange(0, 0).getOrThrow()
        newContent shouldBe "A".repeat(50) + "C".repeat(50)
        newContent.length shouldBe 100
    }

    @Test
    fun `deleteText at exact chunk boundaries handles correctly`() = runTest {
        // Given: Content at exact chunk boundaries
        val content = "X".repeat(100) + "Y".repeat(100)  // 200 bytes
        val buffer = createBuffer(content, chunkSize = 100L)

        // When: Delete starting at exact chunk boundary
        val startPos = TextPosition(offset = 100L, line = 0, column = 100)  // Start of chunk 2
        val endPos = TextPosition(offset = 200L, line = 0, column = 200)   // End of chunk 2
        val result = buffer.deleteText(startPosition = startPos, endPosition = endPos)

        // Then: Delete succeeded
        result.isSuccess shouldBe true

        // And: Only chunk 1 remains
        val newContent = buffer.getTextForRange(0, 0).getOrThrow()
        newContent shouldBe "X".repeat(100)
        newContent.length shouldBe 100
    }

    @Test
    fun `deleteText with newlines across chunks updates line count`() = runTest {
        // Given: Multi-line content across chunks
        // Content structure: each segment has "Line N\n" (7 bytes) + filler*94 (94 bytes) = 101 bytes
        // Line counting: 3 newlines + 1 (no trailing newline after Z's) = 4 lines total
        val line1 = "Line 1\n" + "X".repeat(94)  // 101 bytes, offsets 0-100
        val line2 = "Line 2\n" + "Y".repeat(94)  // 101 bytes, offsets 101-201
        val line3 = "Line 3\n" + "Z".repeat(94)  // 101 bytes, offsets 202-302
        val content = line1 + line2 + line3      // 303 bytes total, 4 display lines
        val buffer = createBuffer(content, chunkSize = 100L)
        buffer.totalLines.value shouldBe 4  // Line 0: "Line 1", Line 1: X's, Line 2: "Line 2" + Y's, Line 3: "Line 3" + Z's

        // When: Delete entire middle line (line2) including its newline
        // This removes: byte 101-201 = "Line 2\n" + "Y"*94
        val startPos = TextPosition(offset = 101L, line = 1, column = 0)   // Start of line 2
        val endPos = TextPosition(offset = 202L, line = 2, column = 0)     // Start of line 3
        val result = buffer.deleteText(startPosition = startPos, endPosition = endPos)

        // Then: Delete succeeded
        result.isSuccess shouldBe true

        // And: Line count updated to 3
        // Result structure: "Line 1\n" + "X"*94 + "Line 3\n" + "Z"*94
        // Line 0: "Line 1"
        // Line 1: "X"*94 + "Line 3" (merged onto same line)
        // Line 2: "Z"*94 (last line, no trailing newline)
        buffer.totalLines.value shouldBe 3

        // And: Correct content remains
        buffer.getTextForLine(0).getOrThrow() shouldContain "Line 1"
        buffer.getTextForLine(1).getOrThrow() shouldContain "Line 3"  // Line 3 merged with X's
        buffer.getTextForLine(2).getOrThrow() shouldContain "Z"  // Just Z's
    }

    @Test
    fun `deleteText preserves content before and after deletion across chunks`() = runTest {
        // Given: Identifiable content in 3 chunks
        val chunk1 = "START" + "A".repeat(95)   // 100 bytes, offsets 0-99
        val chunk2 = "B".repeat(100)            // 100 bytes, offsets 100-199
        val chunk3 = "C".repeat(95) + "END"     // 98 bytes, offsets 200-297
        val content = chunk1 + chunk2 + chunk3  // 298 bytes total
        val buffer = createBuffer(content, chunkSize = 100L)

        // When: Delete middle section across all 3 chunks
        // Keep first 10 bytes (STARTAAAAA) and last 8 bytes (CCCCCEND)
        val startPos = TextPosition(offset = 10L, line = 0, column = 10)    // After first 10 bytes
        val endPos = TextPosition(offset = 290L, line = 0, column = 290)   // Start of last 8 bytes
        val result = buffer.deleteText(startPosition = startPos, endPosition = endPos)

        // Then: Delete succeeded
        result.isSuccess shouldBe true

        // And: Only first 10 and last 8 bytes remain
        val newContent = buffer.getTextForRange(0, 0).getOrThrow()
        newContent shouldBe "STARTAAAAACCCCCEND"
        newContent.length shouldBe 18
    }

    // ==================== Chunk Boundary Edge Cases ====================

    @Test
    fun `insertText at exact chunk boundary does not corrupt data`() = runTest {
        val chunk1 = "AAAA"  // 4 bytes
        val chunk2 = "BBBB"  // 4 bytes
        val content = chunk1 + chunk2
        val buffer = createBuffer(content, chunkSize = 4L)

        // Insert exactly at chunk boundary (offset 4)
        val result = buffer.insertText(TextPosition(4L, 0, 4), "XX")

        result.isSuccess shouldBe true
        val newContent = buffer.getTextForRange(0, 0).getOrThrow()
        newContent shouldBe "AAAAXXBBBB"
    }

    @Test
    fun `deleteText starting at chunk boundary preserves remaining content`() = runTest {
        val chunk1 = "AAAA"  // 4 bytes
        val chunk2 = "BBBB"  // 4 bytes
        val content = chunk1 + chunk2
        val buffer = createBuffer(content, chunkSize = 4L)

        // Delete starting exactly at chunk boundary
        val result = buffer.deleteText(
            startPosition = TextPosition(4L, 0, 4),
            endPosition = TextPosition(6L, 0, 6)
        )

        result.isSuccess shouldBe true
        val newContent = buffer.getTextForRange(0, 0).getOrThrow()
        newContent shouldBe "AAAABB"
    }

    // ==================== Empty Chunk Edge Cases ====================

    @Test
    fun `deleteText entire chunk content leaves valid empty chunk`() = runTest {
        // Given: Content spanning 3 chunks
        val chunk1 = "A".repeat(100)
        val chunk2 = "B".repeat(100)
        val chunk3 = "C".repeat(100)
        val content = chunk1 + chunk2 + chunk3
        val buffer = createBuffer(content, chunkSize = 100L)

        // When: Delete all content from chunk_1 (middle chunk)
        // This tests that after deletion, the chunk can be empty but still valid
        val result = buffer.deleteText(
            startPosition = TextPosition(100L, 0, 100),
            endPosition = TextPosition(200L, 0, 200)
        )

        // Then: Delete succeeded
        result.isSuccess shouldBe true

        // And: Content is correct (chunk_0 + chunk_2, chunk_1 removed)
        val newContent = buffer.getTextForRange(0, 0).getOrThrow()
        newContent shouldBe "A".repeat(100) + "C".repeat(100)
        newContent.length shouldBe 200

        // And: Buffer state is valid (can still perform operations)
        val position = buffer.findPosition(100L)
        position.offset shouldBe 100L
    }

    @Test
    fun `insertText after deleting to empty works correctly`() = runTest {
        // Given: Small buffer
        val content = "TEST"
        val buffer = createBuffer(content)

        // When: Delete all content
        buffer.deleteText(
            startPosition = TextPosition(0L, 0, 0),
            endPosition = TextPosition(4L, 0, 4)
        )

        val afterDelete = buffer.getTextForRange(0, 0).getOrThrow()
        afterDelete shouldBe ""

        // Then: Insert into empty buffer works
        val insertResult = buffer.insertText(TextPosition(0L, 0, 0), "NEW")
        insertResult.isSuccess shouldBe true

        val afterInsert = buffer.getTextForRange(0, 0).getOrThrow()
        afterInsert shouldBe "NEW"
    }

    @Test
    fun `deleteText to empty then undo restores content`() = runTest {
        // Given: Buffer with content
        val content = "Original Content"
        val buffer = createBuffer(content)

        // When: Delete all content
        buffer.deleteText(
            startPosition = TextPosition(0L, 0, 0),
            endPosition = TextPosition(content.length.toLong(), 0, content.length)
        )

        val afterDelete = buffer.getTextForRange(0, 0).getOrThrow()
        afterDelete shouldBe ""

        // Then: Undo restores content
        val undoResult = buffer.undo()
        undoResult.isSuccess shouldBe true

        val restored = buffer.getTextForRange(0, 0).getOrThrow()
        restored shouldBe content
    }

    @Test
    fun `saveFile with empty chunks maintains structure`() = runTest {
        // Given: Buffer with content spanning chunks
        val chunk1 = "A".repeat(100)
        val chunk2 = "B".repeat(100)
        val content = chunk1 + chunk2
        val buffer = createBuffer(content, chunkSize = 100L)

        // When: Delete middle section (creates scenario with potential empty space)
        buffer.deleteText(
            startPosition = TextPosition(50L, 0, 50),
            endPosition = TextPosition(150L, 0, 150)
        )

        // Then: Save should work correctly
        val saveResult = buffer.saveFile()
        saveResult.isSuccess shouldBe true

        // And: Content is still correct after save
        val afterSave = buffer.getTextForRange(0, 0).getOrThrow()
        afterSave shouldBe "A".repeat(50) + "B".repeat(50)
        afterSave.length shouldBe 100
    }

    @Test
    fun `multiple deletes creating empty sections work correctly`() = runTest {
        // Given: Content spanning multiple chunks
        val content = "A".repeat(50) + "B".repeat(50) + "C".repeat(50) + "D".repeat(50)
        val buffer = createBuffer(content, chunkSize = 50L)

        // When: Delete multiple sections
        buffer.deleteText(
            startPosition = TextPosition(25L, 0, 25),
            endPosition = TextPosition(75L, 0, 75)
        )

        val afterFirst = buffer.getTextForRange(0, 0).getOrThrow()
        afterFirst.length shouldBe 150

        buffer.deleteText(
            startPosition = TextPosition(50L, 0, 50),
            endPosition = TextPosition(100L, 0, 100)
        )

        // Then: Multiple deletes succeeded
        val final = buffer.getTextForRange(0, 0).getOrThrow()
        final.length shouldBe 100

        // And: Buffer is still functional
        val position = buffer.findPosition(50L)
        position.offset shouldBe 50L
    }
}
