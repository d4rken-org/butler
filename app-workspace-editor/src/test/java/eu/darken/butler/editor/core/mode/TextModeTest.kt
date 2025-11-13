package eu.darken.butler.editor.core.mode

import eu.darken.butler.editor.core.engine.ChunkManager
import eu.darken.butler.editor.core.engine.ChunkedTextBuffer
import eu.darken.butler.editor.core.engine.EditorChunk
import eu.darken.butler.editor.core.engine.LineEnding
import eu.darken.butler.editor.core.sources.EditorDataSource
import eu.darken.butler.workspace.core.Workspace
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.instanceOf
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import testhelpers.BaseTest

/**
 * Tests for TextMode implementation.
 */
class TextModeTest : BaseTest() {

    private val workspaceId = Workspace.Id()

    private fun createMockDataSource(content: String): EditorDataSource {
        return mockk<EditorDataSource>(relaxed = true).apply {
            coEvery { readChunk(any(), any()) } returns content.toByteArray()
        }
    }

    // ==================== Basic Properties Tests ====================

    @Test
    fun `TextMode has correct type`() {
        // Given: TextMode
        val mode = TextMode()

        // Then: Type is TEXT
        mode.type shouldBe EditorModeType.TEXT
    }

    @Test
    fun `TextMode has text-specific capabilities`() {
        // Given: TextMode
        val mode = TextMode()

        // Then: Capabilities match text mode
        mode.capabilities shouldBe EditorCapabilities(
            canEdit = true,
            canSearch = true,
            canUndo = true,
            canGoToLine = true,
            canGoToOffset = true,
            canShowLineNumbers = true
        )
    }

    // ==================== Load Chunk Tests ====================

    @Test
    fun `loadChunk returns EditorChunk Text`() = runTest {
        // Given: TextMode and data source
        val mode = TextMode()
        val dataSource = createMockDataSource("Hello World")

        // When: Load chunk
        val chunk = mode.loadChunk(dataSource, offset = 0L, size = 11L)

        // Then: Returns Text chunk
        chunk shouldBe instanceOf<EditorChunk.Text>()
    }

    @Test
    fun `loadChunk decodes UTF-8 correctly`() = runTest {
        // Given: TextMode
        val mode = TextMode()
        val content = "Hello World"
        val dataSource = createMockDataSource(content)

        // When: Load chunk
        val chunk = mode.loadChunk(dataSource, offset = 0L, size = content.length.toLong())

        // Then: Content decoded correctly
        chunk as EditorChunk.Text
        chunk.content shouldBe content
    }

    @Test
    fun `loadChunk handles UTF-8 multibyte characters`() = runTest {
        // Given: Content with emoji and unicode
        val mode = TextMode()
        val content = "Hello 🚀 World 中文"
        val dataSource = createMockDataSource(content)

        // When: Load chunk
        val chunk = mode.loadChunk(dataSource, offset = 0L, size = 100L)

        // Then: Multibyte characters preserved
        chunk as EditorChunk.Text
        chunk.content shouldBe content
        chunk.content.contains("🚀") shouldBe true
        chunk.content.contains("中文") shouldBe true
    }

    @Test
    fun `loadChunk calculates line count for single line`() = runTest {
        // Given: Single line content
        val mode = TextMode()
        val content = "Single line without newline"
        val dataSource = createMockDataSource(content)

        // When: Load chunk
        val chunk = mode.loadChunk(dataSource, offset = 0L, size = content.length.toLong())

        // Then: Line count is 1
        chunk as EditorChunk.Text
        chunk.lineCount shouldBe 1
    }

    @Test
    fun `loadChunk calculates line count for multiple lines`() = runTest {
        // Given: Multi-line content
        val mode = TextMode()
        val content = "Line 1\nLine 2\nLine 3"
        val dataSource = createMockDataSource(content)

        // When: Load chunk
        val chunk = mode.loadChunk(dataSource, offset = 0L, size = content.length.toLong())

        // Then: Line count is 3
        chunk as EditorChunk.Text
        chunk.lineCount shouldBe 3
    }

    @Test
    fun `loadChunk calculates line count with trailing newline`() = runTest {
        // Given: Content with trailing newline
        val mode = TextMode()
        val content = "Line 1\nLine 2\n"
        val dataSource = createMockDataSource(content)

        // When: Load chunk
        val chunk = mode.loadChunk(dataSource, offset = 0L, size = content.length.toLong())

        // Then: Line count is 2 (trailing newline doesn't create empty line)
        chunk as EditorChunk.Text
        chunk.lineCount shouldBe 2
    }

    @Test
    fun `loadChunk handles empty content`() = runTest {
        // Given: Empty content
        val mode = TextMode()
        val dataSource = createMockDataSource("")

        // When: Load chunk
        val chunk = mode.loadChunk(dataSource, offset = 0L, size = 0L)

        // Then: Line count is 0
        chunk as EditorChunk.Text
        chunk.content shouldBe ""
        chunk.lineCount shouldBe 0
        chunk.size shouldBe 0L
    }

    // ==================== Line Ending Detection Tests ====================

    @Test
    fun `loadChunk detects LF line endings`() = runTest {
        // Given: Content with LF endings
        val mode = TextMode()
        val content = "Line 1\nLine 2\nLine 3"
        val dataSource = createMockDataSource(content)

        // When: Load chunk
        val chunk = mode.loadChunk(dataSource, offset = 0L, size = content.length.toLong())

        // Then: LF detected
        chunk as EditorChunk.Text
        chunk.lineEnding shouldBe LineEnding.LF
    }

    @Test
    fun `loadChunk detects CRLF line endings`() = runTest {
        // Given: Content with CRLF endings (Windows)
        val mode = TextMode()
        val content = "Line 1\r\nLine 2\r\nLine 3"
        val dataSource = createMockDataSource(content)

        // When: Load chunk
        val chunk = mode.loadChunk(dataSource, offset = 0L, size = content.length.toLong())

        // Then: CRLF detected
        chunk as EditorChunk.Text
        chunk.lineEnding shouldBe LineEnding.CRLF
    }

    @Test
    fun `loadChunk detects CR line endings`() = runTest {
        // Given: Content with CR endings (old Mac)
        val mode = TextMode()
        val content = "Line 1\rLine 2\rLine 3"
        val dataSource = createMockDataSource(content)

        // When: Load chunk
        val chunk = mode.loadChunk(dataSource, offset = 0L, size = content.length.toLong())

        // Then: CR detected
        chunk as EditorChunk.Text
        chunk.lineEnding shouldBe LineEnding.CR
    }

    @Test
    fun `loadChunk detects mixed line endings`() = runTest {
        // Given: Content with mixed endings
        val mode = TextMode()
        val content = "Line 1\nLine 2\r\nLine 3\rLine 4"
        val dataSource = createMockDataSource(content)

        // When: Load chunk
        val chunk = mode.loadChunk(dataSource, offset = 0L, size = content.length.toLong())

        // Then: MIXED detected
        chunk as EditorChunk.Text
        chunk.lineEnding shouldBe LineEnding.MIXED
    }

    @Test
    fun `loadChunk defaults to LF for content without newlines`() = runTest {
        // Given: Content without line endings
        val mode = TextMode()
        val content = "Single line"
        val dataSource = createMockDataSource(content)

        // When: Load chunk
        val chunk = mode.loadChunk(dataSource, offset = 0L, size = content.length.toLong())

        // Then: Defaults to LF
        chunk as EditorChunk.Text
        chunk.lineEnding shouldBe LineEnding.LF
    }

    // ==================== Save Chunk Tests ====================

    // TODO Phase 3.1: Add these tests back when EditorDataSource.writeChunk() is added
    // @Test
    // fun `saveChunk converts Text chunk to UTF-8 bytes`() = runTest { ... }
    //
    // @Test
    // fun `saveChunk preserves multibyte UTF-8 characters`() = runTest { ... }

    @Test
    fun `saveChunk throws on Binary chunk`() = runTest {
        // Given: TextMode and Binary chunk (wrong type)
        val mode = TextMode()
        val binaryChunk = EditorChunk.Binary(
            offset = 0L,
            content = byteArrayOf(0x01, 0x02),
            size = 2L,
            isDirty = true
        )
        val dataSource = mockk<EditorDataSource>(relaxed = true)

        // When: Try to save binary chunk
        val result = runCatching {
            mode.saveChunk(dataSource, binaryChunk)
        }

        // Then: Should throw IllegalArgumentException
        result.isFailure shouldBe true
        result.exceptionOrNull() shouldBe instanceOf<IllegalArgumentException>()
    }

    // ==================== Buffer Creation Tests ====================

    // TODO Phase 1.4: Re-enable when ChunkedTextBuffer implements EditorBuffer
    // @Test
    // fun `createBuffer returns ChunkedTextBuffer`() {
    //     val mode = TextMode()
    //     val chunkManager = mockk<ChunkManager>(relaxed = true)
    //     val buffer = mode.createBuffer(chunkManager)
    //     buffer shouldBe instanceOf<ChunkedTextBuffer>()
    // }

    // ==================== Offset and Size Tests ====================

    @Test
    fun `loadChunk preserves offset and size`() = runTest {
        // Given: TextMode with specific offset and size
        val mode = TextMode()
        val content = "Test content at offset 100"
        val dataSource = createMockDataSource(content)

        // When: Load chunk at offset 100
        val chunk = mode.loadChunk(dataSource, offset = 100L, size = content.length.toLong())

        // Then: Offset and size preserved
        chunk as EditorChunk.Text
        chunk.offset shouldBe 100L
        chunk.size shouldBe content.length.toLong()
    }

    @Test
    fun `loadChunk sets isDirty to false for newly loaded chunks`() = runTest {
        // Given: TextMode
        val mode = TextMode()
        val dataSource = createMockDataSource("Fresh content")

        // When: Load chunk
        val chunk = mode.loadChunk(dataSource, offset = 0L, size = 13L)

        // Then: Not dirty (freshly loaded)
        chunk.isDirty shouldBe false
    }
}
