package eu.darken.butler.editor.core.engine

import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.jupiter.api.Test
import testhelpers.BaseTest

/**
 * Tests for EditorChunk sealed class hierarchy.
 * Tests both Text and Binary variants.
 */
class EditorChunkTest : BaseTest() {

    // ==================== Text Chunk Tests ====================

    @Test
    fun `EditorChunk Text creation with line metadata`() {
        // Given: Text chunk data
        val content = "Hello\nWorld"
        val lineCount = 2
        val lineEnding = LineEnding.LF

        // When: Create Text chunk
        val chunk = EditorChunk.Text(
            offset = 0L,
            content = content,
            size = content.length.toLong(),
            lineCount = lineCount,
            lineEnding = lineEnding,
            isDirty = false
        )

        // Then: All fields set correctly
        chunk.offset shouldBe 0L
        chunk.content shouldBe content
        chunk.size shouldBe 11L
        chunk.lineCount shouldBe lineCount
        chunk.lineEnding shouldBe lineEnding
        chunk.isDirty shouldBe false
        chunk.refCount shouldBe 0
        chunk.isPinned shouldBe false
    }

    @Test
    fun `EditorChunk Text with default values`() {
        // When: Create with defaults
        val chunk = EditorChunk.Text(
            offset = 100L,
            content = "Test",
            size = 4L,
            lineCount = 1,
            lineEnding = LineEnding.LF,
            isDirty = false
        )

        // Then: Defaults applied
        chunk.refCount shouldBe 0
        chunk.isPinned shouldBe false
    }

    @Test
    fun `EditorChunk Text copy preserves type`() {
        // Given: Text chunk
        val original = EditorChunk.Text(
            offset = 0L,
            content = "Original",
            size = 8L,
            lineCount = 1,
            lineEnding = LineEnding.LF,
            isDirty = false
        )

        // When: Copy with changes
        val copied = original.copy(content = "Modified", isDirty = true)

        // Then: Type preserved, fields updated
        copied.content shouldBe "Modified"
        copied.isDirty shouldBe true
        copied.offset shouldBe original.offset
        copied.lineCount shouldBe original.lineCount
    }

    @Test
    fun `EditorChunk Text markDirty sets isDirty flag`() {
        // Given: Clean text chunk
        val chunk = EditorChunk.Text(
            offset = 0L,
            content = "Test",
            size = 4L,
            lineCount = 1,
            lineEnding = LineEnding.LF,
            isDirty = false
        )

        // When: Mark dirty
        val dirtyChunk = chunk.markDirty() as EditorChunk.Text

        // Then: isDirty flag set
        dirtyChunk.isDirty shouldBe true
        dirtyChunk.content shouldBe chunk.content // Other fields unchanged
    }

    @Test
    fun `EditorChunk Text pin and unpin`() {
        // Given: Unpinned chunk
        val chunk = EditorChunk.Text(
            offset = 0L,
            content = "Test",
            size = 4L,
            lineCount = 1,
            lineEnding = LineEnding.LF,
            isDirty = false
        )

        // When: Pin
        val pinned = chunk.pin() as EditorChunk.Text

        // Then: isPinned flag set
        pinned.isPinned shouldBe true
        pinned.refCount shouldBe 1

        // When: Unpin
        val unpinned = pinned.unpin() as EditorChunk.Text

        // Then: isPinned flag cleared
        unpinned.isPinned shouldBe false
        unpinned.refCount shouldBe 0
    }

    @Test
    fun `EditorChunk Text with empty content`() {
        // When: Create with empty string
        val chunk = EditorChunk.Text(
            offset = 0L,
            content = "",
            size = 0L,
            lineCount = 0,
            lineEnding = LineEnding.LF,
            isDirty = false
        )

        // Then: Empty chunk valid
        chunk.content shouldBe ""
        chunk.size shouldBe 0L
        chunk.lineCount shouldBe 0
    }

    @Test
    fun `EditorChunk Text with multiline content and CRLF`() {
        // Given: Content with CRLF line endings
        val content = "Line 1\r\nLine 2\r\nLine 3"
        val lineCount = 3

        // When: Create chunk
        val chunk = EditorChunk.Text(
            offset = 0L,
            content = content,
            size = content.length.toLong(),
            lineCount = lineCount,
            lineEnding = LineEnding.CRLF,
            isDirty = false
        )

        // Then: Line ending correctly set
        chunk.lineEnding shouldBe LineEnding.CRLF
        chunk.lineCount shouldBe 3
    }

    // ==================== Binary Chunk Tests ====================

    @Test
    fun `EditorChunk Binary creation without line metadata`() {
        // Given: Binary data
        val bytes = byteArrayOf(0x00, 0x01, 0x02, 0xFF.toByte())

        // When: Create Binary chunk
        val chunk = EditorChunk.Binary(
            offset = 0L,
            content = bytes,
            size = bytes.size.toLong(),
            isDirty = false
        )

        // Then: All fields set correctly
        chunk.offset shouldBe 0L
        chunk.content shouldBe bytes
        chunk.size shouldBe 4L
        chunk.isDirty shouldBe false
        chunk.refCount shouldBe 0
        chunk.isPinned shouldBe false
    }

    @Test
    fun `EditorChunk Binary with all byte values`() {
        // Given: All possible byte values (0x00-0xFF)
        val bytes = ByteArray(256) { it.toByte() }

        // When: Create chunk
        val chunk = EditorChunk.Binary(
            offset = 0L,
            content = bytes,
            size = bytes.size.toLong(),
            isDirty = false
        )

        // Then: All bytes preserved
        chunk.content.size shouldBe 256
        chunk.content[0] shouldBe 0x00.toByte()
        chunk.content[255] shouldBe 0xFF.toByte()
    }

    @Test
    fun `EditorChunk Binary with null bytes`() {
        // Given: Binary data with nulls
        val bytes = byteArrayOf(0x00, 0x00, 0x00, 0x00)

        // When: Create chunk
        val chunk = EditorChunk.Binary(
            offset = 0L,
            content = bytes,
            size = bytes.size.toLong(),
            isDirty = false
        )

        // Then: Null bytes preserved
        chunk.content.all { it == 0x00.toByte() } shouldBe true
    }

    @Test
    fun `EditorChunk Binary equals works correctly with ByteArray`() {
        // Given: Two chunks with same bytes and same id
        val bytes1 = byteArrayOf(0x01, 0x02, 0x03)
        val bytes2 = byteArrayOf(0x01, 0x02, 0x03)
        val sharedId = EditorChunk.ChunkId.generate()

        val chunk1 = EditorChunk.Binary(
            offset = 0L,
            content = bytes1,
            size = 3L,
            isDirty = false,
            id = sharedId
        )
        val chunk2 = EditorChunk.Binary(
            offset = 0L,
            content = bytes2,
            size = 3L,
            isDirty = false,
            id = sharedId
        )

        // Then: Chunks are equal (content-based equality)
        chunk1 shouldBe chunk2
    }

    @Test
    fun `EditorChunk Binary equals distinguishes different bytes`() {
        // Given: Two chunks with different bytes
        val bytes1 = byteArrayOf(0x01, 0x02, 0x03)
        val bytes2 = byteArrayOf(0x01, 0x02, 0x04)

        val chunk1 = EditorChunk.Binary(
            offset = 0L,
            content = bytes1,
            size = 3L,
            isDirty = false
        )
        val chunk2 = EditorChunk.Binary(
            offset = 0L,
            content = bytes2,
            size = 3L,
            isDirty = false
        )

        // Then: Chunks are not equal
        chunk1 shouldNotBe chunk2
    }

    @Test
    fun `EditorChunk Binary hashCode consistency`() {
        // Given: Two chunks with same bytes and same id
        val bytes1 = byteArrayOf(0xDE.toByte(), 0xAD.toByte(), 0xBE.toByte(), 0xEF.toByte())
        val bytes2 = byteArrayOf(0xDE.toByte(), 0xAD.toByte(), 0xBE.toByte(), 0xEF.toByte())
        val sharedId = EditorChunk.ChunkId.generate()

        val chunk1 = EditorChunk.Binary(
            offset = 0L,
            content = bytes1,
            size = 4L,
            isDirty = false,
            id = sharedId
        )
        val chunk2 = EditorChunk.Binary(
            offset = 0L,
            content = bytes2,
            size = 4L,
            isDirty = false,
            id = sharedId
        )

        // Then: Hash codes are equal
        chunk1.hashCode() shouldBe chunk2.hashCode()
    }

    @Test
    fun `EditorChunk Binary copy preserves type`() {
        // Given: Binary chunk
        val original = EditorChunk.Binary(
            offset = 0L,
            content = byteArrayOf(0x01, 0x02),
            size = 2L,
            isDirty = false
        )

        // When: Copy with changes
        val copied = original.copy(isDirty = true)

        // Then: Type preserved, fields updated
        copied.isDirty shouldBe true
        copied.offset shouldBe original.offset
        copied.content shouldBe original.content
    }

    @Test
    fun `EditorChunk Binary markDirty sets isDirty flag`() {
        // Given: Clean binary chunk
        val chunk = EditorChunk.Binary(
            offset = 0L,
            content = byteArrayOf(0x00),
            size = 1L,
            isDirty = false
        )

        // When: Mark dirty
        val dirtyChunk = chunk.markDirty() as EditorChunk.Binary

        // Then: isDirty flag set
        dirtyChunk.isDirty shouldBe true
        dirtyChunk.content shouldBe chunk.content
    }

    @Test
    fun `EditorChunk Binary with empty content`() {
        // When: Create with empty ByteArray
        val chunk = EditorChunk.Binary(
            offset = 0L,
            content = byteArrayOf(),
            size = 0L,
            isDirty = false
        )

        // Then: Empty chunk valid
        chunk.content.size shouldBe 0
        chunk.size shouldBe 0L
    }

    // ==================== Sealed Class Tests ====================

    @Test
    fun `EditorChunk is sealed class with Text and Binary variants`() {
        // Given: Both chunk types
        val textChunk: EditorChunk = EditorChunk.Text(
            offset = 0L,
            content = "Text",
            size = 4L,
            lineCount = 1,
            lineEnding = LineEnding.LF,
            isDirty = false
        )

        val binaryChunk: EditorChunk = EditorChunk.Binary(
            offset = 0L,
            content = byteArrayOf(0x01),
            size = 1L,
            isDirty = false
        )

        // When: Type check
        val textIsText = textChunk is EditorChunk.Text
        val binaryIsBinary = binaryChunk is EditorChunk.Binary

        // Then: Type checks work
        textIsText shouldBe true
        binaryIsBinary shouldBe true
    }

    @Test
    fun `EditorChunk when expression is exhaustive`() {
        // Given: Chunk (could be either type)
        val chunk: EditorChunk = EditorChunk.Text(
            offset = 0L,
            content = "Test",
            size = 4L,
            lineCount = 1,
            lineEnding = LineEnding.LF,
            isDirty = false
        )

        // When: Exhaustive when
        val result = when (chunk) {
            is EditorChunk.Text -> "text"
            is EditorChunk.Binary -> "binary"
        }

        // Then: Handled correctly
        result shouldBe "text"
    }

    @Test
    fun `EditorChunk common properties accessible on sealed class`() {
        // Given: Chunks of both types
        val chunks = listOf(
            EditorChunk.Text(
                offset = 0L,
                content = "Text",
                size = 4L,
                lineCount = 1,
                lineEnding = LineEnding.LF,
                isDirty = true
            ),
            EditorChunk.Binary(
                offset = 10L,
                content = byteArrayOf(0x01),
                size = 1L,
                isDirty = false
            )
        )

        // When: Access common properties
        val offsets = chunks.map { it.offset }
        val sizes = chunks.map { it.size }
        val dirtyFlags = chunks.map { it.isDirty }

        // Then: Common properties accessible
        offsets shouldBe listOf(0L, 10L)
        sizes shouldBe listOf(4L, 1L)
        dirtyFlags shouldBe listOf(true, false)
    }
}
