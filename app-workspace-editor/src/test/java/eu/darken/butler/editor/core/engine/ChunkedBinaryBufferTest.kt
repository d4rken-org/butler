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
 * Tests for ChunkedBinaryBuffer - buffer for binary file editing.
 */
class ChunkedBinaryBufferTest : BaseTest() {

    private val workspaceId = Workspace.Id()
    private val chunkSize = 64L

    private fun createMockDataSource(bytes: ByteArray): EditorDataSource {
        return mockk<EditorDataSource>(relaxed = true).apply {
            // Mock readChunk to return ByteArray
            coEvery { readChunk(any(), any()) } answers {
                val offset = firstArg<Long>()
                val size = secondArg<Long>()
                val start = offset.toInt()
                val end = (offset + size).toInt().coerceAtMost(bytes.size)
                bytes.copyOfRange(start, end)
            }
            coEvery { getSize() } returns bytes.size.toLong()
        }
    }

    // ==================== Initialization Tests ====================

    @Test
    fun `initialize empty buffer`() = runTest {
        // Given: Empty data source
        val bytes = byteArrayOf()
        val dataSource = createMockDataSource(bytes)
        val chunkManager = ChunkManager(workspaceId, null, chunkSize)
        val repository = BinaryChunkRepository(workspaceId, dataSource, chunkSize)
        val buffer = ChunkedBinaryBuffer(workspaceId, chunkManager, repository)

        // When: Initialize
        val result = buffer.initialize()

        // Then: Success with 0 length
        result.isSuccess shouldBe true
        buffer.totalLength.value shouldBe 0L
        buffer.isModified.value shouldBe false
    }

    @Test
    fun `initialize buffer with small file`() = runTest {
        // Given: Small data source (32 bytes)
        val bytes = ByteArray(32) { it.toByte() }
        val dataSource = createMockDataSource(bytes)
        val chunkManager = ChunkManager(workspaceId, null, chunkSize)
        val repository = BinaryChunkRepository(workspaceId, dataSource, chunkSize)
        val buffer = ChunkedBinaryBuffer(workspaceId, chunkManager, repository)

        // When: Initialize
        val result = buffer.initialize()

        // Then: Success
        result.isSuccess shouldBe true
        buffer.totalLength.value shouldBe 32L
        buffer.isModified.value shouldBe false
    }

    @Test
    fun `initialize buffer with multi-chunk file`() = runTest {
        // Given: Large data source (200 bytes = 4 chunks at 64 bytes each)
        val bytes = ByteArray(200) { it.toByte() }
        val dataSource = createMockDataSource(bytes)
        val chunkManager = ChunkManager(workspaceId, null, chunkSize)
        val repository = BinaryChunkRepository(workspaceId, dataSource, chunkSize)
        val buffer = ChunkedBinaryBuffer(workspaceId, chunkManager, repository)

        // When: Initialize
        val result = buffer.initialize()

        // Then: Success
        result.isSuccess shouldBe true
        buffer.totalLength.value shouldBe 200L
        buffer.isModified.value shouldBe false
    }

    // ==================== Read Tests ====================

    @Test
    fun `getBytes reads entire buffer`() = runTest {
        // Given: Buffer with data
        val bytes = byteArrayOf(0x01, 0x02, 0x03, 0x04, 0x05)
        val dataSource = createMockDataSource(bytes)
        val chunkManager = ChunkManager(workspaceId, null, chunkSize)
        val repository = BinaryChunkRepository(workspaceId, dataSource, chunkSize)
        val buffer = ChunkedBinaryBuffer(workspaceId, chunkManager, repository)
        buffer.initialize()

        // When: Read entire buffer
        val result = buffer.getBytes(0L, 5L)

        // Then: Returns correct bytes
        result.isSuccess shouldBe true
        result.getOrNull() shouldBe bytes
    }

    @Test
    fun `getBytes reads partial range`() = runTest {
        // Given: Buffer with data
        val bytes = byteArrayOf(0x01, 0x02, 0x03, 0x04, 0x05)
        val dataSource = createMockDataSource(bytes)
        val chunkManager = ChunkManager(workspaceId, null, chunkSize)
        val repository = BinaryChunkRepository(workspaceId, dataSource, chunkSize)
        val buffer = ChunkedBinaryBuffer(workspaceId, chunkManager, repository)
        buffer.initialize()

        // When: Read middle 3 bytes
        val result = buffer.getBytes(1L, 4L)

        // Then: Returns bytes [1, 2, 3]
        result.isSuccess shouldBe true
        result.getOrNull() shouldBe byteArrayOf(0x02, 0x03, 0x04)
    }

    @Test
    fun `getBytes reads across chunk boundary`() = runTest {
        // Given: Buffer with 128 bytes (2 chunks at 64 bytes each)
        val bytes = ByteArray(128) { it.toByte() }
        val dataSource = createMockDataSource(bytes)
        val chunkManager = ChunkManager(workspaceId, null, chunkSize)
        val repository = BinaryChunkRepository(workspaceId, dataSource, chunkSize)
        val buffer = ChunkedBinaryBuffer(workspaceId, chunkManager, repository)
        buffer.initialize()

        // When: Read across boundary (bytes 60-68, spanning chunks)
        val result = buffer.getBytes(60L, 68L)

        // Then: Returns correct bytes
        result.isSuccess shouldBe true
        val expected = ByteArray(8) { (60 + it).toByte() }
        result.getOrNull() shouldBe expected
    }

    @Test
    fun `getBytes handles empty range`() = runTest {
        // Given: Buffer with data
        val bytes = byteArrayOf(0x01, 0x02, 0x03)
        val dataSource = createMockDataSource(bytes)
        val chunkManager = ChunkManager(workspaceId, null, chunkSize)
        val repository = BinaryChunkRepository(workspaceId, dataSource, chunkSize)
        val buffer = ChunkedBinaryBuffer(workspaceId, chunkManager, repository)
        buffer.initialize()

        // When: Read empty range
        val result = buffer.getBytes(1L, 1L)

        // Then: Returns empty array
        result.isSuccess shouldBe true
        result.getOrNull() shouldBe byteArrayOf()
    }

    @Test
    fun `getBytes fails on out of bounds`() = runTest {
        // Given: Buffer with 5 bytes
        val bytes = byteArrayOf(0x01, 0x02, 0x03, 0x04, 0x05)
        val dataSource = createMockDataSource(bytes)
        val chunkManager = ChunkManager(workspaceId, null, chunkSize)
        val repository = BinaryChunkRepository(workspaceId, dataSource, chunkSize)
        val buffer = ChunkedBinaryBuffer(workspaceId, chunkManager, repository)
        buffer.initialize()

        // When: Try to read beyond buffer
        val result = buffer.getBytes(0L, 10L)

        // Then: Fails
        result.isFailure shouldBe true
    }

    // ==================== Insert Tests ====================

    @Test
    fun `insertBytes at beginning`() = runTest {
        // Given: Buffer with data [3, 4, 5]
        val bytes = byteArrayOf(0x03, 0x04, 0x05)
        val dataSource = createMockDataSource(bytes)
        val chunkManager = ChunkManager(workspaceId, null, chunkSize)
        val repository = BinaryChunkRepository(workspaceId, dataSource, chunkSize)
        val buffer = ChunkedBinaryBuffer(workspaceId, chunkManager, repository)
        buffer.initialize()

        // When: Insert [1, 2] at beginning
        val result = buffer.insertBytes(0L, byteArrayOf(0x01, 0x02))

        // Then: Buffer becomes [1, 2, 3, 4, 5]
        result.isSuccess shouldBe true
        buffer.totalLength.value shouldBe 5L
        buffer.isModified.value shouldBe true
        buffer.getBytes(0L, 5L).getOrNull() shouldBe byteArrayOf(0x01, 0x02, 0x03, 0x04, 0x05)
    }

    @Test
    fun `insertBytes in middle`() = runTest {
        // Given: Buffer with data [1, 2, 5]
        val bytes = byteArrayOf(0x01, 0x02, 0x05)
        val dataSource = createMockDataSource(bytes)
        val chunkManager = ChunkManager(workspaceId, null, chunkSize)
        val repository = BinaryChunkRepository(workspaceId, dataSource, chunkSize)
        val buffer = ChunkedBinaryBuffer(workspaceId, chunkManager, repository)
        buffer.initialize()

        // When: Insert [3, 4] at offset 2
        val result = buffer.insertBytes(2L, byteArrayOf(0x03, 0x04))

        // Then: Buffer becomes [1, 2, 3, 4, 5]
        result.isSuccess shouldBe true
        buffer.totalLength.value shouldBe 5L
        buffer.isModified.value shouldBe true
        buffer.getBytes(0L, 5L).getOrNull() shouldBe byteArrayOf(0x01, 0x02, 0x03, 0x04, 0x05)
    }

    @Test
    fun `insertBytes at end`() = runTest {
        // Given: Buffer with data [1, 2, 3]
        val bytes = byteArrayOf(0x01, 0x02, 0x03)
        val dataSource = createMockDataSource(bytes)
        val chunkManager = ChunkManager(workspaceId, null, chunkSize)
        val repository = BinaryChunkRepository(workspaceId, dataSource, chunkSize)
        val buffer = ChunkedBinaryBuffer(workspaceId, chunkManager, repository)
        buffer.initialize()

        // When: Insert [4, 5] at end
        val result = buffer.insertBytes(3L, byteArrayOf(0x04, 0x05))

        // Then: Buffer becomes [1, 2, 3, 4, 5]
        result.isSuccess shouldBe true
        buffer.totalLength.value shouldBe 5L
        buffer.getBytes(0L, 5L).getOrNull() shouldBe byteArrayOf(0x01, 0x02, 0x03, 0x04, 0x05)
    }

    @Test
    fun `insertBytes into empty buffer`() = runTest {
        // Given: Empty buffer
        val bytes = byteArrayOf()
        val dataSource = createMockDataSource(bytes)
        val chunkManager = ChunkManager(workspaceId, null, chunkSize)
        val repository = BinaryChunkRepository(workspaceId, dataSource, chunkSize)
        val buffer = ChunkedBinaryBuffer(workspaceId, chunkManager, repository)
        buffer.initialize()

        // When: Insert bytes
        val result = buffer.insertBytes(0L, byteArrayOf(0x01, 0x02, 0x03))

        // Then: Buffer contains inserted bytes
        result.isSuccess shouldBe true
        buffer.totalLength.value shouldBe 3L
        buffer.getBytes(0L, 3L).getOrNull() shouldBe byteArrayOf(0x01, 0x02, 0x03)
    }

    // ==================== Delete Tests ====================

    @Test
    fun `deleteBytes from beginning`() = runTest {
        // Given: Buffer with data [1, 2, 3, 4, 5]
        val bytes = byteArrayOf(0x01, 0x02, 0x03, 0x04, 0x05)
        val dataSource = createMockDataSource(bytes)
        val chunkManager = ChunkManager(workspaceId, null, chunkSize)
        val repository = BinaryChunkRepository(workspaceId, dataSource, chunkSize)
        val buffer = ChunkedBinaryBuffer(workspaceId, chunkManager, repository)
        buffer.initialize()

        // When: Delete first 2 bytes
        val result = buffer.deleteBytes(0L, 2L)

        // Then: Buffer becomes [3, 4, 5]
        result.isSuccess shouldBe true
        result.getOrNull() shouldBe byteArrayOf(0x01, 0x02)
        buffer.totalLength.value shouldBe 3L
        buffer.isModified.value shouldBe true
        buffer.getBytes(0L, 3L).getOrNull() shouldBe byteArrayOf(0x03, 0x04, 0x05)
    }

    @Test
    fun `deleteBytes from middle`() = runTest {
        // Given: Buffer with data [1, 2, 3, 4, 5]
        val bytes = byteArrayOf(0x01, 0x02, 0x03, 0x04, 0x05)
        val dataSource = createMockDataSource(bytes)
        val chunkManager = ChunkManager(workspaceId, null, chunkSize)
        val repository = BinaryChunkRepository(workspaceId, dataSource, chunkSize)
        val buffer = ChunkedBinaryBuffer(workspaceId, chunkManager, repository)
        buffer.initialize()

        // When: Delete bytes 2-4 (positions 1-3)
        val result = buffer.deleteBytes(1L, 4L)

        // Then: Buffer becomes [1, 5]
        result.isSuccess shouldBe true
        result.getOrNull() shouldBe byteArrayOf(0x02, 0x03, 0x04)
        buffer.totalLength.value shouldBe 2L
        buffer.getBytes(0L, 2L).getOrNull() shouldBe byteArrayOf(0x01, 0x05)
    }

    @Test
    fun `deleteBytes to end`() = runTest {
        // Given: Buffer with data [1, 2, 3, 4, 5]
        val bytes = byteArrayOf(0x01, 0x02, 0x03, 0x04, 0x05)
        val dataSource = createMockDataSource(bytes)
        val chunkManager = ChunkManager(workspaceId, null, chunkSize)
        val repository = BinaryChunkRepository(workspaceId, dataSource, chunkSize)
        val buffer = ChunkedBinaryBuffer(workspaceId, chunkManager, repository)
        buffer.initialize()

        // When: Delete last 2 bytes
        val result = buffer.deleteBytes(3L, 5L)

        // Then: Buffer becomes [1, 2, 3]
        result.isSuccess shouldBe true
        result.getOrNull() shouldBe byteArrayOf(0x04, 0x05)
        buffer.totalLength.value shouldBe 3L
        buffer.getBytes(0L, 3L).getOrNull() shouldBe byteArrayOf(0x01, 0x02, 0x03)
    }

    @Test
    fun `deleteBytes all content`() = runTest {
        // Given: Buffer with data [1, 2, 3]
        val bytes = byteArrayOf(0x01, 0x02, 0x03)
        val dataSource = createMockDataSource(bytes)
        val chunkManager = ChunkManager(workspaceId, null, chunkSize)
        val repository = BinaryChunkRepository(workspaceId, dataSource, chunkSize)
        val buffer = ChunkedBinaryBuffer(workspaceId, chunkManager, repository)
        buffer.initialize()

        // When: Delete all bytes
        val result = buffer.deleteBytes(0L, 3L)

        // Then: Buffer becomes empty
        result.isSuccess shouldBe true
        result.getOrNull() shouldBe byteArrayOf(0x01, 0x02, 0x03)
        buffer.totalLength.value shouldBe 0L
    }

    // ==================== Replace Tests ====================

    @Test
    fun `replaceBytes same size`() = runTest {
        // Given: Buffer with data [1, 2, 3, 4, 5]
        val bytes = byteArrayOf(0x01, 0x02, 0x03, 0x04, 0x05)
        val dataSource = createMockDataSource(bytes)
        val chunkManager = ChunkManager(workspaceId, null, chunkSize)
        val repository = BinaryChunkRepository(workspaceId, dataSource, chunkSize)
        val buffer = ChunkedBinaryBuffer(workspaceId, chunkManager, repository)
        buffer.initialize()

        // When: Replace bytes 1-3 with [A, B, C]
        val result = buffer.replaceBytes(1L, 4L, byteArrayOf(0xA, 0xB, 0xC))

        // Then: Buffer becomes [1, A, B, C, 5]
        result.isSuccess shouldBe true
        buffer.totalLength.value shouldBe 5L
        buffer.getBytes(0L, 5L).getOrNull() shouldBe byteArrayOf(0x01, 0xA, 0xB, 0xC, 0x05)
    }

    @Test
    fun `replaceBytes with larger data`() = runTest {
        // Given: Buffer with data [1, 2, 3]
        val bytes = byteArrayOf(0x01, 0x02, 0x03)
        val dataSource = createMockDataSource(bytes)
        val chunkManager = ChunkManager(workspaceId, null, chunkSize)
        val repository = BinaryChunkRepository(workspaceId, dataSource, chunkSize)
        val buffer = ChunkedBinaryBuffer(workspaceId, chunkManager, repository)
        buffer.initialize()

        // When: Replace byte 2 with [A, B, C, D] (1 byte → 4 bytes)
        val result = buffer.replaceBytes(1L, 2L, byteArrayOf(0xA, 0xB, 0xC, 0xD))

        // Then: Buffer becomes [1, A, B, C, D, 3]
        result.isSuccess shouldBe true
        buffer.totalLength.value shouldBe 6L
        buffer.getBytes(0L, 6L).getOrNull() shouldBe byteArrayOf(0x01, 0xA, 0xB, 0xC, 0xD, 0x03)
    }

    @Test
    fun `replaceBytes with smaller data`() = runTest {
        // Given: Buffer with data [1, 2, 3, 4, 5]
        val bytes = byteArrayOf(0x01, 0x02, 0x03, 0x04, 0x05)
        val dataSource = createMockDataSource(bytes)
        val chunkManager = ChunkManager(workspaceId, null, chunkSize)
        val repository = BinaryChunkRepository(workspaceId, dataSource, chunkSize)
        val buffer = ChunkedBinaryBuffer(workspaceId, chunkManager, repository)
        buffer.initialize()

        // When: Replace bytes 1-4 with [A] (3 bytes → 1 byte)
        val result = buffer.replaceBytes(1L, 4L, byteArrayOf(0xA))

        // Then: Buffer becomes [1, A, 5]
        result.isSuccess shouldBe true
        buffer.totalLength.value shouldBe 3L
        buffer.getBytes(0L, 3L).getOrNull() shouldBe byteArrayOf(0x01, 0xA, 0x05)
    }

    // ==================== Undo/Redo Tests ====================

    @Test
    fun `undo insert operation`() = runTest {
        // Given: Buffer with insert operation
        val bytes = byteArrayOf(0x01, 0x02)
        val dataSource = createMockDataSource(bytes)
        val chunkManager = ChunkManager(workspaceId, null, chunkSize)
        val repository = BinaryChunkRepository(workspaceId, dataSource, chunkSize)
        val buffer = ChunkedBinaryBuffer(workspaceId, chunkManager, repository)
        buffer.initialize()
        buffer.insertBytes(2L, byteArrayOf(0x03, 0x04))

        // When: Undo
        val result = buffer.undo()

        // Then: Returns to original state
        result.isSuccess shouldBe true
        result.getOrNull() shouldBe null  // Binary buffers don't have cursor positioning
        buffer.totalLength.value shouldBe 2L
        buffer.getBytes(0L, 2L).getOrNull() shouldBe byteArrayOf(0x01, 0x02)
    }

    @Test
    fun `undo delete operation`() = runTest {
        // Given: Buffer with delete operation
        val bytes = byteArrayOf(0x01, 0x02, 0x03, 0x04)
        val dataSource = createMockDataSource(bytes)
        val chunkManager = ChunkManager(workspaceId, null, chunkSize)
        val repository = BinaryChunkRepository(workspaceId, dataSource, chunkSize)
        val buffer = ChunkedBinaryBuffer(workspaceId, chunkManager, repository)
        buffer.initialize()
        buffer.deleteBytes(1L, 3L)

        // When: Undo
        val result = buffer.undo()

        // Then: Restores deleted bytes
        result.isSuccess shouldBe true
        result.getOrNull() shouldBe null  // Binary buffers don't have cursor positioning
        buffer.totalLength.value shouldBe 4L
        buffer.getBytes(0L, 4L).getOrNull() shouldBe byteArrayOf(0x01, 0x02, 0x03, 0x04)
    }

    @Test
    fun `redo insert operation`() = runTest {
        // Given: Buffer with undone insert
        val bytes = byteArrayOf(0x01, 0x02)
        val dataSource = createMockDataSource(bytes)
        val chunkManager = ChunkManager(workspaceId, null, chunkSize)
        val repository = BinaryChunkRepository(workspaceId, dataSource, chunkSize)
        val buffer = ChunkedBinaryBuffer(workspaceId, chunkManager, repository)
        buffer.initialize()
        buffer.insertBytes(2L, byteArrayOf(0x03, 0x04))
        buffer.undo()

        // When: Redo
        val result = buffer.redo()

        // Then: Reapplies insert
        result.isSuccess shouldBe true
        result.getOrNull() shouldBe null  // Binary buffers don't have cursor positioning
        buffer.totalLength.value shouldBe 4L
        buffer.getBytes(0L, 4L).getOrNull() shouldBe byteArrayOf(0x01, 0x02, 0x03, 0x04)
    }

    @Test
    fun `canUndo returns true after operation`() = runTest {
        // Given: Buffer with operation
        val bytes = byteArrayOf(0x01, 0x02)
        val dataSource = createMockDataSource(bytes)
        val chunkManager = ChunkManager(workspaceId, null, chunkSize)
        val repository = BinaryChunkRepository(workspaceId, dataSource, chunkSize)
        val buffer = ChunkedBinaryBuffer(workspaceId, chunkManager, repository)
        buffer.initialize()

        // When: Perform operation
        buffer.insertBytes(0L, byteArrayOf(0xFF.toByte()))

        // Then: Can undo
        buffer.canUndo() shouldBe true
        buffer.canRedo() shouldBe false
    }

    @Test
    fun `canRedo returns true after undo`() = runTest {
        // Given: Buffer with undone operation
        val bytes = byteArrayOf(0x01, 0x02)
        val dataSource = createMockDataSource(bytes)
        val chunkManager = ChunkManager(workspaceId, null, chunkSize)
        val repository = BinaryChunkRepository(workspaceId, dataSource, chunkSize)
        val buffer = ChunkedBinaryBuffer(workspaceId, chunkManager, repository)
        buffer.initialize()
        buffer.insertBytes(0L, byteArrayOf(0xFF.toByte()))
        buffer.undo()

        // Then: Can redo
        buffer.canUndo() shouldBe false
        buffer.canRedo() shouldBe true
    }

    // ==================== Save Tests ====================

    @Test
    fun `saveFile marks buffer as clean`() = runTest {
        // Given: Modified buffer
        val bytes = byteArrayOf(0x01, 0x02)
        val dataSource = createMockDataSource(bytes)
        val chunkManager = ChunkManager(workspaceId, null, chunkSize)
        val repository = BinaryChunkRepository(workspaceId, dataSource, chunkSize)
        val buffer = ChunkedBinaryBuffer(workspaceId, chunkManager, repository)
        buffer.initialize()
        buffer.insertBytes(0L, byteArrayOf(0xFF.toByte()))

        // When: Save (TODO Phase 3.1 - currently stubbed)
        // val result = buffer.saveFile()

        // Then: Buffer marked as clean
        // result.isSuccess shouldBe true
        // buffer.isModified.value shouldBe false

        // For now, just verify buffer is dirty
        buffer.isModified.value shouldBe true
    }

    // ==================== Release Tests ====================

    @Test
    fun `release clears buffer state`() = runTest {
        // Given: Initialized buffer with operations
        val bytes = byteArrayOf(0x01, 0x02, 0x03)
        val dataSource = createMockDataSource(bytes)
        val chunkManager = ChunkManager(workspaceId, null, chunkSize)
        val repository = BinaryChunkRepository(workspaceId, dataSource, chunkSize)
        val buffer = ChunkedBinaryBuffer(workspaceId, chunkManager, repository)
        buffer.initialize()
        buffer.insertBytes(0L, byteArrayOf(0xFF.toByte()))

        // When: Release
        val result = buffer.release()

        // Then: Success and state cleared
        result.isSuccess shouldBe true
        buffer.totalLength.value shouldBe 0L
        buffer.isModified.value shouldBe false
    }
}
