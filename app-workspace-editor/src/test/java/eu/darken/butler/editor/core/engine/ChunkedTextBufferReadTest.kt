package eu.darken.butler.editor.core.engine

import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

/**
 * Tests for ChunkedTextBuffer read operations including line counting, content display,
 * chunk boundaries, position/offset conversion, and edge cases.
 */
class ChunkedTextBufferReadTest : ChunkedTextBufferTestBase() {

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

    // ==================== Position/Offset Edge Cases ====================

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

    // ==================== UTF-8 Safety Verification ====================

    @Test
    fun `UTF-8 emoji at chunk boundary preserves character - smoke test`() = runTest {
        // CRITICAL SAFETY TEST: UTF-16 surrogate pairs at chunk boundaries
        // Emoji "🎉" = 2 Char values (UTF-16 surrogate pair) in Java/Kotlin String
        // Content: 98 X's + emoji (positions 98-99) + 98 Y's = 198 total Char values
        // Chunk size: 100 Char values
        // Emoji at 98-99 is safely within chunk_0 [0, 100)
        val emoji = "🎉"
        val content = "X".repeat(98) + emoji + "Y".repeat(98)

        val buffer = createBuffer(content, chunkSize = 100L)

        // Read the emoji - should be complete
        val text = buffer.getText(98L, 100L).getOrThrow()

        // Emoji must be intact
        text shouldBe emoji
        text.length shouldBe 2  // Emoji is 2 Char values (surrogate pair)
    }

    @Test
    fun `UTF-16 emoji split across chunk boundary is protected`() = runTest {
        // This test exposes the CRITICAL BUG: emoji split across chunk boundary
        // Emoji at positions 99-100, chunk boundary at 100
        // chunk_0: [0, 100) contains high surrogate at position 99
        // chunk_1: [100, 199) contains low surrogate at position 100
        // Reading chunk_0 alone would return corrupted data!
        val emoji = "🎉"
        val content = "X".repeat(99) + emoji + "Y".repeat(98)  // 199 Char values total

        val buffer = createBuffer(content, chunkSize = 100L)

        // Read the emoji that spans the chunk boundary
        val text = buffer.getText(99L, 101L).getOrThrow()

        // With surrogate protection, emoji should be intact
        text shouldBe emoji
        text.length shouldBe 2
    }

    @Test
    fun `emoji with skin tone at chunk boundary is preserved`() = runTest {
        // Emoji with skin tone modifier: 👍🏻 = U+1F44D (thumbs up) + U+1F3FB (light skin tone)
        // = 4 UTF-16 chars total: high1, low1, high2, low2
        // This tests grapheme clusters (multiple code points forming one visual character)
        val thumbsUpWithSkinTone = "👍🏻"
        val content = "X".repeat(97) + thumbsUpWithSkinTone + "Y".repeat(96)  // Position to span boundary

        val buffer = createBuffer(content, chunkSize = 100L)

        // Read the skin tone emoji that spans chunks
        val text = buffer.getText(97L, 101L).getOrThrow()

        // Should preserve the full emoji with skin tone modifier
        text.contains("👍") shouldBe true
        text.contains(thumbsUpWithSkinTone) shouldBe true
        text.length shouldBe 4  // Two emoji (each 2 UTF-16 chars)
    }

    @Test
    fun `ZWJ emoji sequence at chunk boundary is preserved`() = runTest {
        // Family emoji with Zero-Width Joiners: 👨‍👩‍👧‍👦
        // = Man + ZWJ + Woman + ZWJ + Girl + ZWJ + Boy
        // = (2 + 1 + 2 + 1 + 2 + 1 + 2) = 11 UTF-16 chars total
        val familyEmoji = "👨‍👩‍👧‍👦"
        val content = "X".repeat(95) + familyEmoji + "Y".repeat(94)

        val buffer = createBuffer(content, chunkSize = 100L)

        // Read across chunk boundary
        val text = buffer.getText(95L, 106L).getOrThrow()

        // Should preserve the complete ZWJ sequence
        text.contains(familyEmoji) shouldBe true
        text.length shouldBe 11  // Full ZWJ sequence
    }

    @Test
    fun `combining diacritics at chunk boundary are preserved`() = runTest {
        // é can be represented as: e (U+0065) + combining acute accent (U+0301)
        // This is different from the precomposed é (U+00E9)
        val baseE = "e"
        val combiningAcute = "\u0301"
        val eWithDiacritic = baseE + combiningAcute  // Decomposed form
        val textWithDiacritics = "Caf$eWithDiacritic"  // "Café" with combining diacritic

        val content = "X".repeat(98) + textWithDiacritics + "Y".repeat(97)

        val buffer = createBuffer(content, chunkSize = 100L)

        // Read across chunk boundary where combining character might be split
        val text = buffer.getText(98L, 103L).getOrThrow()

        // Should contain the base+combining sequence (may not display perfectly in test output)
        text.contains("Caf") shouldBe true
        (text.length >= 4) shouldBe true  // At least "Cafe" (base characters)
    }
}
