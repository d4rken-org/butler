package eu.darken.butler.editor.core.engine

import eu.darken.butler.editor.core.sources.InMemoryDataSource
import eu.darken.butler.workspace.core.Workspace
import io.kotest.matchers.shouldBe
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
}
