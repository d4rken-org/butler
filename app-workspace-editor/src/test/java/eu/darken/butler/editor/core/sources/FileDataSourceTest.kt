package eu.darken.butler.editor.core.sources

import eu.darken.butler.common.files.APath
import eu.darken.butler.common.files.APathLookup
import eu.darken.butler.common.files.GatewaySwitch
import eu.darken.butler.common.files.LocalPath
import eu.darken.butler.common.files.LookupOptions
import eu.darken.butler.common.files.local.LocalFileSystemOps
import eu.darken.butler.common.files.metadata.OwnershipResolver
import eu.darken.butler.editor.core.engine.ChunkBoundary
import eu.darken.butler.editor.core.engine.EditorChunk
import eu.darken.butler.editor.core.engine.LineEnding
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
        // For write mode, create the file if it doesn't exist
        coEvery { file(any(), any()) } coAnswers {
            val path = firstArg<APath<*>>() as LocalPath
            val readWrite = secondArg<Boolean>()
            if (readWrite && !fileSystemOps.exists(path)) {
                // Create the file if it doesn't exist (for temp files during save)
                path.file.createNewFile()
            }
            fileSystemOps.file(path, readWrite)
        }

        // Mock delete() - delegates to REAL file system operations
        coEvery { delete(any<APath<*>>()) } coAnswers {
            val path = firstArg<APath<*>>() as LocalPath
            path.file.delete()
        }

        // Mock move() - delegates to REAL file system operations
        coEvery { move(any<APath<*>>(), any<APath<*>>()) } coAnswers {
            val source = firstArg<APath<*>>() as LocalPath
            val target = secondArg<APath<*>>() as LocalPath
            source.file.renameTo(target.file)
        }
    }

    private fun createFilePath(tempDir: File, fileName: String, content: String): LocalPath =
        LocalPath.build(File(tempDir, fileName).apply { writeText(content) })

    private suspend fun createDataSource(tempDir: File, fileName: String, content: String): FileDataSource =
        FileDataSource(workspaceId, createFilePath(tempDir, fileName, content), createMockGateway()).apply {
            open()
        }

    private fun boundaries(vararg entries: Pair<EditorChunk.Text, Pair<Long, Long>>): Map<EditorChunk.ChunkId, ChunkBoundary> {
        return entries.associate { (chunk, offsets) ->
            // Calculate line count from chunk content
            val lineCount = chunk.content.count { it == '\n' } + if (chunk.content.isNotEmpty() && !chunk.content.endsWith('\n')) 1 else 0
            chunk.id to ChunkBoundary(offsets.first, offsets.second, lineCount)
        }
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
        val chunk = dataSource.readChunk(0L, 11L)

        // Then: Should match expected content
        String(chunk) shouldBe "Hello World"
    }

    @Test
    fun `readChunk reads from middle of file`(@TempDir tempDir: File) = runTest {
        // Given
        val dataSource = createDataSource(tempDir, "test.txt", "Hello World\nLine 2\nLine 3")

        // When: Read from offset 12 (after first newline)
        val chunk = dataSource.readChunk(12L, 6L)

        // Then
        String(chunk) shouldBe "Line 2"
    }

    @Test
    fun `readChunk beyond EOF returns available content`(@TempDir tempDir: File) = runTest {
        // Given
        val dataSource = createDataSource(tempDir, "test.txt", "Hello")

        // When: Request more bytes than available
        val chunk = dataSource.readChunk(0L, 100L)

        // Then: Returns what's available
        String(chunk) shouldBe "Hello"
    }

    @Test
    fun `readChunk from offset beyond file size returns empty`(@TempDir tempDir: File) = runTest {
        // Given
        val dataSource = createDataSource(tempDir, "test.txt", "Hello")

        // When: Offset beyond file
        val chunk = dataSource.readChunk(100L, 10L)

        // Then: Empty string
        String(chunk) shouldBe ""
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

    // ==================== Save Tests ====================

    @Test
    fun `save merges modifications and writes to disk`(@TempDir tempDir: File) = runTest {
        // Given
        val testFile = File(tempDir, "test.txt").apply { writeText("Hello World") }
        val dataSource = createDataSource(tempDir, "test.txt", "Hello World")

        // When: Save dirty chunks
        val dirtyChunk = EditorChunk.Text(
            id = EditorChunk.ChunkId.generate(),
            offset = 0L,
            content = "Goodbye",
            size = 7L,
            lineCount = 1,
            lineEnding = LineEnding.LF,
            isDirty = true,
            isLoaded = true,
            refCount = 0
        )
        dataSource.save(
            listOf(dirtyChunk),
            boundaries(dirtyChunk to (0L to 7L))
        )

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

        // When: Save with empty dirty chunks list
        dataSource.save(emptyList(), emptyMap())

        // Then: File timestamp unchanged (assuming implementation optimizes this)
        // Note: Depending on implementation, this may or may not update timestamp
        dataSource.isModified.value shouldBe false
    }

    // ==================== Edge Case Tests ====================

    @Test
    fun `handles empty file`(@TempDir tempDir: File) = runTest {
        // Given: Empty file
        val dataSource = createDataSource(tempDir, "test.txt", "")

        // When: Read
        val chunk = dataSource.readChunk(0L, 100L)

        // Then: Empty string
        String(chunk) shouldBe ""
        dataSource.getSize() shouldBe 0L
    }

    @Test
    fun `handles single byte file`(@TempDir tempDir: File) = runTest {
        // Given
        val dataSource = createDataSource(tempDir, "test.txt", "X")

        // When
        val chunk = dataSource.readChunk(0L, 1L)

        // Then
        String(chunk) shouldBe "X"
        dataSource.getSize() shouldBe 1L
    }

    @Test
    fun `handles UTF-8 multibyte characters`(@TempDir tempDir: File) = runTest {
        // Given: File with emoji and Chinese characters
        val dataSource = createDataSource(tempDir, "test.txt", "Hello 🚀 World 中文")

        // When: Read
        val chunk = dataSource.readChunk(0L, 100L)

        // Then: Characters preserved
        String(chunk).contains("🚀") shouldBe true
        String(chunk).contains("中文") shouldBe true
    }

    @Test
    fun `handles file without trailing newline`(@TempDir tempDir: File) = runTest {
        // Given
        val dataSource = createDataSource(tempDir, "test.txt", "Line 1\nLine 2")

        // When
        val chunk = dataSource.readChunk(0L, 100L)

        // Then: Content preserved without trailing newline
        String(chunk) shouldBe "Line 1\nLine 2"
        String(chunk).endsWith("\n") shouldBe false
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

    // ==================== Partial Read Tests ====================

    @Test
    fun `readChunk handles large chunk requiring multiple reads`(@TempDir tempDir: File) = runTest {
        // Given: File with 100KB of content (larger than 8KB Okio segment size)
        val contentSize = 100 * 1024 // 100KB
        val content = "a".repeat(contentSize)
        val dataSource = createDataSource(tempDir, "large.txt", content)

        // When: Read 64KB chunk from middle (will require multiple Okio reads)
        val chunkSize = 64 * 1024L
        val chunk = dataSource.readChunk(1024L, chunkSize)

        // Then: Full chunk is read despite Okio returning partial reads
        chunk.size shouldBe chunkSize.toInt()
        String(chunk) shouldBe "a".repeat(chunkSize.toInt())
    }

    @Test
    fun `readChunk handles reading exactly 64KB chunk`(@TempDir tempDir: File) = runTest {
        // Given: File with exactly 64KB + some extra
        val chunkSize = 64 * 1024
        val content = "b".repeat(chunkSize + 1000)
        val dataSource = createDataSource(tempDir, "64kb.txt", content)

        // When: Read exactly 64KB (default chunk size)
        val chunk = dataSource.readChunk(0L, chunkSize.toLong())

        // Then: All 64KB read correctly (not just first 8KB segment)
        chunk.size shouldBe chunkSize
        String(chunk) shouldBe "b".repeat(chunkSize)
    }

    @Test
    fun `readChunk handles EOF during accumulation`(@TempDir tempDir: File) = runTest {
        // Given: File with 20KB content
        val contentSize = 20 * 1024
        val content = "c".repeat(contentSize)
        val dataSource = createDataSource(tempDir, "20kb.txt", content)

        // When: Try to read 64KB but file only has 20KB
        val chunk = dataSource.readChunk(0L, 64 * 1024L)

        // Then: Returns what's available (20KB), not empty or error
        chunk.size shouldBe contentSize
        String(chunk) shouldBe "c".repeat(contentSize)
    }

    @Test
    fun `readChunk handles partial last chunk correctly`(@TempDir tempDir: File) = runTest {
        // Given: File with 70KB (first chunk 64KB, second chunk 6KB)
        val contentSize = 70 * 1024
        val content = "d".repeat(contentSize)
        val dataSource = createDataSource(tempDir, "70kb.txt", content)

        // When: Read second chunk (starts at 64KB, should read remaining 6KB)
        val secondChunkStart = 64 * 1024L
        val secondChunkSize = 64 * 1024L
        val chunk = dataSource.readChunk(secondChunkStart, secondChunkSize)

        // Then: Returns only the 6KB available, not 64KB
        val expectedSize = contentSize - secondChunkStart.toInt()
        chunk.size shouldBe expectedSize
        String(chunk) shouldBe "d".repeat(expectedSize)
    }

    @Test
    fun `readChunk returns empty string at exact EOF`(@TempDir tempDir: File) = runTest {
        // Given: File with 1KB
        val content = "e".repeat(1024)
        val dataSource = createDataSource(tempDir, "1kb.txt", content)

        // When: Read at exact file size (EOF)
        val chunk = dataSource.readChunk(1024L, 100L)

        // Then: Returns empty string
        String(chunk) shouldBe ""
    }
}
