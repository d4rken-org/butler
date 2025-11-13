package eu.darken.butler.editor.core.mode

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import eu.darken.butler.editor.core.engine.ChunkManager
import eu.darken.butler.editor.core.engine.EditorBuffer
import eu.darken.butler.editor.core.engine.EditorChunk
import eu.darken.butler.editor.core.engine.LineEnding
import eu.darken.butler.editor.core.sources.EditorDataSource
import io.kotest.matchers.shouldBe
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import testhelpers.BaseTest

/**
 * Tests for EditorMode interface contract.
 * Uses mock implementations to test the interface design.
 */
class EditorModeTest : BaseTest() {

    // ==================== Mock Implementation ====================

    /**
     * Simple mock implementation for testing the interface contract.
     */
    private class MockTextMode : EditorMode {
        override val type = EditorModeType.TEXT

        override val capabilities = EditorCapabilities(
            canEdit = true,
            canSearch = true,
            canUndo = true,
            canGoToLine = true,
            canGoToOffset = false,
            canShowLineNumbers = true
        )

        override suspend fun loadChunk(
            dataSource: EditorDataSource,
            offset: Long,
            size: Long
        ): EditorChunk {
            // Mock: return text chunk
            return EditorChunk.Text(
                offset = offset,
                content = "Mock content",
                size = 12L,
                lineCount = 1,
                lineEnding = LineEnding.LF,
                isDirty = false
            )
        }

        override suspend fun saveChunk(
            dataSource: EditorDataSource,
            chunk: EditorChunk
        ) {
            // Mock: no-op
        }

        override fun createBuffer(chunkManager: ChunkManager): EditorBuffer {
            return mockk<EditorBuffer>(relaxed = true)
        }

        @Composable
        override fun RenderEditor(buffer: EditorBuffer, modifier: Modifier) {
            // Mock: no-op composable
        }
    }

    private class MockHexMode : EditorMode {
        override val type = EditorModeType.HEX

        override val capabilities = EditorCapabilities(
            canEdit = true,
            canSearch = true,
            canUndo = true,
            canGoToLine = false,
            canGoToOffset = true,
            canShowLineNumbers = false
        )

        override suspend fun loadChunk(
            dataSource: EditorDataSource,
            offset: Long,
            size: Long
        ): EditorChunk {
            // Mock: return binary chunk
            return EditorChunk.Binary(
                offset = offset,
                content = byteArrayOf(0x01, 0x02, 0x03),
                size = 3L,
                isDirty = false
            )
        }

        override suspend fun saveChunk(
            dataSource: EditorDataSource,
            chunk: EditorChunk
        ) {
            // Mock: no-op
        }

        override fun createBuffer(chunkManager: ChunkManager): EditorBuffer {
            return mockk<EditorBuffer>(relaxed = true)
        }

        @Composable
        override fun RenderEditor(buffer: EditorBuffer, modifier: Modifier) {
            // Mock: no-op composable
        }
    }

    // ==================== Type Identification Tests ====================

    @Test
    fun `EditorMode exposes mode type`() {
        // Given: Different mode implementations
        val textMode = MockTextMode()
        val hexMode = MockHexMode()

        // Then: Type is accessible
        textMode.type shouldBe EditorModeType.TEXT
        hexMode.type shouldBe EditorModeType.HEX
    }

    // ==================== Capabilities Tests ====================

    @Test
    fun `EditorMode exposes capabilities`() {
        // Given: Mode implementations
        val textMode = MockTextMode()
        val hexMode = MockHexMode()

        // Then: Capabilities are accessible
        textMode.capabilities shouldBe EditorCapabilities(
            canEdit = true,
            canSearch = true,
            canUndo = true,
            canGoToLine = true,
            canGoToOffset = false,
            canShowLineNumbers = true
        )

        hexMode.capabilities shouldBe EditorCapabilities(
            canEdit = true,
            canSearch = true,
            canUndo = true,
            canGoToLine = false,
            canGoToOffset = true,
            canShowLineNumbers = false
        )
    }

    @Test
    fun `Text mode capabilities indicate line-based features`() {
        // Given: Text mode
        val mode = MockTextMode()

        // Then: Line-based capabilities enabled
        mode.capabilities.canGoToLine shouldBe true
        mode.capabilities.canShowLineNumbers shouldBe true
        mode.capabilities.canGoToOffset shouldBe false
    }

    @Test
    fun `Hex mode capabilities indicate offset-based features`() {
        // Given: Hex mode
        val mode = MockHexMode()

        // Then: Offset-based capabilities enabled
        mode.capabilities.canGoToOffset shouldBe true
        mode.capabilities.canGoToLine shouldBe false
        mode.capabilities.canShowLineNumbers shouldBe false
    }

    // ==================== Load Chunk Contract Tests ====================

    @Test
    fun `loadChunk returns chunk with correct offset`() = runTest {
        // Given: Mode and data source
        val mode = MockTextMode()
        val dataSource = mockk<EditorDataSource>(relaxed = true)

        // When: Load chunk at offset 100
        val chunk = mode.loadChunk(dataSource, offset = 100L, size = 64L)

        // Then: Chunk has correct offset
        chunk.offset shouldBe 100L
    }

    @Test
    fun `Text mode loadChunk returns Text chunk`() = runTest {
        // Given: Text mode
        val mode = MockTextMode()
        val dataSource = mockk<EditorDataSource>(relaxed = true)

        // When: Load chunk
        val chunk = mode.loadChunk(dataSource, offset = 0L, size = 64L)

        // Then: Returns Text chunk
        chunk shouldBe io.kotest.matchers.types.instanceOf<EditorChunk.Text>()
    }

    @Test
    fun `Hex mode loadChunk returns Binary chunk`() = runTest {
        // Given: Hex mode
        val mode = MockHexMode()
        val dataSource = mockk<EditorDataSource>(relaxed = true)

        // When: Load chunk
        val chunk = mode.loadChunk(dataSource, offset = 0L, size = 64L)

        // Then: Returns Binary chunk
        chunk shouldBe io.kotest.matchers.types.instanceOf<EditorChunk.Binary>()
    }

    // ==================== Save Chunk Contract Tests ====================

    @Test
    fun `saveChunk accepts chunks for saving`() = runTest {
        // Given: Mode and chunk
        val mode = MockTextMode()
        val dataSource = mockk<EditorDataSource>(relaxed = true)
        val chunk = EditorChunk.Text(
            offset = 0L,
            content = "Test",
            size = 4L,
            lineCount = 1,
            lineEnding = LineEnding.LF,
            isDirty = true
        )

        // When: Save chunk (should not throw)
        mode.saveChunk(dataSource, chunk)

        // Then: No exception thrown
    }

    // ==================== Buffer Creation Tests ====================

    @Test
    fun `createBuffer returns EditorBuffer`() {
        // Given: Mode
        val mode = MockTextMode()
        val chunkManager = mockk<ChunkManager>(relaxed = true)

        // When: Create buffer
        val buffer = mode.createBuffer(chunkManager)

        // Then: Returns buffer
        buffer shouldBe io.kotest.matchers.types.instanceOf<EditorBuffer>()
    }

    // ==================== Mode Equality Tests ====================

    @Test
    fun `Modes with same type are distinguishable`() {
        // Given: Two instances of same mode type
        val mode1 = MockTextMode()
        val mode2 = MockTextMode()

        // Then: Both have same type
        mode1.type shouldBe mode2.type
        mode1.capabilities shouldBe mode2.capabilities
    }

    @Test
    fun `Different mode types have different capabilities`() {
        // Given: Text and Hex modes
        val textMode = MockTextMode()
        val hexMode = MockHexMode()

        // Then: Capabilities differ
        textMode.capabilities.canGoToLine shouldBe true
        hexMode.capabilities.canGoToLine shouldBe false

        textMode.capabilities.canGoToOffset shouldBe false
        hexMode.capabilities.canGoToOffset shouldBe true
    }
}
