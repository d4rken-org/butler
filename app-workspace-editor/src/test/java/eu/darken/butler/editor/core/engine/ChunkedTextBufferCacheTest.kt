package eu.darken.butler.editor.core.engine

import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

/**
 * Tests for ChunkedTextBuffer chunk eviction and cache pressure scenarios including
 * data integrity under cache eviction, dirty chunk protection, and operation correctness.
 */
class ChunkedTextBufferCacheTest : ChunkedTextBufferTestBase() {

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

        // Verify edit worked - read only the modified chunk_0 (now 12 bytes)
        val modifiedContent = buffer.getText(0L, 12L).getOrThrow()
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
        // Read only chunk_0's content (now 12 bytes after insertion)
        val chunk0Content = buffer.getText(0L, 12L).getOrThrow()
        chunk0Content shouldBe "AAAAAXXAAAAA"
    }

    @Test
    fun `searching across evicted chunks works correctly`() = runTest {
        // Create 10 chunks with pattern
        val chunks = (0 until 10).map { i ->
            "Line$i" + "X".repeat(5) + "\n"  // 11 bytes per chunk
        }
        val content = chunks.joinToString("")
        val buffer = createBuffer(content, chunkSize = 11L)

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
        // Deletes from offset 25 to 45: last 5 chars of chunk_2 + all of chunk_3 + first 5 chars of chunk_4
        val deleteResult = buffer.deleteText(
            startPosition = TextPosition(25L, 0, 25),
            endPosition = TextPosition(45L, 0, 45)
        )
        deleteResult.isSuccess shouldBe true

        // Verify correct content after deletion
        // Result: first 5 chars of chunk_2 + last 5 chars of chunk_4 + chunks 5, 6, 7
        val result = buffer.getTextForRange(0, 0).getOrThrow()
        result shouldBe "0".repeat(10) + "1".repeat(10) + "22222" + "44444" + "5".repeat(10) + "6".repeat(10) + "7".repeat(10)
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
