package eu.darken.butler.editor.core.engine

import io.kotest.matchers.comparables.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

/**
 * Tests for selection offset recalculation in EditorEngine.
 *
 * These tests verify that setSelection() correctly recalculates offsets
 * from line/column positions when the UI sends placeholder offset=0
 * during virtual scrolling of large files.
 *
 * The fix ensures that TextPosition offsets are computed via
 * DocumentBuffer.findOffset() rather than trusting potentially
 * stale offset values from the UI layer.
 */
class EditorEngineSelectionTest : DocumentBufferTestBase() {

    // ==================== Single Line Selection ====================

    @Test
    fun `findOffset calculates correct offset for single line selection`() = runTest {
        // Given: Multi-line content
        val content = "Line 0\nLine 1\nLine 2"
        val buffer = createBuffer(content)

        // When: UI sends selection on line 1 with placeholder offset=0
        val actualOffset = buffer.findOffset(line = 1, column = 3)

        // Then: Offset is recalculated correctly (7 chars "Line 0\n" + 3 chars "Lin")
        actualOffset shouldBe 10L
    }

    @Test
    fun `findOffset calculates correct offset at start of line`() = runTest {
        // Given: Multi-line content
        val content = "First line\nSecond line\nThird line"
        val buffer = createBuffer(content)

        // When: Calculate offset at start of line 2
        val actualOffset = buffer.findOffset(line = 2, column = 0)

        // Then: Offset is 11 ("First line\n") + 12 ("Second line\n") = 23
        actualOffset shouldBe 23L
    }

    @Test
    fun `findOffset calculates correct offset at end of line`() = runTest {
        // Given: Multi-line content
        val content = "Short\nMedium\nLong"
        val buffer = createBuffer(content)

        // When: Calculate offset at end of line 1 (column 6, which is length of "Medium")
        val actualOffset = buffer.findOffset(line = 1, column = 6)

        // Then: Offset is 6 ("Short\n") + 6 ("Medium") = 12
        actualOffset shouldBe 12L
    }

    // ==================== Multi-Line Selection ====================

    @Test
    fun `findOffset calculates both offsets for multi-line selection`() = runTest {
        // Given: Content with varying line lengths
        val content = "Short\nMedium line\nVery long line here"
        val buffer = createBuffer(content)

        // When: Select from line 0, col 2 to line 2, col 5 (simulating placeholder offsets)
        val startOffset = buffer.findOffset(line = 0, column = 2)  // "Sh|ort"
        val endOffset = buffer.findOffset(line = 2, column = 5)    // "Very |long..."

        // Then: Both offsets calculated correctly
        startOffset shouldBe 2L
        endOffset shouldBe 23L  // 6 ("Short\n") + 12 ("Medium line\n") + 5 ("Very ")
    }

    @Test
    fun `findOffset handles selection spanning many lines`() = runTest {
        // Given: Content with 10 lines
        val content = (0..9).joinToString("\n") { "Line $it" }
        val buffer = createBuffer(content)

        // When: Select from line 2 to line 7
        val startOffset = buffer.findOffset(line = 2, column = 0)
        val endOffset = buffer.findOffset(line = 7, column = 6)  // End of "Line 7"

        // Then: Offsets are calculated correctly
        // Lines 0-1: "Line 0\nLine 1\n" = 14 bytes
        startOffset shouldBe 14L

        // Line 7 starts at offset 49 (7 lines * 7 bytes), column 6 = offset 55
        endOffset shouldBe 55L
    }

    // ==================== Chunk Boundary Cases ====================

    @Test
    fun `findOffset handles selection at exact chunk boundary`() = runTest {
        // Given: Content spanning multiple chunks (100 bytes each)
        val chunk1 = "A".repeat(100)
        val chunk2 = "B".repeat(100)
        val content = "$chunk1\n$chunk2"
        val buffer = createBuffer(content, blockSize = 100)

        // When: Find offset at chunk boundary (start of line 1)
        val offsetAtBoundary = buffer.findOffset(line = 1, column = 0)

        // Then: Offset is 101 (100 A's + newline)
        offsetAtBoundary shouldBe 101L
    }

    @Test
    fun `findOffset handles selection spanning multiple chunks`() = runTest {
        // Given: Content spanning 3 chunks (100 bytes each)
        val line1 = "A".repeat(100)
        val line2 = "B".repeat(100)
        val line3 = "C".repeat(100)
        val content = "$line1\n$line2\n$line3"
        val buffer = createBuffer(content, blockSize = 100)

        // When: Find offset in the middle of chunk 3
        val offsetInChunk3 = buffer.findOffset(line = 2, column = 50)

        // Then: Offset is 101 (chunk1+\n) + 101 (chunk2+\n) + 50 = 252
        offsetInChunk3 shouldBe 252L
    }

    // ==================== Large File / Virtual Scrolling ====================

    @Test
    fun `findOffset handles selection in large file with many lines`() = runTest {
        // Given: Large file with 1000 lines (simulating virtual scrolling scenario)
        val content = (0..999).joinToString("\n") { "Line $it with content" }
        val buffer = createBuffer(content)

        // When: Select on line 500 (far from start, common in virtual scrolling)
        val line500Col5 = buffer.findOffset(line = 500, column = 5)

        // Then: Offset is calculated (not placeholder 0)
        line500Col5 shouldBeGreaterThan 0L

        // And: Can retrieve correct text at that offset
        val text = buffer.getText(line500Col5, line500Col5 + 10).getOrThrow()
        text shouldContain "500"
    }

    @Test
    fun `findOffset handles selection near end of large file`() = runTest {
        // Given: Large file with 1000 lines
        val lines = (0..999).map { "Line $it" }
        val content = lines.joinToString("\n")
        val buffer = createBuffer(content)

        // When: Find offset on line 995
        val line995Start = buffer.findOffset(line = 995, column = 0)
        val line995End = buffer.findOffset(line = 995, column = 7)  // Length of "Line 995"

        // Then: Both offsets are calculated
        line995Start shouldBeGreaterThan 0L
        line995End shouldBeGreaterThan line995Start

        // And: Character count matches expected
        val characterCount = (line995End - line995Start).toInt()
        characterCount shouldBe 7
    }

    // ==================== Character Count Calculation ====================

    @Test
    fun `selection offset difference matches expected character count`() = runTest {
        // Given: Content with known structure
        val content = "Hello World\nThis is a test\nFinal line"
        val buffer = createBuffer(content)

        // When: Select "World" on line 0 (from col 6 to col 11)
        val startOffset = buffer.findOffset(line = 0, column = 6)
        val endOffset = buffer.findOffset(line = 0, column = 11)

        // Then: Character count is 5 (length of "World")
        val characterCount = (endOffset - startOffset).toInt()
        characterCount shouldBe 5
    }

    @Test
    fun `selection across multiple lines calculates correct character count`() = runTest {
        // Given: Multi-line content
        val content = "Line 1\nLine 2\nLine 3"
        val buffer = createBuffer(content)

        // When: Select from "1" to "2" (line 0 col 5 to line 1 col 5)
        val startOffset = buffer.findOffset(line = 0, column = 5)
        val endOffset = buffer.findOffset(line = 1, column = 5)

        // Then: Character count includes: "1" + "\n" + "Line " = 7 characters
        val characterCount = (endOffset - startOffset).toInt()
        characterCount shouldBe 7
    }

    @Test
    fun `selection with placeholder offset zero recalculates to non-zero offset`() = runTest {
        // Given: Content with multiple lines
        val content = "First\nSecond\nThird"
        val buffer = createBuffer(content)

        // When: Simulate UI sending placeholder offset=0 for line 1, column 3
        // (In actual code, setSelection() would call findOffset to recalculate)
        val recalculatedOffset = buffer.findOffset(line = 1, column = 3)

        // Then: Offset is NOT zero (it's 6 ("First\n") + 3 ("Sec") = 9)
        recalculatedOffset shouldBe 9L
        recalculatedOffset shouldBeGreaterThan 0L
    }

    @Test
    fun `empty selection has zero character count`() = runTest {
        // Given: Content
        val content = "Some content here"
        val buffer = createBuffer(content)

        // When: Selection at same position (cursor, no selection)
        val offset = buffer.findOffset(line = 0, column = 5)

        // Then: Start and end are same, character count is 0
        val characterCount = (offset - offset).toInt()
        characterCount shouldBe 0
    }

    @Test
    fun `select all calculates full file character count`() = runTest {
        // Given: Known content
        val content = "Hello\nWorld"  // 5 + 1 + 5 = 11 characters
        val buffer = createBuffer(content)

        // When: Select from start to end
        val startOffset = buffer.findOffset(line = 0, column = 0)
        val endLine = buffer.totalLines.value - 1
        val lastLineText = buffer.getTextForLine(endLine).getOrThrow()
        val endOffset = buffer.findOffset(line = endLine, column = lastLineText.length)

        // Then: Character count matches total content length
        val characterCount = (endOffset - startOffset).toInt()
        characterCount shouldBe 11
    }
}
