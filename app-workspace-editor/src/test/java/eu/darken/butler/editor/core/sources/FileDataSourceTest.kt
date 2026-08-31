package eu.darken.butler.editor.core.sources

import eu.darken.butler.common.files.APath
import eu.darken.butler.common.files.APathLookup
import eu.darken.butler.common.files.GatewaySwitch
import eu.darken.butler.common.files.LocalPath
import eu.darken.butler.common.files.LookupOptions
import eu.darken.butler.common.files.MoveOutcome
import eu.darken.butler.common.files.Existence
import eu.darken.butler.common.files.errors.PathNotFoundException
import eu.darken.butler.common.files.errors.PathPermissionDeniedException
import eu.darken.butler.common.files.errors.ReadException
import eu.darken.butler.common.files.local.LocalFileSystemOps
import eu.darken.butler.common.files.metadata.OwnershipResolver
import eu.darken.butler.workspace.core.Workspace
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.instanceOf
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import okio.buffer
import okio.use
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import testhelpers.BaseTest
import java.io.File
import java.io.IOException
import kotlin.uuid.Uuid

class FileDataSourceTest : BaseTest() {

    private val workspaceId = Workspace.Id(Uuid.random())
    private val mockOwnershipResolver = mockk<OwnershipResolver>(relaxed = true)

    // Use REAL LocalFileSystemOps for actual file operations
    private val fileSystemOps = LocalFileSystemOps(ownershipResolver = mockOwnershipResolver)

    private fun createMockGateway(): GatewaySwitch = mockk<GatewaySwitch>().apply {
        coEvery { canWrite(any()) } returns true
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
            if (source.file.renameTo(target.file)) MoveOutcome.Moved else MoveOutcome.NotSupported("rename failed")
        }

        // Mock createFile() - delegates to REAL file system operations
        coEvery { createFile(any(), any()) } coAnswers {
            val path = firstArg<APath<*>>() as LocalPath
            path.file.createNewFile()
        }
    }

    private fun createFilePath(tempDir: File, fileName: String, content: String): LocalPath =
        LocalPath.build(File(tempDir, fileName).apply { writeText(content) })

    private suspend fun createDataSource(tempDir: File, fileName: String, content: String): FileDataSource =
        FileDataSource(workspaceId, createFilePath(tempDir, fileName, content), createMockGateway()).apply {
            open()
        }

    private suspend fun FileDataSource.readBytes(offset: Long, count: Int): ByteArray =
        openByteSource(offset).buffer().use { it.readByteArray(count.toLong()) }

    private suspend fun FileDataSource.readAllBytes(offset: Long): ByteArray =
        openByteSource(offset).buffer().use { it.readByteArray() }

    // ==================== Initialization Tests ====================

    @Test
    fun `open succeeds without loading content`(@TempDir tempDir: File) = runTest {
        // Given: File with content
        val filePath = createFilePath(tempDir, "test.txt", "Hello World")
        val dataSource = FileDataSource(workspaceId, filePath, createMockGateway())

        // When: Open
        dataSource.open()

        // Then: Success without loading into memory
        dataSource.contentSource.value.size shouldBe 11L
    }

    @Test
    fun `open throws on non-existent file`(@TempDir tempDir: File) = runTest {
        // Given: Non-existent file
        val mockGateway = mockk<GatewaySwitch>().apply {
            coEvery { canWrite(any()) } returns true
            coEvery { exists(any()) } returns false
            coEvery { existsStrict(any()) } returns Existence.ABSENT
            coEvery { lookup(any(), any()) } throws ReadException("no such file", null)
        }
        val dataSource = FileDataSource(
            workspaceId,
            LocalPath.build(File(tempDir, "nonexistent.txt")),
            mockGateway,
        )

        // When & Then: Open throws
        val exception = runCatching { dataSource.open() }.exceptionOrNull()
        exception shouldBe instanceOf<PathNotFoundException>()
        // The path travels as a field, which is what lets the UI render it without parsing
        (exception as PathNotFoundException).path!!.name shouldBe "nonexistent.txt"
    }

    @Test
    fun `an unreadable file is not reported as gone`(@TempDir tempDir: File) = runTest {
        // A path the probe cannot answer for - an unreachable host, a wedged provider, or a denial.
        // Calling that "deleted" would tell the user the wrong story AND, because the gone types
        // are PathGoneError, suppress the permission handling that would have offered them a fix.
        val denied = PathPermissionDeniedException(
            path = LocalPath.build(File(tempDir, "locked.txt")),
            operation = "lookup",
            reason = PathPermissionDeniedException.Reason.ACCESS_DENIED,
        )
        val mockGateway = mockk<GatewaySwitch>().apply {
            coEvery { canWrite(any()) } returns true
            coEvery { existsStrict(any()) } returns Existence.UNKNOWN
            coEvery { lookup(any(), any()) } throws denied
        }
        val dataSource = FileDataSource(workspaceId, LocalPath.build(File(tempDir, "locked.txt")), mockGateway)

        val exception = runCatching { dataSource.open() }.exceptionOrNull()

        // The original error survives untouched - it is the one carrying the signal
        exception shouldBe denied
    }

    @Test
    fun `a file that still exists keeps its original failure`(@TempDir tempDir: File) = runTest {
        val boom = IOException("provider blew up")
        val mockGateway = mockk<GatewaySwitch>().apply {
            coEvery { canWrite(any()) } returns true
            coEvery { existsStrict(any()) } returns Existence.PRESENT
            coEvery { lookup(any(), any()) } throws boom
        }
        val dataSource = FileDataSource(workspaceId, LocalPath.build(File(tempDir, "there.txt")), mockGateway)

        val exception = runCatching { dataSource.open() }.exceptionOrNull()

        exception shouldBe boom
    }

    // ==================== Byte Source Tests ====================

    @Test
    fun `openByteSource reads from start of file`(@TempDir tempDir: File) = runTest {
        // Given: File with known content
        val dataSource = createDataSource(tempDir, "test.txt", "Hello World\nLine 2\nLine 3")

        // When: Read first 11 bytes
        val bytes = dataSource.readBytes(0L, 11)

        // Then: Should match expected content
        bytes shouldBe "Hello World".toByteArray()
    }

    @Test
    fun `openByteSource reads from middle of file`(@TempDir tempDir: File) = runTest {
        // Given
        val dataSource = createDataSource(tempDir, "test.txt", "Hello World\nLine 2\nLine 3")

        // When: Read from offset 12 (after first newline)
        val bytes = dataSource.readBytes(12L, 6)

        // Then
        bytes shouldBe "Line 2".toByteArray()
    }

    @Test
    fun `openByteSource read beyond EOF returns available content`(@TempDir tempDir: File) = runTest {
        // Given
        val dataSource = createDataSource(tempDir, "test.txt", "Hello")

        // When: Read everything from the start
        val bytes = dataSource.readAllBytes(0L)

        // Then: Returns what's available
        bytes shouldBe "Hello".toByteArray()
    }

    @Test
    fun `openByteSource from offset beyond file size returns empty`(@TempDir tempDir: File) = runTest {
        // Given
        val dataSource = createDataSource(tempDir, "test.txt", "Hello")

        // When: Offset beyond file
        val bytes = dataSource.readAllBytes(100L)

        // Then: No bytes
        bytes.size shouldBe 0
    }

    @Test
    fun `openByteSource multiple reads with different offsets`(@TempDir tempDir: File) = runTest {
        // Given
        val dataSource = createDataSource(tempDir, "test.txt", "ABCDEFGHIJ")

        // When: Read different ranges
        val bytes1 = dataSource.readBytes(0L, 3)
        val bytes2 = dataSource.readBytes(3L, 3)
        val bytes3 = dataSource.readBytes(6L, 3)

        // Then: All ranges correct
        bytes1 shouldBe "ABC".toByteArray()
        bytes2 shouldBe "DEF".toByteArray()
        bytes3 shouldBe "GHI".toByteArray()
    }

    // ==================== Safe-save (local backup-swap) Tests ====================

    @Test
    fun `save failure preserves the original file and cleans up artifacts`(@TempDir tempDir: File) = runTest {
        val gateway = createMockGateway()
        // Fail the commit move (temp -> original); allow the backup and restore moves.
        coEvery { gateway.move(any<APath<*>>(), any<APath<*>>()) } coAnswers {
            val source = firstArg<APath<*>>() as LocalPath
            val target = secondArg<APath<*>>() as LocalPath
            if (source.name.contains("butler-save-tmp")) throw IOException("simulated commit failure")
            if (source.file.renameTo(target.file)) MoveOutcome.Moved else MoveOutcome.NotSupported("rename failed")
        }
        val testFile = File(tempDir, "test.txt").apply { writeText("Hello World") }
        val dataSource = FileDataSource(workspaceId, LocalPath.build(testFile), gateway).apply { open() }

        shouldThrow<Exception> {
            dataSource.commit { context -> context.sink.write("Goodbye".toByteArray()) }
        }

        // Original intact, no leftover save artifacts.
        testFile.readText() shouldBe "Hello World"
        tempDir.listFiles()!!.none { it.name.contains("butler-save") } shouldBe true
    }

    @Test
    fun `save failure via non-Moved result also preserves the original`(@TempDir tempDir: File) = runTest {
        val gateway = createMockGateway()
        coEvery { gateway.move(any<APath<*>>(), any<APath<*>>()) } coAnswers {
            val source = firstArg<APath<*>>() as LocalPath
            val target = secondArg<APath<*>>() as LocalPath
            when {
                source.name.contains("butler-save-tmp") -> MoveOutcome.NotSupported("test")
                source.file.renameTo(target.file) -> MoveOutcome.Moved
                else -> MoveOutcome.NotSupported("rename failed")
            }
        }
        val testFile = File(tempDir, "test.txt").apply { writeText("Hello World") }
        val dataSource = FileDataSource(workspaceId, LocalPath.build(testFile), gateway).apply { open() }

        shouldThrow<Exception> {
            dataSource.commit { context -> context.sink.write("Goodbye".toByteArray()) }
        }

        testFile.readText() shouldBe "Hello World"
        tempDir.listFiles()!!.none { it.name.contains("butler-save") } shouldBe true
    }

    @Test
    fun `successful save leaves no temp or backup artifacts`(@TempDir tempDir: File) = runTest {
        val testFile = File(tempDir, "test.txt").apply { writeText("Hello World") }
        val dataSource = createDataSource(tempDir, "test.txt", "Hello World")

        dataSource.commit { context -> context.sink.write("Goodbye".toByteArray()) }

        testFile.readText() shouldBe "Goodbye"
        tempDir.listFiles()!!.none { it.name.contains("butler-save") } shouldBe true
    }

    @Test
    fun `save failure never destroys the original even when restore fails`(@TempDir tempDir: File) = runTest {
        val gateway = createMockGateway()
        // Every move targeting the original (commit AND restore) fails.
        coEvery { gateway.move(any<APath<*>>(), any<APath<*>>()) } coAnswers {
            val source = firstArg<APath<*>>() as LocalPath
            val target = secondArg<APath<*>>() as LocalPath
            when {
                target.name == "test.txt" -> throw IOException("write barrier")
                source.file.renameTo(target.file) -> MoveOutcome.Moved
                else -> MoveOutcome.NotSupported("rename failed")
            }
        }
        val testFile = File(tempDir, "test.txt").apply { writeText("Hello World") }
        val dataSource = FileDataSource(workspaceId, LocalPath.build(testFile), gateway).apply { open() }

        shouldThrow<Exception> {
            dataSource.commit { context -> context.sink.write("Goodbye".toByteArray()) }
        }

        // The original survives: restored in place, or preserved in the backup if restore also failed.
        val survivors = tempDir.listFiles()!!
            .filter { it.name == "test.txt" || it.name.contains("butler-save-bak") }
            .map { it.readText() }
        survivors.any { it == "Hello World" } shouldBe true
    }

    // ==================== Safe-save (in-place / non-local) Tests ====================

    @Test
    fun `commitViaInPlace overwrites in place and removes the backup on success`(@TempDir tempDir: File) = runTest {
        val testFile = File(tempDir, "test.txt").apply { writeText("original") }
        val dataSource = createDataSource(tempDir, "test.txt", "original")
        val backupPath = LocalPath.build(File(tempDir, "test.txt.butler-save-bak-inplace"))

        dataSource.commitViaInPlace(backupPath) { context ->
            context.sink.write("NEW CONTENT".toByteArray())
        }

        testFile.readText() shouldBe "NEW CONTENT"
        backupPath.file.exists() shouldBe false
    }

    @Test
    fun `commitViaInPlace retains the backup when the overwrite fails`(@TempDir tempDir: File) = runTest {
        val gateway = createMockGateway()
        // Block read/write opens of the target document; backup writes still succeed.
        coEvery { gateway.file(any(), any()) } coAnswers {
            val path = firstArg<APath<*>>() as LocalPath
            val readWrite = secondArg<Boolean>()
            if (readWrite && path.name == "test.txt") throw IOException("doc not writable")
            if (readWrite && !fileSystemOps.exists(path)) path.file.createNewFile()
            fileSystemOps.file(path, readWrite)
        }
        val testFile = File(tempDir, "test.txt").apply { writeText("original") }
        val dataSource = FileDataSource(workspaceId, LocalPath.build(testFile), gateway).apply { open() }
        val backupPath = LocalPath.build(File(tempDir, "test.txt.butler-save-bak-inplace"))

        shouldThrow<Exception> {
            dataSource.commitViaInPlace(backupPath) { context ->
                context.sink.write("NEW".toByteArray())
            }
        }

        // Original content preserved in the backup for recovery.
        backupPath.file.readText() shouldBe "original"
    }

    // ==================== Edge Case Tests ====================

    @Test
    fun `handles empty file`(@TempDir tempDir: File) = runTest {
        // Given: Empty file
        val dataSource = createDataSource(tempDir, "test.txt", "")

        // When: Read
        val bytes = dataSource.readAllBytes(0L)

        // Then: No bytes
        bytes.size shouldBe 0
        dataSource.getSize() shouldBe 0L
    }

    @Test
    fun `handles single byte file`(@TempDir tempDir: File) = runTest {
        // Given
        val dataSource = createDataSource(tempDir, "test.txt", "X")

        // When
        val bytes = dataSource.readBytes(0L, 1)

        // Then
        bytes shouldBe "X".toByteArray()
        dataSource.getSize() shouldBe 1L
    }

    @Test
    fun `handles UTF-8 multibyte characters`(@TempDir tempDir: File) = runTest {
        // Given: File with emoji and Chinese characters
        val content = "Hello 🚀 World 中文"
        val dataSource = createDataSource(tempDir, "test.txt", content)

        // When: Read
        val bytes = dataSource.readAllBytes(0L)

        // Then: Bytes round-trip verbatim and decode back to the original characters
        bytes shouldBe content.toByteArray()
        val decoded = bytes.toString(Charsets.UTF_8)
        decoded.contains("🚀") shouldBe true
        decoded.contains("中文") shouldBe true
    }

    @Test
    fun `handles file without trailing newline`(@TempDir tempDir: File) = runTest {
        // Given
        val dataSource = createDataSource(tempDir, "test.txt", "Line 1\nLine 2")

        // When
        val bytes = dataSource.readAllBytes(0L)

        // Then: Content preserved without trailing newline
        bytes shouldBe "Line 1\nLine 2".toByteArray()
        bytes.toString(Charsets.UTF_8).endsWith("\n") shouldBe false
    }

    @Test
    fun `close can be called multiple times safely`(@TempDir tempDir: File) = runTest {
        // Given
        val dataSource = createDataSource(tempDir, "test.txt", "Content")

        // When: Close multiple times
        dataSource.close()
        dataSource.close()

        // Then: No exception thrown, content source reset to empty Memory
        dataSource.contentSource.value.size shouldBe 0L
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
    fun `openByteSource handles large read requiring multiple segments`(@TempDir tempDir: File) = runTest {
        // Given: File with 100KB of content (larger than 8KB Okio segment size)
        val contentSize = 100 * 1024 // 100KB
        val content = "a".repeat(contentSize)
        val dataSource = createDataSource(tempDir, "large.txt", content)

        // When: Read 64KB from an offset (will require multiple Okio reads)
        val readSize = 64 * 1024
        val bytes = dataSource.readBytes(1024L, readSize)

        // Then: Full range is read despite Okio returning partial reads
        bytes.size shouldBe readSize
        bytes shouldBe "a".repeat(readSize).toByteArray()
    }

    @Test
    fun `openByteSource handles reading exactly 64KB`(@TempDir tempDir: File) = runTest {
        // Given: File with exactly 64KB + some extra
        val readSize = 64 * 1024
        val content = "b".repeat(readSize + 1000)
        val dataSource = createDataSource(tempDir, "64kb.txt", content)

        // When: Read exactly 64KB
        val bytes = dataSource.readBytes(0L, readSize)

        // Then: All 64KB read correctly (not just first 8KB segment)
        bytes.size shouldBe readSize
        bytes shouldBe "b".repeat(readSize).toByteArray()
    }

    @Test
    fun `openByteSource handles EOF before requested amount`(@TempDir tempDir: File) = runTest {
        // Given: File with 20KB content
        val contentSize = 20 * 1024
        val content = "c".repeat(contentSize)
        val dataSource = createDataSource(tempDir, "20kb.txt", content)

        // When: Read everything although far more was expected to fit
        val bytes = dataSource.readAllBytes(0L)

        // Then: Returns what's available (20KB), not empty or error
        bytes.size shouldBe contentSize
        bytes shouldBe "c".repeat(contentSize).toByteArray()
    }

    @Test
    fun `openByteSource handles partial tail read correctly`(@TempDir tempDir: File) = runTest {
        // Given: File with 70KB (64KB head, 6KB tail)
        val contentSize = 70 * 1024
        val content = "d".repeat(contentSize)
        val dataSource = createDataSource(tempDir, "70kb.txt", content)

        // When: Read from 64KB to EOF (remaining 6KB)
        val tailStart = 64 * 1024L
        val bytes = dataSource.readAllBytes(tailStart)

        // Then: Returns only the 6KB available
        val expectedSize = contentSize - tailStart.toInt()
        bytes.size shouldBe expectedSize
        bytes shouldBe "d".repeat(expectedSize).toByteArray()
    }

    @Test
    fun `openByteSource returns no bytes at exact EOF`(@TempDir tempDir: File) = runTest {
        // Given: File with 1KB
        val content = "e".repeat(1024)
        val dataSource = createDataSource(tempDir, "1kb.txt", content)

        // When: Read at exact file size (EOF)
        val bytes = dataSource.readAllBytes(1024L)

        // Then: No bytes
        bytes.size shouldBe 0
    }
}
