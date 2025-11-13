package eu.darken.butler.editor.core.mode

import eu.darken.butler.editor.core.engine.ChunkManager
import eu.darken.butler.editor.core.engine.EditorChunk
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
 * Tests for HexMode implementation.
 */
class HexModeTest : BaseTest() {

    private val workspaceId = Workspace.Id()

    private fun createMockDataSource(bytes: ByteArray): EditorDataSource {
        return mockk<EditorDataSource>(relaxed = true).apply {
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

    // ==================== Basic Properties Tests ====================

    @Test
    fun `HexMode has correct type`() {
        // Given: HexMode
        val mode = HexMode()

        // Then: Type is HEX
        mode.type shouldBe EditorModeType.HEX
    }

    @Test
    fun `HexMode has hex-specific capabilities`() {
        // Given: HexMode
        val mode = HexMode()

        // Then: Capabilities match hex mode
        mode.capabilities shouldBe EditorCapabilities(
            canEdit = true,
            canSearch = true,
            canUndo = true,
            canGoToLine = false,      // Hex mode doesn't have lines
            canGoToOffset = true,     // Hex mode uses byte offsets
            canShowLineNumbers = false
        )
    }

    // ==================== Load Chunk Tests ====================

    @Test
    fun `loadChunk returns EditorChunk Binary`() = runTest {
        // Given: HexMode and data source
        val mode = HexMode()
        val bytes = byteArrayOf(0x01, 0x02, 0x03, 0x04)
        val dataSource = createMockDataSource(bytes)

        // When: Load chunk
        val chunk = mode.loadChunk(dataSource, offset = 0L, size = 4L)

        // Then: Returns Binary chunk
        chunk shouldBe instanceOf<EditorChunk.Binary>()
    }

    @Test
    fun `loadChunk preserves all byte values`() = runTest {
        // Given: HexMode and data with all byte values
        val mode = HexMode()
        val bytes = ByteArray(256) { it.toByte() }
        val dataSource = createMockDataSource(bytes)

        // When: Load chunk
        val chunk = mode.loadChunk(dataSource, offset = 0L, size = 256L)

        // Then: All bytes preserved
        chunk as EditorChunk.Binary
        chunk.content.size shouldBe 256
        chunk.content[0] shouldBe 0x00.toByte()
        chunk.content[127] shouldBe 0x7F.toByte()
        chunk.content[128] shouldBe 0x80.toByte()
        chunk.content[255] shouldBe 0xFF.toByte()
    }

    @Test
    fun `loadChunk preserves null bytes`() = runTest {
        // Given: HexMode and data with null bytes
        val mode = HexMode()
        val bytes = byteArrayOf(0x00, 0x00, 0x00, 0x00)
        val dataSource = createMockDataSource(bytes)

        // When: Load chunk
        val chunk = mode.loadChunk(dataSource, offset = 0L, size = 4L)

        // Then: Null bytes preserved
        chunk as EditorChunk.Binary
        chunk.content.all { it == 0x00.toByte() } shouldBe true
        chunk.content.size shouldBe 4
    }

    @Test
    fun `loadChunk handles empty chunk`() = runTest {
        // Given: HexMode and empty data
        val mode = HexMode()
        val bytes = byteArrayOf()
        val dataSource = createMockDataSource(bytes)

        // When: Load empty chunk
        val chunk = mode.loadChunk(dataSource, offset = 0L, size = 0L)

        // Then: Empty chunk returned
        chunk as EditorChunk.Binary
        chunk.content.size shouldBe 0
        chunk.size shouldBe 0L
    }

    @Test
    fun `loadChunk at specific offset`() = runTest {
        // Given: HexMode and data
        val mode = HexMode()
        val bytes = ByteArray(200) { it.toByte() }
        val dataSource = createMockDataSource(bytes)

        // When: Load chunk at offset 100
        val chunk = mode.loadChunk(dataSource, offset = 100L, size = 50L)

        // Then: Correct bytes loaded
        chunk as EditorChunk.Binary
        chunk.offset shouldBe 100L
        chunk.size shouldBe 50L
        chunk.content[0] shouldBe 100.toByte()
        chunk.content[49] shouldBe 149.toByte()
    }

    // ==================== Chunk Properties Tests ====================

    @Test
    fun `loadChunk sets correct offset and size`() = runTest {
        // Given: HexMode and binary data with enough bytes for offset 100
        val mode = HexMode()
        val bytes = ByteArray(200) { it.toByte() }
        val dataSource = createMockDataSource(bytes)

        // When: Load chunk at offset 100
        val chunk = mode.loadChunk(dataSource, offset = 100L, size = 3L)

        // Then: Properties set correctly
        chunk.offset shouldBe 100L
        chunk.size shouldBe 3L
        chunk.isDirty shouldBe false
    }

    @Test
    fun `loadChunk creates non-dirty chunk`() = runTest {
        // Given: HexMode
        val mode = HexMode()
        val dataSource = createMockDataSource(byteArrayOf(0x01, 0x02))

        // When: Load chunk
        val chunk = mode.loadChunk(dataSource, offset = 0L, size = 2L)

        // Then: Not dirty (freshly loaded)
        chunk.isDirty shouldBe false
    }

    // ==================== Save Chunk Tests ====================

    // TODO Phase 3.1: Add these tests back when EditorDataSource.writeChunk() is added
    // @Test
    // fun `saveChunk writes Binary chunk`() = runTest { ... }

    @Test
    fun `saveChunk throws on Text chunk`() = runTest {
        // Given: HexMode and Text chunk (wrong type)
        val mode = HexMode()
        val textChunk = EditorChunk.Text(
            offset = 0L,
            content = "Hello",
            size = 5L,
            lineCount = 1,
            lineEnding = eu.darken.butler.editor.core.engine.LineEnding.LF,
            isDirty = true
        )
        val dataSource = mockk<EditorDataSource>(relaxed = true)

        // When: Try to save text chunk
        val result = runCatching {
            mode.saveChunk(dataSource, textChunk)
        }

        // Then: Should throw IllegalArgumentException
        result.isFailure shouldBe true
        result.exceptionOrNull() shouldBe instanceOf<IllegalArgumentException>()
    }

    // ==================== Buffer Creation Tests ====================

    // TODO Phase 2.4: Re-enable when ChunkedBinaryBuffer is integrated with EditorBuffer
    // @Test
    // fun `createBuffer returns ChunkedBinaryBuffer`() {
    //     val mode = HexMode()
    //     val chunkManager = mockk<ChunkManager>(relaxed = true)
    //     val buffer = mode.createBuffer(chunkManager)
    //     buffer shouldBe instanceOf<ChunkedBinaryBuffer>()
    // }

    // ==================== Binary Pattern Tests ====================

    @Test
    fun `loadChunk preserves file signatures`() = runTest {
        // Given: HexMode and data with file signatures
        val mode = HexMode()
        val bytes = byteArrayOf(
            0x50, 0x4B, 0x03, 0x04,  // ZIP file signature
            0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(),  // JPEG signature
            0x89.toByte(), 0x50, 0x4E, 0x47  // PNG signature
        )
        val dataSource = createMockDataSource(bytes)

        // When: Load chunk
        val chunk = mode.loadChunk(dataSource, offset = 0L, size = bytes.size.toLong())

        // Then: All signatures preserved
        chunk as EditorChunk.Binary
        chunk.content shouldBe bytes
    }
}
