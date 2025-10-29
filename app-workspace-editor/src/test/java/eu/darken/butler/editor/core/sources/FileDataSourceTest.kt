package eu.darken.butler.editor.core.sources

import eu.darken.butler.common.files.APath
import eu.darken.butler.common.files.APathLookup
import eu.darken.butler.common.files.GatewaySwitch
import eu.darken.butler.common.files.LocalPath
import eu.darken.butler.common.files.LookupOptions
import eu.darken.butler.common.files.local.LocalFileSystemOps
import eu.darken.butler.common.files.metadata.OwnershipResolver
import eu.darken.butler.workspace.core.Workspace
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.types.instanceOf
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import testhelpers.BaseTest
import java.io.File
import java.io.FileNotFoundException
import kotlin.uuid.Uuid

class FileDataSourceTest : BaseTest() {

    private val workspaceId = Workspace.Id(Uuid.random())
    private val mockOwnershipResolver = mockk<OwnershipResolver>(relaxed = true)

    // Use REAL LocalFileSystemOps for actual file operations
    private val fileSystemOps = LocalFileSystemOps(ownershipResolver = mockOwnershipResolver)

    private fun createMockGateway(): GatewaySwitch = mockk<GatewaySwitch>().apply {
        // Mock exists() - delegates to REAL file system operations
        coEvery { exists(any()) } coAnswers {
            val path = firstArg<APath<*>>() as LocalPath
            fileSystemOps.exists(path)
        }

        // Mock lookup() - delegates to REAL file system operations
        @Suppress("UNCHECKED_CAST")
        coEvery { lookup(any(), any()) } coAnswers {
            val path = firstArg<APath<*>>() as LocalPath
            val options = secondArg<LookupOptions>()
            fileSystemOps.lookup(path, options) as APathLookup<APath<*>>
        }

        // Mock file() - delegates to REAL file system operations
        coEvery { file(any(), any()) } coAnswers {
            val path = firstArg<APath<*>>() as LocalPath
            val readWrite = secondArg<Boolean>()
            fileSystemOps.file(path, readWrite)
        }
    }

    private fun createFilePath(tempDir: File, fileName: String, content: String): LocalPath =
        LocalPath.build(File(tempDir, fileName).apply { writeText(content) })

    private suspend fun createDataSource(tempDir: File, fileName: String, content: String): FileDataSource =
        FileDataSource(workspaceId, createFilePath(tempDir, fileName, content), createMockGateway()).apply {
            open()
        }

    // ==================== Initialization Tests ====================

    @Test
    fun `open succeeds without loading content`(@TempDir tempDir: File) = runTest {
        // Given: File with content
        val filePath = createFilePath(tempDir, "test.txt", "Hello World")
        val dataSource = FileDataSource(workspaceId, filePath, createMockGateway())

        // When: Open
        dataSource.open()

        // Then: Success without loading into memory
        dataSource.fileInfo.value shouldNotBe null
        dataSource.fileInfo.value?.size shouldBe 11L
    }

    @Test
    fun `open throws on non-existent file`(@TempDir tempDir: File) = runTest {
        // Given: Non-existent file
        val mockGateway = mockk<GatewaySwitch>().apply {
            coEvery { exists(any()) } returns false
        }
        val dataSource = FileDataSource(
            workspaceId,
            LocalPath.build(File(tempDir, "nonexistent.txt")),
            mockGateway,
        )

        // When & Then: Open throws IllegalArgumentException
        val exception = runCatching { dataSource.open() }.exceptionOrNull()
        exception shouldBe instanceOf<FileNotFoundException>()
    }

    // ==================== Read Chunk Tests ====================

    @Test
    fun `readChunk reads from start of file`(@TempDir tempDir: File) = runTest {
        // Given: File with known content
        val dataSource = createDataSource(tempDir, "test.txt", "Hello World\nLine 2\nLine 3")

        // When: Read first 11 bytes
        val chunk = dataSource.readChunk(startOffset = 0L, size = 11L)

        // Then: Should match expected content
        chunk shouldBe "Hello World"
    }

    @Test
    fun `readChunk reads from middle of file`(@TempDir tempDir: File) = runTest {
        // Given
        val dataSource = createDataSource(tempDir, "test.txt", "Hello World\nLine 2\nLine 3")

        // When: Read from offset 12 (after first newline)
        val chunk = dataSource.readChunk(startOffset = 12L, size = 6L)

        // Then
        chunk shouldBe "Line 2"
    }

    @Test
    fun `readChunk beyond EOF returns available content`(@TempDir tempDir: File) = runTest {
        // Given
        val dataSource = createDataSource(tempDir, "test.txt", "Hello")

        // When: Request more bytes than available
        val chunk = dataSource.readChunk(startOffset = 0L, size = 100L)

        // Then: Returns what's available
        chunk shouldBe "Hello"
    }

    @Test
    fun `readChunk from offset beyond file size returns empty`(@TempDir tempDir: File) = runTest {
        // Given
        val dataSource = createDataSource(tempDir, "test.txt", "Hello")

        // When: Offset beyond file
        val chunk = dataSource.readChunk(startOffset = 1000L, size = 10L)

        // Then: Empty string
        chunk shouldBe ""
    }

    @Test
    fun `readChunk multiple reads with different offsets`(@TempDir tempDir: File) = runTest {
        // Given
        val dataSource = createDataSource(tempDir, "test.txt", "ABCDEFGHIJ")

        // When: Read different chunks
        val chunk1 = dataSource.readChunk(0L, 3L)
        val chunk2 = dataSource.readChunk(3L, 3L)
        val chunk3 = dataSource.readChunk(6L, 3L)

        // Then: All chunks correct
        chunk1 shouldBe "ABC"
        chunk2 shouldBe "DEF"
        chunk3 shouldBe "GHI"
    }

    // ==================== Write Chunk Tests ====================

    @Test
    fun `writeChunk caches modification without writing to disk`(@TempDir tempDir: File) = runTest {
        // Given
        val testFile = File(tempDir, "test.txt").apply { writeText("Original Content") }
        val dataSource = createDataSource(tempDir, "test.txt", "Original Content")

        // When: Write modification
        dataSource.writeChunk(0L, "Modified")

        // Then: Original file unchanged
        testFile.readText() shouldBe "Original Content"

        // And: isModified flag set
        dataSource.isModified.value shouldBe true
    }

    @Test
    fun `writeChunk read returns modified content`(@TempDir tempDir: File) = runTest {
        // Given
        val dataSource = createDataSource(tempDir, "test.txt", "Original Content")

        // When: Write and read
        dataSource.writeChunk(0L, "Modified")

        // Then: Modified content returned
        dataSource.readChunk(0L, 8L) shouldBe "Modified"
    }

    @Test
    fun `writeChunk multiple modifications cached separately`(@TempDir tempDir: File) = runTest {
        // Given
        val dataSource = createDataSource(tempDir, "test.txt", "AAAA\nBBBB\nCCCC")

        // When: Multiple writes
        dataSource.writeChunk(0L, "1111")
        dataSource.writeChunk(5L, "2222")

        // Then: Both modifications cached
        dataSource.readChunk(0L, 4L) shouldBe "1111"
        dataSource.readChunk(5L, 4L) shouldBe "2222"
        dataSource.isModified.value shouldBe true
    }

    // ==================== Save Tests ====================

    @Test
    fun `save merges modifications and writes to disk`(@TempDir tempDir: File) = runTest {
        // Given
        val testFile = File(tempDir, "test.txt").apply { writeText("Hello World") }
        val dataSource = createDataSource(tempDir, "test.txt", "Hello World")

        // When: Modify and save
        dataSource.writeChunk(0L, "Goodbye")
        dataSource.save()

        // Then: File updated on disk
        testFile.readText().take(7) shouldBe "Goodbye"

        // And: isModified cleared
        dataSource.isModified.value shouldBe false
    }

    @Test
    fun `save with no modifications does nothing`(@TempDir tempDir: File) = runTest {
        // Given
        val testFile = File(tempDir, "test.txt").apply { writeText("Content") }
        val dataSource = createDataSource(tempDir, "test.txt", "Content")
        val lastModified = testFile.lastModified()

        Thread.sleep(100)

        // When: Save without modifications
        dataSource.save()

        // Then: File timestamp unchanged
        testFile.lastModified() shouldBe lastModified
    }

    // ==================== Edge Case Tests ====================

    @Test
    fun `handles empty file`(@TempDir tempDir: File) = runTest {
        // Given: Empty file
        val dataSource = createDataSource(tempDir, "test.txt", "")

        // When: Read
        val chunk = dataSource.readChunk(0L, 100L)

        // Then: Empty string
        chunk shouldBe ""
        dataSource.getSize() shouldBe 0L
    }

    @Test
    fun `handles single byte file`(@TempDir tempDir: File) = runTest {
        // Given
        val dataSource = createDataSource(tempDir, "test.txt", "X")

        // When
        val chunk = dataSource.readChunk(0L, 1L)

        // Then
        chunk shouldBe "X"
        dataSource.getSize() shouldBe 1L
    }

    @Test
    fun `handles UTF-8 multibyte characters`(@TempDir tempDir: File) = runTest {
        // Given: File with emoji and Chinese characters
        val dataSource = createDataSource(tempDir, "test.txt", "Hello 🚀 World 中文")

        // When: Read
        val chunk = dataSource.readChunk(0L, 100L)

        // Then: Characters preserved
        chunk.contains("🚀") shouldBe true
        chunk.contains("中文") shouldBe true
    }

    @Test
    fun `handles file without trailing newline`(@TempDir tempDir: File) = runTest {
        // Given
        val dataSource = createDataSource(tempDir, "test.txt", "Line 1\nLine 2")

        // When
        val chunk = dataSource.readChunk(0L, 100L)

        // Then: Content preserved without trailing newline
        chunk shouldBe "Line 1\nLine 2"
        chunk.endsWith("\n") shouldBe false
    }

    @Test
    fun `close can be called multiple times safely`(@TempDir tempDir: File) = runTest {
        // Given
        val dataSource = createDataSource(tempDir, "test.txt", "Content")

        // When: Close multiple times
        dataSource.close()
        dataSource.close()

        // Then: No exception thrown
        dataSource.fileInfo.value shouldBe null
        dataSource.isModified.value shouldBe false
    }

    @Test
    fun `getSize returns file size from metadata`(@TempDir tempDir: File) = runTest {
        // Given
        val dataSource = createDataSource(tempDir, "test.txt", "Hello World")

        // When & Then
        dataSource.getSize() shouldBe 11L
    }
}
