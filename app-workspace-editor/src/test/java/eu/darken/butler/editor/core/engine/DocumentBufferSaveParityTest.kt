package eu.darken.butler.editor.core.engine

import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

/**
 * Save-dependent black-box tests deferred from the ported ChunkedTextBuffer suites (Cache, Edit,
 * LineEnding) until saveFile landed. Assertions are unchanged from the originals.
 */
class DocumentBufferSaveParityTest : DocumentBufferTestBase() {

    // From ChunkedTextBufferCacheTest
    @Test
    fun `saving file with evicted dirty chunks works correctly`() = runTest {
        val content = "A".repeat(80)  // 8 chunks
        val buffer = createBuffer(content, blockSize = 10)

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

    // From ChunkedTextBufferEditTest
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

    // From ChunkedTextBufferEditTest
    @Test
    fun `saveFile with empty chunks maintains structure`() = runTest {
        // Given: Buffer with content spanning chunks
        val chunk1 = "A".repeat(100)
        val chunk2 = "B".repeat(100)
        val content = chunk1 + chunk2
        val buffer = createBuffer(content, blockSize = 100)

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

    // From ChunkedTextBufferLineEndingTest
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
}
