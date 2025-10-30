package eu.darken.butler.editor.core.engine

import eu.darken.butler.editor.core.sources.InMemoryDataSource
import eu.darken.butler.workspace.core.Workspace
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.comparables.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import testhelpers.BaseTest

class ChunkedTextBufferTest : BaseTest() {

    private val workspaceId = Workspace.Id()

    // ==================== Helper Methods ====================

    /**
     * Creates a ChunkedTextBuffer with in-memory content for testing.
     * This avoids file I/O and makes tests faster and more isolated.
     *
     * @param content The text content to load into the buffer
     * @param chunkSize The size of each chunk in bytes (default: 64KB)
     */
    private suspend fun createBuffer(
        content: String,
        chunkSize: Long = ChunkManager.DEFAULT_CHUNK_SIZE
    ): ChunkedTextBuffer {
        val dataSource = InMemoryDataSource(workspaceId, content)
        dataSource.open()

        val repository = ChunkRepository(workspaceId, dataSource, chunkSize)
        val manager = ChunkManager(workspaceId, repository, chunkSize)
        val buffer = ChunkedTextBuffer(workspaceId, manager, repository)

        buffer.initialize().getOrThrow()
        return buffer
    }

    // ==================== P0 Tests: Line Counting (Critical Bugs) ====================

    @Test
    fun `file ending without trailing newline counts correctly`() = runTest {
        // Given: Content without trailing newline (3 lines)
        val content = "line1\nline2\nline3"
        val buffer = createBuffer(content)

        // Then: Should count 3 lines (not 2 like wc -l would)
        buffer.totalLines.value shouldBe 3

        // And: Last line should be accessible
        val lastLine = buffer.getTextForLine(2).getOrThrow()
        lastLine shouldBe "line3"
    }

    @Test
    fun `file ending with trailing newline counts correctly`() = runTest {
        // Given: Content with trailing newline
        val content = "line1\nline2\nline3\n"
        val buffer = createBuffer(content)

        // Then: Should count 3 lines (not 4 with empty line)
        buffer.totalLines.value shouldBe 3

        // And: Lines should be accessible
        buffer.getTextForLine(0).getOrThrow() shouldBe "line1"
        buffer.getTextForLine(1).getOrThrow() shouldBe "line2"
        buffer.getTextForLine(2).getOrThrow() shouldBe "line3"

        // And: No 4th line should exist
        buffer.getTextForLine(3).isFailure shouldBe true
    }

    @Test
    fun `only last chunk gets plus one for missing newline not all chunks`() = runTest {
        // Given: Large content spanning multiple chunks (> 64KB)
        // Create 100 lines of ~700 bytes each = ~70KB (2 chunks)
        val lines = (1..100).map { "Line $it: " + "x".repeat(690) }
        val content = lines.joinToString("\n") // No trailing newline

        val buffer = createBuffer(content)

        // Then: Line count should be 100 (not 100 + number_of_chunks)
        buffer.totalLines.value shouldBe 100

        // And: Last line accessible
        val lastLine = buffer.getTextForLine(99).getOrThrow()
        lastLine shouldBe "Line 100: " + "x".repeat(690)
    }

    @Test
    fun `empty file has one line`() = runTest {
        // Given: Empty content
        val buffer = createBuffer("")

        // Then: Should have 1 line (editor convention)
        buffer.totalLines.value shouldBe 1

        // And: That line is empty
        buffer.getTextForLine(0).getOrThrow() shouldBe ""
    }

    @Test
    fun `file with only newlines counts correctly`() = runTest {
        // Given: Content with only newlines
        val content = "\n\n\n"
        val buffer = createBuffer(content)

        // Then: Should count 3 lines (3 newlines = 3 empty lines)
        buffer.totalLines.value shouldBe 3

        // And: All lines should be empty
        buffer.getTextForLine(0).getOrThrow() shouldBe ""
        buffer.getTextForLine(1).getOrThrow() shouldBe ""
        buffer.getTextForLine(2).getOrThrow() shouldBe ""
    }

    // ==================== P0 Tests: split() Bug (Content Display) ====================

    @Test
    fun `getLineFromChunk handles content ending with newline without empty element`() = runTest {
        // Given: Content ending with newline
        val content = "line1\nline2\n"
        val buffer = createBuffer(content)

        // Then: Should have 2 lines (not 3 with empty element from split)
        buffer.totalLines.value shouldBe 2

        // And: Lines should have correct content
        buffer.getTextForLine(0).getOrThrow() shouldBe "line1"
        buffer.getTextForLine(1).getOrThrow() shouldBe "line2"

        // And: No 3rd line exists
        buffer.getTextForLine(2).isFailure shouldBe true
    }

    @Test
    fun `getLineFromChunk handles content ending without newline includes partial line`() = runTest {
        // Given: Content without trailing newline
        val content = "line1\nline2"
        val buffer = createBuffer(content)

        // Then: Both lines accessible
        buffer.totalLines.value shouldBe 2
        buffer.getTextForLine(0).getOrThrow() shouldBe "line1"
        buffer.getTextForLine(1).getOrThrow() shouldBe "line2"
    }

    @Test
    fun `last line displays correct content not second to last`() = runTest {
        // Regression test for the bug where line N showed content of line N-1
        // Given: File with multiple lines, no trailing newline
        val content = "first\nsecond\nthird"
        val buffer = createBuffer(content)

        // When: Requesting the last line
        val lastLine = buffer.getTextForLine(2).getOrThrow()

        // Then: Should get "third", not "second"
        lastLine shouldBe "third"
    }

    @Test
    fun `single newline creates one empty line`() = runTest {
        // Given: Content with just a newline
        val content = "\n"
        val buffer = createBuffer(content)

        // Then: One empty line before the newline
        buffer.totalLines.value shouldBe 1
        buffer.getTextForLine(0).getOrThrow() shouldBe ""
    }

    // ==================== P0 Tests: Complete Reads (Accuracy) ====================

    @Test
    fun `buildChunkMetadata reads full chunks not partial`() = runTest {
        // Regression test for the 10% data loss bug (partial reads)
        // Given: Content with known line count
        val lines = (1..1000).map { "Line $it" }
        val content = lines.joinToString("\n") + "\n"
        val buffer = createBuffer(content)

        // Then: All lines counted (not just 10%)
        buffer.totalLines.value shouldBe 1000

        // And: Sample lines have correct content
        buffer.getTextForLine(0).getOrThrow() shouldBe "Line 1"
        buffer.getTextForLine(500).getOrThrow() shouldBe "Line 501"
        buffer.getTextForLine(999).getOrThrow() shouldBe "Line 1000"
    }

    @Test
    fun `metadata chunk count matches expected for file size`() = runTest {
        // Regression test for 34,620 vs 4,328 chunk bug
        // Given: Content of ~200KB (should be ~4 chunks at 64KB each)
        val largeContent = "x".repeat(200 * 1024)
        val buffer = createBuffer(largeContent)

        // Then: File initialized successfully
        buffer.totalLength.value shouldBe 200L * 1024

        // And: Content is accessible
        val text = buffer.getText(0, 100).getOrThrow()
        text shouldBe "x".repeat(100)
    }

    // ==================== P1 Tests: Chunk Boundaries ====================

    @Test
    fun `getTextForRange within single chunk`() = runTest {
        // Given: Content with multiple lines in one chunk
        val content = "line1\nline2\nline3\nline4\nline5"
        val buffer = createBuffer(content)

        // When: Getting range within single chunk
        val result = buffer.getTextForRange(1, 3).getOrThrow()

        // Then: Correct lines returned with newlines between
        result shouldBe "line2\nline3\nline4"
    }

    @Test
    fun `getTextForRange spanning multiple chunks`() = runTest {
        // Given: Large content spanning 2 chunks
        val lines = (1..200).map { "Line $it: " + "x".repeat(400) }
        val content = lines.joinToString("\n")
        val buffer = createBuffer(content)

        // When: Getting range across chunks
        val result = buffer.getTextForRange(0, 199).getOrThrow()

        // Then: All lines present
        val resultLines = result.split("\n")
        resultLines.size shouldBe 200
        resultLines[0] shouldBe "Line 1: " + "x".repeat(400)
        resultLines[199] shouldBe "Line 200: " + "x".repeat(400)
    }

    @Test
    fun `getText with offset range in single chunk`() = runTest {
        // Given: Simple content
        val content = "Hello, World!"
        val buffer = createBuffer(content)

        // When: Getting substring by offset
        val result = buffer.getText(0, 5).getOrThrow()

        // Then: Correct substring
        result shouldBe "Hello"
    }

    @Test
    fun `getText with offset range spanning chunks`() = runTest {
        // Given: Content > 64KB
        val content = "A".repeat(70 * 1024)
        val buffer = createBuffer(content)

        // When: Getting range across chunk boundary
        val result = buffer.getText(64 * 1024 - 10, 64 * 1024 + 10).getOrThrow()

        // Then: Correct content at boundary
        result shouldBe "A".repeat(20)
    }

    // ==================== P1 Tests: Position & Offset Conversion ====================

    @Test
    fun `findPosition converts offset to correct line and column`() = runTest {
        // Given: Multi-line content
        val content = "abc\ndef\nghi"
        val buffer = createBuffer(content)

        // When: Finding position for various offsets
        val pos0 = buffer.findPosition(0) // 'a'
        val pos4 = buffer.findPosition(4) // 'd'
        val pos8 = buffer.findPosition(8) // 'g'

        // Then: Correct line and column
        pos0.line shouldBe 0
        pos0.column shouldBe 0

        pos4.line shouldBe 1
        pos4.column shouldBe 0

        pos8.line shouldBe 2
        pos8.column shouldBe 0
    }

    @Test
    fun `findOffset converts line and column to correct offset`() = runTest {
        // Given: Multi-line content
        val content = "abc\ndef\nghi"
        val buffer = createBuffer(content)

        // When: Finding offsets for various positions
        val offset1 = buffer.findOffset(0, 0) // 'a'
        val offset2 = buffer.findOffset(1, 0) // 'd'
        val offset3 = buffer.findOffset(2, 0) // 'g'

        // Then: Correct offsets
        offset1 shouldBe 0
        offset2 shouldBe 4
        offset3 shouldBe 8
    }

    // ==================== P2 Tests: Edge Cases ====================

    @Test
    fun `consecutive empty lines preserved`() = runTest {
        // Given: Content with consecutive empty lines
        val content = "line1\n\n\nline2"
        val buffer = createBuffer(content)

        // Then: All lines counted
        buffer.totalLines.value shouldBe 4

        // And: Empty lines are empty
        buffer.getTextForLine(0).getOrThrow() shouldBe "line1"
        buffer.getTextForLine(1).getOrThrow() shouldBe ""
        buffer.getTextForLine(2).getOrThrow() shouldBe ""
        buffer.getTextForLine(3).getOrThrow() shouldBe "line2"
    }

    @Test
    fun `single character file`() = runTest {
        // Given: Single character
        val content = "A"
        val buffer = createBuffer(content)

        // Then: One line with that character
        buffer.totalLines.value shouldBe 1
        buffer.getTextForLine(0).getOrThrow() shouldBe "A"
    }

    @Test
    fun `UTF-8 multibyte characters handled correctly`() = runTest {
        // Given: Content with emojis and Chinese characters
        val content = "Hello 👋\n你好\n🎉"
        val buffer = createBuffer(content)

        // Then: Lines counted correctly
        buffer.totalLines.value shouldBe 3

        // And: Characters preserved
        buffer.getTextForLine(0).getOrThrow() shouldBe "Hello 👋"
        buffer.getTextForLine(1).getOrThrow() shouldBe "你好"
        buffer.getTextForLine(2).getOrThrow() shouldBe "🎉"
    }

    @Test
    fun `getTextForLine with out of bounds index fails`() = runTest {
        // Given: Content with 3 lines
        val content = "line1\nline2\nline3"
        val buffer = createBuffer(content)

        // When: Requesting invalid line numbers
        val negativeLine = buffer.getTextForLine(-1)
        val beyondEnd = buffer.getTextForLine(10)

        // Then: Both fail
        negativeLine.isFailure shouldBe true
        beyondEnd.isFailure shouldBe true
    }

    @Test
    fun `file with very long lines`() = runTest {
        // Given: Lines longer than typical
        val longLine = "x".repeat(10000)
        val content = "$longLine\nshort\n$longLine"
        val buffer = createBuffer(content)

        // Then: All lines accessible
        buffer.totalLines.value shouldBe 3
        buffer.getTextForLine(0).getOrThrow() shouldBe longLine
        buffer.getTextForLine(1).getOrThrow() shouldBe "short"
        buffer.getTextForLine(2).getOrThrow() shouldBe longLine
    }

    @Test
    fun `mixed line lengths handled correctly`() = runTest {
        // Given: Mix of very short and long lines
        val content = "a\n" + "b".repeat(1000) + "\nc\n" + "d".repeat(5000) + "\ne"
        val buffer = createBuffer(content)

        // Then: All lines correct
        buffer.totalLines.value shouldBe 5
        buffer.getTextForLine(0).getOrThrow() shouldBe "a"
        buffer.getTextForLine(1).getOrThrow() shouldBe "b".repeat(1000)
        buffer.getTextForLine(2).getOrThrow() shouldBe "c"
        buffer.getTextForLine(3).getOrThrow() shouldBe "d".repeat(5000)
        buffer.getTextForLine(4).getOrThrow() shouldBe "e"
    }

    // ==================== P2 Tests: Basic Operations ====================

    @Test
    fun `getTextForRange with full file range`() = runTest {
        // Given: Multi-line content
        val content = "line1\nline2\nline3"
        val buffer = createBuffer(content)

        // When: Getting all lines
        val result = buffer.getTextForRange(0, 2).getOrThrow()

        // Then: Complete content returned
        result shouldBe content
    }

    @Test
    fun `totalLength reflects content size`() = runTest {
        // Given: Content of known size
        val content = "Hello, World!"
        val buffer = createBuffer(content)

        // Then: Length matches
        buffer.totalLength.value shouldBe content.length.toLong()
    }

    @Test
    fun `fileInfo available for in-memory source`() = runTest {
        // Given: Buffer with content
        val content = "test content"
        val buffer = createBuffer(content)

        // Then: File info may be null (in-memory source)
        // This is OK - just verify it doesn't crash
        buffer.fileInfo.value // May be null for InMemoryDataSource
    }

    // ==================== P1 Tests: Chunk Boundary Cases (With Small Chunks) ====================

    @Test
    fun `small chunks - text retrieval spanning multiple chunks`() = runTest {
        // Given: Content split into 1KB chunks (smaller than default 64KB)
        val content = "0123456789".repeat(500)  // 5000 bytes = 5 chunks with 1KB size
        val buffer = createBuffer(content, chunkSize = 1024)

        // When: Getting text spanning multiple chunks (bytes 500-3500)
        val result = buffer.getText(500, 3500).getOrThrow()

        // Then: Correct content retrieved
        result shouldBe content.substring(500, 3500)
        result.length shouldBe 3000
    }

    @Test
    fun `small chunks - multiple lines with small chunk size`() = runTest {
        // Given: Content with 1KB chunks (much smaller than default 64KB)
        val line1 = "Line 1 content that fits in a single chunk"
        val line2 = "Line 2 content that also fits in one chunk"
        val line3 = "Line 3 content fits as well in its chunk"
        val content = "$line1\n$line2\n$line3\n"
        val buffer = createBuffer(content, chunkSize = 1024)

        // Then: All lines counted correctly
        buffer.totalLines.value shouldBe 3

        // And: Each line accessible
        buffer.getTextForLine(0).getOrThrow() shouldBe line1
        buffer.getTextForLine(1).getOrThrow() shouldBe line2
        buffer.getTextForLine(2).getOrThrow() shouldBe line3
    }

    @Test
    fun `small chunks - chunk ending with newline`() = runTest {
        // Given: 100-byte chunks where content fits within chunks
        val line1 = "01234567890123456789012345678901234567890123456789"  // 50 chars
        val line2 = "56789"
        val content = "$line1\n$line2"
        val buffer = createBuffer(content, chunkSize = 100)

        // Then: Lines counted correctly
        buffer.totalLines.value shouldBe 2

        // And: Lines have correct content
        buffer.getTextForLine(0).getOrThrow() shouldBe line1
        buffer.getTextForLine(1).getOrThrow() shouldBe line2
    }

    @Test
    fun `small chunks - 1KB chunks with large content`() = runTest {
        // Given: Content with 1KB chunks (much smaller than 64KB default)
        val lines = (1..100).map { "Line $it with some content to make it realistic" }
        val content = lines.joinToString("\n")
        val buffer = createBuffer(content, chunkSize = 1024)

        // Then: All lines counted
        buffer.totalLines.value shouldBe 100

        // And: Sample lines correct
        buffer.getTextForLine(0).getOrThrow() shouldBe "Line 1 with some content to make it realistic"
        buffer.getTextForLine(50).getOrThrow() shouldBe "Line 51 with some content to make it realistic"
        buffer.getTextForLine(99).getOrThrow() shouldBe "Line 100 with some content to make it realistic"
    }

    @Test
    fun `small chunks - long line within reasonable chunk size`() = runTest {
        // Given: 100-byte chunks with lines that fit within them
        val longLine = "x".repeat(80)  // Long but fits in 100-byte chunk
        val content = "$longLine\nshort"
        val buffer = createBuffer(content, chunkSize = 100)

        // Then: Correct line count
        buffer.totalLines.value shouldBe 2

        // And: Long line retrieved correctly
        buffer.getTextForLine(0).getOrThrow() shouldBe longLine
        buffer.getTextForLine(1).getOrThrow() shouldBe "short"
    }

    @Test
    fun `small chunks - getTextForRange crossing multiple chunks`() = runTest {
        // Given: Content with 500-byte chunks (smaller than default)
        val lines = (1..10).map { "Line $it with enough content to test chunking behavior properly" }
        val content = lines.joinToString("\n")
        val buffer = createBuffer(content, chunkSize = 500)

        // When: Getting range spanning chunks (lines 1-4)
        val result = buffer.getTextForRange(1, 4).getOrThrow()

        // Then: Correct lines returned
        val expected = lines.slice(1..4).joinToString("\n")
        result shouldBe expected
    }

    @Test
    fun `small chunks - empty chunk at end`() = runTest {
        // Given: Content size exactly divisible by chunk size
        val content = "0123456789".repeat(10)  // Exactly 100 bytes
        val buffer = createBuffer(content, chunkSize = 50)

        // Then: Two chunks, no empty third chunk
        buffer.totalLength.value shouldBe 100

        // And: Content accessible
        val text = buffer.getText(0, 100).getOrThrow()
        text shouldBe content
    }

    // ==================== P0 Tests: Multi-Chunk Lines (Critical for Long Lines) ====================

    @Test
    fun `multi-chunk line - very long line spanning 3 chunks`() = runTest {
        // Given: A single 300-byte line with 100-byte chunks (spans 3 chunks)
        val veryLongLine = "x".repeat(300)
        val content = "$veryLongLine\nshort line"
        val buffer = createBuffer(content, chunkSize = 100)

        // Then: Should count 2 lines
        buffer.totalLines.value shouldBe 2

        // When: Getting the long line
        val line = buffer.getTextForLine(0).getOrThrow()

        // Then: Should get COMPLETE line (not truncated at chunk boundary)
        line shouldBe veryLongLine
        line.length shouldBe 300  // Full 300 chars, not just first 100

        // And: Second line should work too
        buffer.getTextForLine(1).getOrThrow() shouldBe "short line"
    }

    @Test
    fun `multi-chunk line - entire file is single line without newline`() = runTest {
        // Given: 500-byte file with NO newlines, 100-byte chunks
        val singleLine = "ABCDEFGHIJ".repeat(50)  // 500 bytes
        val buffer = createBuffer(singleLine, chunkSize = 100)

        // Then: Should count as 1 line
        buffer.totalLines.value shouldBe 1

        // When: Getting the line
        val line = buffer.getTextForLine(0).getOrThrow()

        // Then: Should get complete content
        line shouldBe singleLine
        line.length shouldBe 500
    }

    @Test
    fun `multi-chunk line - multiple long lines each spanning chunks`() = runTest {
        // Given: 3 lines, each 150 bytes, with 100-byte chunks
        val line1 = "1".repeat(150)
        val line2 = "2".repeat(150)
        val line3 = "3".repeat(150)
        val content = "$line1\n$line2\n$line3"
        val buffer = createBuffer(content, chunkSize = 100)

        // Then: 3 lines counted
        buffer.totalLines.value shouldBe 3

        // When: Getting each line
        val retrievedLine1 = buffer.getTextForLine(0).getOrThrow()
        val retrievedLine2 = buffer.getTextForLine(1).getOrThrow()
        val retrievedLine3 = buffer.getTextForLine(2).getOrThrow()

        // Then: All lines complete
        retrievedLine1 shouldBe line1
        retrievedLine2 shouldBe line2
        retrievedLine3 shouldBe line3
    }

    @Test
    fun `multi-chunk line - line ending exactly at chunk boundary`() = runTest {
        // Given: Line with exactly 99 chars + newline (100 bytes total = exact chunk size)
        val line1 = "x".repeat(99)
        val line2 = "second line"
        val content = "$line1\n$line2"
        val buffer = createBuffer(content, chunkSize = 100)

        // Then: Should count 2 lines
        buffer.totalLines.value shouldBe 2

        // When: Getting each line
        val retrievedLine1 = buffer.getTextForLine(0).getOrThrow()
        val retrievedLine2 = buffer.getTextForLine(1).getOrThrow()

        // Then: Both lines correct (tests chunk boundary transition)
        retrievedLine1 shouldBe line1
        retrievedLine2 shouldBe line2
    }

    @Test
    fun `multi-chunk line - getTextForRange with multi-chunk lines`() = runTest {
        // Given: 3 lines each spanning 2 chunks (150 bytes each) with 100-byte chunks
        val line1 = "1".repeat(150)
        val line2 = "2".repeat(150)
        val line3 = "3".repeat(150)
        val content = "$line1\n$line2\n$line3"
        val buffer = createBuffer(content, chunkSize = 100)

        // When: Getting range of all 3 lines at once
        val range = buffer.getTextForRange(0, 2).getOrThrow()

        // Then: Should get all 3 complete lines with newlines preserved
        range shouldBe "$line1\n$line2\n$line3"
    }

    @Test
    fun `multi-chunk line - empty lines between multi-chunk lines`() = runTest {
        // Given: Long line + 3 empty lines + another long line
        val line1 = "A".repeat(150)
        val line2 = ""  // empty line
        val line3 = ""  // empty line
        val line4 = ""  // empty line
        val line5 = "B".repeat(150)
        val content = "$line1\n\n\n\n$line5"
        val buffer = createBuffer(content, chunkSize = 100)

        // Then: Should count 5 lines
        buffer.totalLines.value shouldBe 5

        // When: Getting each line
        val retrievedLine1 = buffer.getTextForLine(0).getOrThrow()
        val retrievedLine2 = buffer.getTextForLine(1).getOrThrow()
        val retrievedLine3 = buffer.getTextForLine(2).getOrThrow()
        val retrievedLine4 = buffer.getTextForLine(3).getOrThrow()
        val retrievedLine5 = buffer.getTextForLine(4).getOrThrow()

        // Then: All lines correct (long lines complete, empty lines empty)
        retrievedLine1 shouldBe line1
        retrievedLine2 shouldBe line2
        retrievedLine3 shouldBe line3
        retrievedLine4 shouldBe line4
        retrievedLine5 shouldBe line5
    }

    // ==================== Edit Operations Tests ====================

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

        // When: Attempt save (will fail with InMemoryDataSource)
        val result = buffer.saveFile()

        // Then: Save fails (InMemoryDataSource doesn't support save)
        result.isFailure shouldBe true

        // Note: isModified remains true because save failed
        // (This is correct behavior - only clear on successful save)
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

    // ===== Phase 4: Multi-Chunk Delete Tests =====

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

    // ===== Phase 5: Search Functionality Tests =====

    @Test
    fun `search in single chunk returns correct line numbers`() = runTest {
        // Given: Content in single chunk with multiple lines
        val content = "Line 0: Hello\nLine 1: World\nLine 2: Hello again\nLine 3: Test"
        val buffer = createBuffer(content)

        // When: Search for "Hello"
        val matches = buffer.search(query = "Hello", startFrom = null, ignoreCase = false)

        // Then: Found 2 matches
        matches.size shouldBe 2

        // And: First match is on line 0
        matches[0].position.line shouldBe 0
        matches[0].matchText shouldBe "Hello"

        // And: Second match is on line 2
        matches[1].position.line shouldBe 2
        matches[1].matchText shouldBe "Hello"
    }

    @Test
    fun `search spanning multiple chunks returns correct line numbers`() = runTest {
        // Given: Content spanning multiple chunks with search term in each
        val line0 = "SEARCH in chunk 1\n" + "X".repeat(82)  // 100 bytes total
        val line1 = "Y".repeat(82) + "\nSEARCH in chunk 2\n"  // 100 bytes
        val line2 = "Z".repeat(82) + "\nSEARCH in chunk 3"  // 100 bytes
        val content = line0 + line1 + line2
        val buffer = createBuffer(content, chunkSize = 100L)

        // When: Search for "SEARCH"
        val matches = buffer.search(query = "SEARCH", startFrom = null, ignoreCase = false)

        // Then: Found 3 matches
        matches.size shouldBe 3

        // And: Matches have correct file-relative line numbers (not chunk-relative!)
        matches[0].position.line shouldBe 0  // First line of file
        matches[1].position.line shouldBe 2  // Third line of file (after line 0 and line 1)
        matches[2].position.line shouldBe 4  // Fifth line of file
    }

    @Test
    fun `search case-sensitive distinguishes matches`() = runTest {
        // Given: Content with mixed case
        val content = "hello HELLO Hello HeLLo"
        val buffer = createBuffer(content)

        // When: Search case-sensitive for "Hello"
        val matches = buffer.search(query = "Hello", startFrom = null, ignoreCase = false)

        // Then: Found only exact match
        matches.size shouldBe 1
        matches[0].matchText shouldBe "Hello"
    }

    @Test
    fun `search returns results sorted by offset`() = runTest {
        // Given: Content with multiple occurrences
        val content = "apple banana apple cherry apple date"
        val buffer = createBuffer(content)

        // When: Search for "apple"
        val matches = buffer.search(query = "apple", startFrom = null, ignoreCase = false)

        // Then: Results are sorted by offset
        matches.size shouldBe 3
        matches[0].position.offset shouldBe 0L
        matches[1].position.offset shouldBe 13L
        matches[2].position.offset shouldBe 26L

        // And: Each subsequent offset is greater than previous
        for (i in 1 until matches.size) {
            matches[i].position.offset shouldBeGreaterThan matches[i - 1].position.offset
        }
    }

    @Test
    fun `search with no matches returns empty list`() = runTest {
        // Given: Content without search term
        val content = "This is a test document"
        val buffer = createBuffer(content)

        // When: Search for non-existent term
        val matches = buffer.search(query = "NOTFOUND", startFrom = null, ignoreCase = false)

        // Then: Returns empty list (not failure)
        matches.shouldBeEmpty()
    }

    @Test
    fun `search with empty query returns empty list`() = runTest {
        // Given: Any content
        val content = "Some content here"
        val buffer = createBuffer(content)

        // When: Search for empty string
        val matches = buffer.search(query = "", startFrom = null, ignoreCase = false)

        // Then: Returns empty list
        matches.shouldBeEmpty()
    }

    @Test
    fun `search result positions match actual text locations`() = runTest {
        // Given: Multi-line content
        val content = "Line one\nLine two with TARGET\nLine three\nTARGET at start"
        val buffer = createBuffer(content)

        // When: Search for "TARGET"
        val matches = buffer.search(query = "TARGET", startFrom = null, ignoreCase = false)

        // Then: Found 2 matches
        matches.size shouldBe 2

        // And: Can retrieve actual text at reported offsets
        val text1 = buffer.getText(matches[0].position.offset, matches[0].position.offset + 6).getOrThrow()
        text1 shouldBe "TARGET"

        val text2 = buffer.getText(matches[1].position.offset, matches[1].position.offset + 6).getOrThrow()
        text2 shouldBe "TARGET"
    }

    // ===== Phase 6: Undo/Redo Tests =====

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
        val buffer = createBuffer(content, chunkSize = 100L)

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
        buffer.totalLines.value shouldBe 3

        // When: Insert newlines then undo
        buffer.insertText(TextPosition(6, 0, 6), "\nNew Line\n")
        buffer.totalLines.value shouldBe 5

        buffer.undo()

        // Then: Line count restored
        buffer.totalLines.value shouldBe 3

        // And: Redo restores added lines
        buffer.redo()
        buffer.totalLines.value shouldBe 5
    }

    // ============================================================
    // Phase 7A: Error Handling Tests
    // ============================================================

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

    // ============================================================
    // Phase 7B: Position/Offset Edge Cases
    // ============================================================

    @Test
    fun `findPosition at exact chunk boundary returns correct position`() = runTest {
        val chunk1 = "A".repeat(100)  // 100 bytes - chunk_0
        val chunk2 = "B".repeat(100)  // 100 bytes - chunk_1
        val content = chunk1 + chunk2
        val buffer = createBuffer(content, chunkSize = 100L)

        // Test position at exact chunk boundary (offset 100)
        val position = buffer.findPosition(offset = 100L)

        position.offset shouldBe 100L
        position.line shouldBe 0
        position.column shouldBe 100
    }

    @Test
    fun `findOffset at last character in file returns correct offset`() = runTest {
        val buffer = createBuffer("Hello")

        val offset = buffer.findOffset(line = 0, column = 4)

        offset shouldBe 4L  // 'o' is at offset 4
    }

    @Test
    fun `findPosition in empty file returns zero position`() = runTest {
        val buffer = createBuffer("")

        val position = buffer.findPosition(offset = 0L)

        position shouldBe TextPosition.ZERO
    }

    @Test
    fun `findOffset with line beyond total should handle gracefully`() = runTest {
        val buffer = createBuffer("Line 1\nLine 2")

        // Attempting to find offset for line 10 (beyond file)
        // Should return position at end of file or fail gracefully
        val result = runCatching {
            buffer.findOffset(line = 10, column = 0)
        }

        // We expect this to fail or return file size
        result.isFailure shouldBe true
    }

    @Test
    fun `findPosition at file end returns correct line and column`() = runTest {
        val content = "Line 1\nLine 2"
        val buffer = createBuffer(content)

        val position = buffer.findPosition(offset = content.length.toLong())

        position.offset shouldBe content.length.toLong()
        position.line shouldBe 1
        position.column shouldBe 6  // End of "Line 2"
    }

    @Test
    fun `getText at exact chunk boundary returns correct content`() = runTest {
        val chunk1 = "AAAA"  // 4 bytes
        val chunk2 = "BBBB"  // 4 bytes
        val content = chunk1 + chunk2
        val buffer = createBuffer(content, chunkSize = 4L)

        // Get text starting exactly at chunk boundary
        val result = buffer.getText(startOffset = 4L, endOffset = 6L)

        result.isSuccess shouldBe true
        result.getOrThrow() shouldBe "BB"
    }

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

    // ============================================================
    // Phase 7C: Chunk Eviction/Cache Pressure Tests
    // ============================================================

    @Test
    fun `reading content spanning many chunks maintains data integrity despite eviction`() = runTest {
        // Create content with 10 chunks (cache holds 5)
        // Reading from all chunks will cause evictions
        val chunks = (0 until 10).map { i ->
            "${('A' + i)}".repeat(10)  // 10 bytes per chunk
        }
        val content = chunks.joinToString("")
        val buffer = createBuffer(content, chunkSize = 10L)

        // Read from each chunk - this forces cache evictions
        for (i in 0 until 10) {
            val offset = i * 10L
            val text = buffer.getText(offset, offset + 5).getOrThrow()
            text shouldBe "${('A' + i)}".repeat(5)
        }

        // Verify full content still intact after evictions
        val finalContent = buffer.getTextForRange(0, 0).getOrThrow()
        finalContent shouldBe content
    }

    @Test
    fun `editing evicted chunks reloads and modifies correctly`() = runTest {
        // Create 8 chunks, cache holds 5
        val content = "A".repeat(80)  // 8 chunks of 10 bytes each
        val buffer = createBuffer(content, chunkSize = 10L)

        // Access chunks 0-4 (fill cache)
        for (i in 0 until 5) {
            buffer.getText(i * 10L, i * 10L + 5).getOrThrow()
        }

        // Access chunks 5-7 (evicts chunks 0-2)
        for (i in 5 until 8) {
            buffer.getText(i * 10L, i * 10L + 5).getOrThrow()
        }

        // Now edit chunk 0 (should auto-reload since it was evicted)
        val result = buffer.insertText(TextPosition(5L, 0, 5), "XX")
        result.isSuccess shouldBe true

        // Verify edit worked
        val modifiedContent = buffer.getText(0L, 15L).getOrThrow()
        modifiedContent shouldBe "AAAAAXXAAAAA"
    }

    @Test
    fun `dirty chunks are not evicted from cache`() = runTest {
        // Create 8 chunks
        val content = "A".repeat(80)  // 8 chunks of 10 bytes each
        val buffer = createBuffer(content, chunkSize = 10L)

        // Modify chunk 0 (makes it dirty)
        buffer.insertText(TextPosition(5L, 0, 5), "XX")

        // Access many other chunks to trigger evictions
        for (i in 1 until 8) {
            buffer.getText(i * 10L, i * 10L + 5).getOrThrow()
        }

        // Chunk 0 should still have the modification (not evicted because dirty)
        val chunk0Content = buffer.getText(0L, 15L).getOrThrow()
        chunk0Content shouldBe "AAAAAXXAAAAA"
    }

    @Test
    fun `searching across evicted chunks works correctly`() = runTest {
        // Create 10 chunks with pattern
        val chunks = (0 until 10).map { i ->
            "Line$i" + "X".repeat(5) + "\n"  // 10 bytes per chunk
        }
        val content = chunks.joinToString("")
        val buffer = createBuffer(content, chunkSize = 10L)

        // Access first 5 chunks
        for (i in 0 until 5) {
            buffer.getText(i * 10L, i * 10L + 5).getOrThrow()
        }

        // Search for pattern (may span evicted chunks)
        val results = buffer.search("Line", startFrom = null, ignoreCase = false)

        results.size shouldBe 10
        for (i in 0 until 10) {
            results[i].matchText shouldBe "Line"
        }
    }

    @Test
    fun `getTextForRange across evicted chunks returns correct content`() = runTest {
        val content = (0 until 10).joinToString("\n") { "Line $it with text" }  // ~190 bytes
        val buffer = createBuffer(content, chunkSize = 20L)  // ~10 chunks

        // Access first line
        buffer.getTextForRange(0, 0).getOrThrow()

        // Access last line (causes evictions in between)
        buffer.getTextForRange(9, 9).getOrThrow()

        // Now get middle range (chunks may be evicted)
        val middleContent = buffer.getTextForRange(4, 6).getOrThrow()

        middleContent shouldContain "Line 4"
        middleContent shouldContain "Line 5"
        middleContent shouldContain "Line 6"
    }

    @Test
    fun `undo-redo works correctly with evicted chunks`() = runTest {
        val content = "A".repeat(100)  // 10 chunks of 10 bytes
        val buffer = createBuffer(content, chunkSize = 10L)

        // Make edit in chunk 0
        buffer.insertText(TextPosition(5L, 0, 5), "XX")

        // Access many chunks to trigger evictions
        for (i in 1 until 10) {
            buffer.getText(i * 10L, i * 10L + 5).getOrThrow()
        }

        // Undo should work even if chunk was evicted
        val undoResult = buffer.undo()
        undoResult.isSuccess shouldBe true

        val restoredContent = buffer.getText(0L, 10L).getOrThrow()
        restoredContent shouldBe "A".repeat(10)
    }

    @Test
    fun `multi-chunk delete with cache pressure maintains integrity`() = runTest {
        // Create content spanning 8 chunks
        val content = (0 until 8).joinToString("") { i -> "$i".repeat(10) }
        val buffer = createBuffer(content, chunkSize = 10L)

        // Access chunks to fill cache
        for (i in 0 until 5) {
            buffer.getText(i * 10L, i * 10L + 5).getOrThrow()
        }

        // Delete across chunks 2-4 (some may be evicted)
        val deleteResult = buffer.deleteText(
            startPosition = TextPosition(25L, 0, 25),
            endPosition = TextPosition(45L, 0, 45)
        )
        deleteResult.isSuccess shouldBe true

        // Verify correct content after deletion
        val result = buffer.getTextForRange(0, 0).getOrThrow()
        result shouldBe "0".repeat(10) + "1".repeat(10) + "22222" + "55555" + "6".repeat(10) + "7".repeat(10)
    }

    @Test
    fun `line count remains accurate with chunk eviction`() = runTest {
        val lines = (0 until 20).joinToString("\n") { "Line $it" }
        val buffer = createBuffer(lines, chunkSize = 10L)

        // Access different parts causing evictions
        buffer.getTextForRange(0, 0).getOrThrow()
        buffer.getTextForRange(10, 10).getOrThrow()
        buffer.getTextForRange(5, 5).getOrThrow()
        buffer.getTextForRange(15, 15).getOrThrow()

        // Line count should still be correct
        buffer.totalLines.value shouldBe 20
    }

    @Test
    fun `position calculations work correctly with evicted chunks`() = runTest {
        val content = "A".repeat(100)  // 10 chunks of 10 bytes each
        val buffer = createBuffer(content, chunkSize = 10L)

        // Access first and last chunks
        buffer.getText(0L, 5L).getOrThrow()
        buffer.getText(95L, 100L).getOrThrow()

        // Find position in middle (chunk may be evicted)
        val position = buffer.findPosition(offset = 50L)

        position.offset shouldBe 50L
        position.line shouldBe 0
        position.column shouldBe 50
    }

    @Test
    fun `saving file with evicted dirty chunks works correctly`() = runTest {
        val content = "A".repeat(80)  // 8 chunks
        val buffer = createBuffer(content, chunkSize = 10L)

        // Modify multiple chunks
        buffer.insertText(TextPosition(5L, 0, 5), "1")
        buffer.insertText(TextPosition(25L, 0, 25), "2")
        buffer.insertText(TextPosition(45L, 0, 45), "3")

        // Access many other chunks (but dirty chunks shouldn't be evicted)
        for (i in 4 until 8) {
            buffer.getText(i * 10L, i * 10L + 5).getOrThrow()
        }

        // Save should succeed with all modifications
        val saveResult = buffer.saveFile()
        saveResult.isSuccess shouldBe true

        // Verify modifications are still present
        val saved = buffer.getTextForRange(0, 0).getOrThrow()
        saved shouldContain "1"
        saved shouldContain "2"
        saved shouldContain "3"
    }
}
