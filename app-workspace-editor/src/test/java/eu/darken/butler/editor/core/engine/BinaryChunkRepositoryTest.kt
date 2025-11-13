package eu.darken.butler.editor.core.engine

import eu.darken.butler.editor.core.sources.EditorDataSource
import eu.darken.butler.workspace.core.Workspace
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.instanceOf
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import testhelpers.BaseTest

/**
 * Tests for BinaryChunkRepository - loads binary chunks without UTF-8 conversion.
 */
class BinaryChunkRepositoryTest : BaseTest() {

    private val workspaceId = Workspace.Id()
    private val chunkSize = 64L

    private fun createMockDataSource(bytes: ByteArray): EditorDataSource {
        return mockk<EditorDataSource>(relaxed = true).apply {
            // Mock readChunk to return ByteArray
            coEvery { readChunk(any(), any()) } returns bytes
        }
    }

    // ==================== Basic Load Tests ====================

    @Test
    fun `loadChunk reads raw bytes`() = runTest {
        // Given: Repository and binary data
        val bytes = byteArrayOf(0x01, 0x02, 0x03, 0x04)
        val dataSource = createMockDataSource(bytes)
        val repository = BinaryChunkRepository(workspaceId, dataSource, chunkSize)

        // When: Load chunk
        val chunkId = EditorChunk.ChunkId.generate()
        val boundary = ChunkBoundary(startOffset = 0L, endOffset = 4L, lineCount = 0)
        val chunk = repository.loadChunk(chunkId, boundary)

        // Then: Returns Binary chunk
        chunk shouldBe instanceOf<EditorChunk.Binary>()
        chunk as EditorChunk.Binary
        chunk.content shouldBe bytes
    }

    @Test
    fun `loadChunk preserves binary nulls`() = runTest {
        // Given: Data with null bytes
        val bytes = byteArrayOf(0x00, 0x00, 0x00, 0x00)
        val dataSource = createMockDataSource(bytes)
        val repository = BinaryChunkRepository(workspaceId, dataSource, chunkSize)

        // When: Load chunk
        val chunkId = EditorChunk.ChunkId.generate()
        val boundary = ChunkBoundary(startOffset = 0L, endOffset = 4L, lineCount = 0)
        val chunk = repository.loadChunk(chunkId, boundary)

        // Then: Null bytes preserved
        chunk as EditorChunk.Binary
        chunk.content.all { it == 0x00.toByte() } shouldBe true
        chunk.content.size shouldBe 4
    }

    @Test
    fun `loadChunk handles all byte values 0x00-0xFF`() = runTest {
        // Given: All possible byte values
        val bytes = ByteArray(256) { it.toByte() }
        val dataSource = createMockDataSource(bytes)
        val repository = BinaryChunkRepository(workspaceId, dataSource, chunkSize)

        // When: Load chunk
        val chunkId = EditorChunk.ChunkId.generate()
        val boundary = ChunkBoundary(startOffset = 0L, endOffset = 256L, lineCount = 0)
        val chunk = repository.loadChunk(chunkId, boundary)

        // Then: All bytes preserved correctly
        chunk as EditorChunk.Binary
        chunk.content.size shouldBe 256
        chunk.content[0] shouldBe 0x00.toByte()
        chunk.content[127] shouldBe 0x7F.toByte()
        chunk.content[128] shouldBe 0x80.toByte()
        chunk.content[255] shouldBe 0xFF.toByte()
    }

    @Test
    fun `loadChunk at exact chunk boundary`() = runTest {
        // Given: Data at chunk boundary (64 bytes)
        val bytes = ByteArray(64) { it.toByte() }
        val dataSource = createMockDataSource(bytes)
        val repository = BinaryChunkRepository(workspaceId, dataSource, chunkSize)

        // When: Load chunk at boundary
        val chunkId = EditorChunk.ChunkId.generate()
        val boundary = ChunkBoundary(startOffset = 0L, endOffset = 64L, lineCount = 0)
        val chunk = repository.loadChunk(chunkId, boundary)

        // Then: Full chunk loaded
        chunk as EditorChunk.Binary
        chunk.content.size shouldBe 64
        chunk.size shouldBe 64L
    }

    @Test
    fun `loadChunk with offset preserves correct bytes`() = runTest {
        // Given: Repository with data at offset
        val allBytes = ByteArray(200) { it.toByte() }
        val dataSource = mockk<EditorDataSource>(relaxed = true).apply {
            coEvery { readChunk(100L, 50L) } returns allBytes.copyOfRange(100, 150)
        }
        val repository = BinaryChunkRepository(workspaceId, dataSource, chunkSize)

        // When: Load chunk at offset 100
        val chunkId = EditorChunk.ChunkId.generate()
        val boundary = ChunkBoundary(startOffset = 100L, endOffset = 150L, lineCount = 0)
        val chunk = repository.loadChunk(chunkId, boundary)

        // Then: Correct bytes loaded
        chunk as EditorChunk.Binary
        chunk.content[0] shouldBe 100.toByte()
        chunk.content[49] shouldBe 149.toByte()
    }

    // ==================== Empty and Edge Cases ====================

    @Test
    fun `loadChunk handles empty chunk`() = runTest {
        // Given: Empty data
        val bytes = byteArrayOf()
        val dataSource = createMockDataSource(bytes)
        val repository = BinaryChunkRepository(workspaceId, dataSource, chunkSize)

        // When: Load empty chunk
        val chunkId = EditorChunk.ChunkId.generate()
        val boundary = ChunkBoundary(startOffset = 0L, endOffset = 0L, lineCount = 0)
        val chunk = repository.loadChunk(chunkId, boundary)

        // Then: Empty chunk returned
        chunk as EditorChunk.Binary
        chunk.content.size shouldBe 0
        chunk.size shouldBe 0L
    }

    @Test
    fun `loadChunk single byte`() = runTest {
        // Given: Single byte
        val bytes = byteArrayOf(0x42)
        val dataSource = createMockDataSource(bytes)
        val repository = BinaryChunkRepository(workspaceId, dataSource, chunkSize)

        // When: Load chunk
        val chunkId = EditorChunk.ChunkId.generate()
        val boundary = ChunkBoundary(startOffset = 0L, endOffset = 1L, lineCount = 0)
        val chunk = repository.loadChunk(chunkId, boundary)

        // Then: Single byte preserved
        chunk as EditorChunk.Binary
        chunk.content.size shouldBe 1
        chunk.content[0] shouldBe 0x42.toByte()
    }

    // ==================== Chunk Properties Tests ====================

    @Test
    fun `loadChunk sets correct offset and size`() = runTest {
        // Given: Binary data
        val bytes = byteArrayOf(0x01, 0x02, 0x03)
        val dataSource = createMockDataSource(bytes)
        val repository = BinaryChunkRepository(workspaceId, dataSource, chunkSize)

        // When: Load chunk at offset 100
        val chunkId = EditorChunk.ChunkId.generate()
        val boundary = ChunkBoundary(startOffset = 100L, endOffset = 103L, lineCount = 0)
        val chunk = repository.loadChunk(chunkId, boundary)

        // Then: Properties set correctly
        chunk.offset shouldBe 100L
        chunk.size shouldBe 3L
        chunk.isDirty shouldBe false
    }

    @Test
    fun `loadChunk creates chunk with correct ID`() = runTest {
        // Given: Repository
        val bytes = byteArrayOf(0x01)
        val dataSource = createMockDataSource(bytes)
        val repository = BinaryChunkRepository(workspaceId, dataSource, chunkSize)

        // When: Load chunk with specific ID
        val chunkId = EditorChunk.ChunkId.generate()
        val boundary = ChunkBoundary(startOffset = 0L, endOffset = 1L, lineCount = 0)
        val chunk = repository.loadChunk(chunkId, boundary)

        // Then: Chunk has correct ID
        chunk as EditorChunk.Binary
        chunk.id shouldBe chunkId
    }

    // ==================== Binary Pattern Tests ====================

    @Test
    fun `loadChunk preserves byte patterns`() = runTest {
        // Given: Specific byte patterns (magic numbers, headers, etc.)
        val bytes = byteArrayOf(
            0x50, 0x4B, 0x03, 0x04,  // ZIP file signature
            0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(),  // JPEG signature
            0x89.toByte(), 0x50, 0x4E, 0x47  // PNG signature
        )
        val dataSource = createMockDataSource(bytes)
        val repository = BinaryChunkRepository(workspaceId, dataSource, chunkSize)

        // When: Load chunk
        val chunkId = EditorChunk.ChunkId.generate()
        val boundary = ChunkBoundary(startOffset = 0L, endOffset = bytes.size.toLong(), lineCount = 0)
        val chunk = repository.loadChunk(chunkId, boundary)

        // Then: All patterns preserved
        chunk as EditorChunk.Binary
        chunk.content shouldBe bytes
    }
}
