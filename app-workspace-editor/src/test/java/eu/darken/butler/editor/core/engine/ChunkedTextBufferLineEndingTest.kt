package eu.darken.butler.editor.core.engine

import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

/**
 * TDD tests for CRLF and line ending support in ChunkedTextBuffer.
 * These tests define the expected behavior before implementation.
 */
class ChunkedTextBufferLineEndingTest : ChunkedTextBufferTestBase() {

    // ========== Line Ending Detection Tests ==========

    @Test
    fun `detect LF line endings in Unix file`() = runTest {
        // Given: Content with LF line endings
        val content = "Line 1\nLine 2\nLine 3\n"
        val buffer = createBuffer(content)

        // When: Buffer is initialized
        val detectedLineEnding = buffer.lineEnding.value

        // Then: Line ending detected as LF
        detectedLineEnding shouldBe LineEnding.LF
    }

    @Test
    fun `detect CRLF line endings in Windows file`() = runTest {
        // Given: Content with CRLF line endings
        val content = "Line 1\r\nLine 2\r\nLine 3\r\n"
        val buffer = createBuffer(content)

        // When: Buffer is initialized
        val detectedLineEnding = buffer.lineEnding.value

        // Then: Line ending detected as CRLF
        detectedLineEnding shouldBe LineEnding.CRLF
    }

    @Test
    fun `detect CR line endings in old Mac file`() = runTest {
        // Given: Content with CR line endings
        val content = "Line 1\rLine 2\rLine 3\r"
        val buffer = createBuffer(content)

        // When: Buffer is initialized
        val detectedLineEnding = buffer.lineEnding.value

        // Then: Line ending detected as CR
        detectedLineEnding shouldBe LineEnding.CR
    }

    @Test
    fun `detect mixed line endings`() = runTest {
        // Given: Content with mixed line endings
        val content = "Line 1\nLine 2\r\nLine 3\rLine 4\n"
        val buffer = createBuffer(content)

        // When: Buffer is initialized
        val detectedLineEnding = buffer.lineEnding.value

        // Then: Line ending detected as MIXED
        detectedLineEnding shouldBe LineEnding.MIXED
    }

    @Test
    fun `empty file defaults to LF`() = runTest {
        // Given: Empty content
        val content = ""
        val buffer = createBuffer(content)

        // When: Buffer is initialized
        val detectedLineEnding = buffer.lineEnding.value

        // Then: Defaults to LF
        detectedLineEnding shouldBe LineEnding.LF
    }

    // ========== Line Counting Tests ==========

    @Test
    fun `count lines correctly with CRLF endings`() = runTest {
        // Given: Content with CRLF line endings
        val content = "Line 1\r\nLine 2\r\nLine 3\r\n"
        val buffer = createBuffer(content)

        // When: Buffer is initialized
        val lineCount = buffer.totalLines.value

        // Then: Correct line count (3 lines)
        lineCount shouldBe 3
    }

    @Test
    fun `count lines correctly with CRLF no trailing newline`() = runTest {
        // Given: CRLF content without trailing newline
        val content = "Line 1\r\nLine 2\r\nLine 3"
        val buffer = createBuffer(content)

        // When: Buffer is initialized
        val lineCount = buffer.totalLines.value

        // Then: Correct line count (3 lines)
        lineCount shouldBe 3
    }

    @Test
    fun `count lines correctly with CR endings`() = runTest {
        // Given: Content with CR line endings
        val content = "Line 1\rLine 2\rLine 3\r"
        val buffer = createBuffer(content)

        // When: Buffer is initialized
        val lineCount = buffer.totalLines.value

        // Then: Correct line count (3 lines)
        lineCount shouldBe 3
    }

    @Test
    fun `count lines correctly with mixed endings`() = runTest {
        // Given: Content with mixed line endings
        val content = "Line 1\nLine 2\r\nLine 3\rLine 4"
        val buffer = createBuffer(content)

        // When: Buffer is initialized
        val lineCount = buffer.totalLines.value

        // Then: Correct line count (4 lines)
        lineCount shouldBe 4
    }

    // ========== Multi-Chunk CRLF Tests ==========

    @Test
    fun `CRLF spanning 2 chunks maintains correct line count`() = runTest {
        // Given: CRLF content spanning 2 chunks (chunk boundary at 100)
        val line1 = "A".repeat(99)  // 99 chars
        val line2 = "B".repeat(50)  // 50 chars
        val content = "$line1\r\n$line2"  // \r at 99, \n at 100 (chunk boundary)
        val buffer = createBuffer(content, chunkSize = 100L)

        // When: Buffer is initialized
        val lineCount = buffer.totalLines.value

        // Then: Correct line count (2 lines, CRLF not split logically)
        lineCount shouldBe 2

        // And: Line ending detected as CRLF
        buffer.lineEnding.value shouldBe LineEnding.CRLF
    }

    @Test
    fun `read text with CRLF across multiple chunks`() = runTest {
        // Given: Large CRLF file spanning 3 chunks
        val lines = (1..10).map { "Line $it content" }
        val content = lines.joinToString("\r\n") + "\r\n"
        val buffer = createBuffer(content, chunkSize = 50L)

        // When: Read full content
        val fullText = buffer.getText(0, content.length.toLong()).getOrThrow()

        // Then: Content matches original (CRLF preserved)
        fullText shouldBe content
    }

    // ========== getTextForLine Tests ==========

    @Test
    fun `getTextForLine with CRLF strips trailing CR`() = runTest {
        // Given: CRLF content
        val content = "Line 1\r\nLine 2\r\nLine 3\r\n"
        val buffer = createBuffer(content)

        // When: Get individual lines
        val line0 = buffer.getTextForLine(0).getOrThrow()
        val line1 = buffer.getTextForLine(1).getOrThrow()
        val line2 = buffer.getTextForLine(2).getOrThrow()

        // Then: Lines don't contain \r (stripped)
        line0 shouldBe "Line 1"
        line1 shouldBe "Line 2"
        line2 shouldBe "Line 3"
    }

    @Test
    fun `getTextForLine with LF returns line correctly`() = runTest {
        // Given: LF content
        val content = "Line 1\nLine 2\nLine 3\n"
        val buffer = createBuffer(content)

        // When: Get individual lines
        val line0 = buffer.getTextForLine(0).getOrThrow()
        val line1 = buffer.getTextForLine(1).getOrThrow()

        // Then: Lines are correct
        line0 shouldBe "Line 1"
        line1 shouldBe "Line 2"
    }

    @Test
    fun `getTextForRange with CRLF returns all lines`() = runTest {
        // Given: CRLF content with 3 lines
        val content = "Line 1\r\nLine 2\r\nLine 3"
        val buffer = createBuffer(content)

        // When: Get all lines (0 to totalLines-1)
        val allLines = buffer.getTextForRange(0, buffer.totalLines.value - 1).getOrThrow()

        // Then: All lines returned (may preserve CRLF internally)
        allLines shouldContain "Line 1"
        allLines shouldContain "Line 2"
        allLines shouldContain "Line 3"
    }

    // ========== Position Calculation Tests ==========

    @Test
    fun `findPosition accounts for CRLF characters`() = runTest {
        // Given: CRLF content
        // "Line 1\r\n" = 8 chars (Line 1 = 6, \r\n = 2)
        // "Line 2\r\n" = 8 chars
        // "Line 3" = 6 chars
        val content = "Line 1\r\nLine 2\r\nLine 3"
        val buffer = createBuffer(content)

        // When: Find position of "Line 2" start (offset 8)
        val position = buffer.findPosition(8L)

        // Then: Position is line 1, column 0
        position.line shouldBe 1
        position.column shouldBe 0
        position.offset shouldBe 8L
    }

    @Test
    fun `findOffset accounts for CRLF characters`() = runTest {
        // Given: CRLF content
        val content = "Line 1\r\nLine 2\r\nLine 3"
        val buffer = createBuffer(content)

        // When: Find offset of line 1, column 0
        val offset = buffer.findOffset(1, 0)

        // Then: Offset is 8 (after "Line 1\r\n")
        offset shouldBe 8L
    }

    // ========== Edit Operations Tests ==========

    @Test
    fun `insert text preserves document line ending style CRLF`() = runTest {
        // Given: CRLF document
        val content = "Line 1\r\nLine 2\r\n"
        val buffer = createBuffer(content)

        // When: Insert new line at end
        val position = buffer.findPosition(content.length.toLong())
        buffer.insertText(position, "Line 3\r\n")

        // Then: Full content has CRLF throughout
        val fullText = buffer.getText(0, buffer.totalLength.value).getOrThrow()
        fullText shouldBe "Line 1\r\nLine 2\r\nLine 3\r\n"
    }

    @Test
    fun `insert text preserves document line ending style LF`() = runTest {
        // Given: LF document
        val content = "Line 1\nLine 2\n"
        val buffer = createBuffer(content)

        // When: Insert new line at end
        val position = buffer.findPosition(content.length.toLong())
        buffer.insertText(position, "Line 3\n")

        // Then: Full content has LF throughout
        val fullText = buffer.getText(0, buffer.totalLength.value).getOrThrow()
        fullText shouldBe "Line 1\nLine 2\nLine 3\n"
    }

    @Test
    fun `delete text maintains CRLF line ending style`() = runTest {
        // Given: CRLF document
        val content = "Line 1\r\nLine 2\r\nLine 3\r\n"
        val buffer = createBuffer(content)

        // When: Delete middle line (including CRLF)
        val startPos = TextPosition(8L, 1, 0)  // Start of "Line 2"
        val endPos = TextPosition(16L, 2, 0)   // Start of "Line 3"
        buffer.deleteText(startPos, endPos)

        // Then: Remaining content still uses CRLF
        val fullText = buffer.getText(0, buffer.totalLength.value).getOrThrow()
        fullText shouldBe "Line 1\r\nLine 3\r\n"

        // And: Line ending style preserved
        buffer.lineEnding.value shouldBe LineEnding.CRLF
    }

    // ========== Undo/Redo with CRLF Tests ==========

    @Test
    fun `undo insert preserves CRLF line endings`() = runTest {
        // Given: CRLF document
        val originalContent = "Line 1\r\nLine 2\r\n"
        val buffer = createBuffer(originalContent)

        // When: Insert then undo
        val position = buffer.findPosition(originalContent.length.toLong())
        buffer.insertText(position, "Line 3\r\n")
        buffer.undo()

        // Then: Content restored with CRLF
        val fullText = buffer.getText(0, buffer.totalLength.value).getOrThrow()
        fullText shouldBe originalContent
    }

    @Test
    fun `redo insert maintains CRLF line endings`() = runTest {
        // Given: CRLF document with insert + undo
        val originalContent = "Line 1\r\nLine 2\r\n"
        val buffer = createBuffer(originalContent)
        val position = buffer.findPosition(originalContent.length.toLong())
        buffer.insertText(position, "Line 3\r\n")
        buffer.undo()

        // When: Redo
        buffer.redo()

        // Then: Content has CRLF
        val fullText = buffer.getText(0, buffer.totalLength.value).getOrThrow()
        fullText shouldBe "Line 1\r\nLine 2\r\nLine 3\r\n"
    }

    // ========== Save Operation Tests ==========

    @Test
    fun `save file maintains CRLF line endings`() = runTest {
        // Given: CRLF document with edits
        val originalContent = "Line 1\r\nLine 2\r\n"
        val buffer = createBuffer(originalContent)

        val position = buffer.findPosition(originalContent.length.toLong())
        buffer.insertText(position, "Line 3\r\n")

        // When: Save file
        val saveResult = buffer.saveFile()

        // Then: Save succeeds
        saveResult.isSuccess shouldBe true

        // And: Content still has CRLF after save
        val fullText = buffer.getText(0, buffer.totalLength.value).getOrThrow()
        fullText shouldBe "Line 1\r\nLine 2\r\nLine 3\r\n"
    }

    // ========== Edge Cases ==========

    @Test
    fun `chunk boundary splitting CRLF handles correctly`() = runTest {
        // Given: Content where CRLF is split across chunk boundary
        // Create content with \r at position 99 and \n at position 100
        val beforeCR = "X".repeat(99)
        val afterLF = "Y".repeat(50)
        val content = beforeCR + "\r\n" + afterLF
        val buffer = createBuffer(content, chunkSize = 100L)

        // When: Read content
        val fullText = buffer.getText(0, buffer.totalLength.value).getOrThrow()

        // Then: CRLF is preserved and not corrupted
        fullText shouldBe content
        fullText.length shouldBe 151
    }

    @Test
    fun `large CRLF file loads and operates correctly`() = runTest {
        // Given: Large file with 100 CRLF lines
        val lines = (1..100).map { "Line $it with some content here" }
        val content = lines.joinToString("\r\n")
        val buffer = createBuffer(content, chunkSize = 100L)

        // When: Buffer initialized
        val lineCount = buffer.totalLines.value

        // Then: Correct line count
        lineCount shouldBe 100

        // And: Can read random lines
        val line50 = buffer.getTextForLine(50).getOrThrow()
        line50 shouldBe "Line 51 with some content here"
    }

    @Test
    fun `search in CRLF file returns correct positions`() = runTest {
        // Given: CRLF file with search target
        val content = "Hello World\r\nFoo Bar\r\nHello Again\r\n"
        val buffer = createBuffer(content)

        // When: Search for "Hello"
        val results = buffer.search("Hello", startFrom = null, ignoreCase = false)

        // Then: Found 2 results
        results.size shouldBe 2

        // And: Positions account for CRLF
        results[0].position.line shouldBe 0
        results[1].position.line shouldBe 2
    }
}
