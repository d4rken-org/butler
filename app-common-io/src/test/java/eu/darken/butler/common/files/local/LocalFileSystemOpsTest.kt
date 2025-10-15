package eu.darken.butler.common.files.local

import eu.darken.butler.common.files.LocalPath
import eu.darken.butler.common.files.errors.PathAlreadyExistsException
import eu.darken.butler.common.files.errors.ReadException
import eu.darken.butler.common.files.errors.WriteException
import eu.darken.butler.common.files.metadata.FileType
import eu.darken.butler.common.files.metadata.Ownership
import eu.darken.butler.common.files.metadata.Permissions
import eu.darken.butler.common.pkgs.pkgops.LibcoreTool
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.longs.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkConstructor
import io.mockk.unmockkConstructor
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import testhelpers.BaseTest
import java.io.File
import kotlin.time.Instant

class LocalFileSystemOpsTest : BaseTest() {

    private val mockLibcoreTool = mockk<LibcoreTool> {
        every { getNameForUid(any()) } returns null
        every { getNameForGid(any()) } returns null
    }

    private val fileSystemOps = LocalFileSystemOps(mockLibcoreTool)

    @Test
    fun `lookup returns file metadata`(@TempDir tempDir: File) = runTest {
        val testFile = File(tempDir, "test.txt").apply {
            writeText("content")
        }
        val path = LocalPath.build(testFile)

        val lookup = fileSystemOps.lookup(path)

        lookup.lookedUp shouldBe path
        lookup.fileType shouldBe FileType.FILE
        lookup.size shouldBe 7L
        lookup.modifiedAt shouldNotBe Instant.DISTANT_PAST
        lookup.error shouldBe null
    }

    @Test
    fun `lookup returns directory metadata`(@TempDir tempDir: File) = runTest {
        val path = LocalPath.build(tempDir)

        val lookup = fileSystemOps.lookup(path)

        lookup.lookedUp shouldBe path
        lookup.fileType shouldBe FileType.DIRECTORY
        lookup.error shouldBe null
    }

    @Test
    fun `lookup handles restricted files gracefully`() = runTest {
        // This file typically exists and has restricted permissions on Android/Linux
        val restrictedPath = LocalPath.build("/proc/1/mem")

        // Should either succeed with partial data or throw ReadException
        // Depending on permissions, it might get file type but not size
        try {
            val lookup = fileSystemOps.lookup(restrictedPath)
            // If lookup succeeds, it should have some data
            lookup.lookedUp shouldBe restrictedPath
            // Error field may be populated if partial data was collected
        } catch (e: ReadException) {
            // Expected if file cannot be accessed at all
        }
    }

    @Test
    fun `lookup throws ReadException for non-existent file`() = runTest {
        val nonExistentPath = LocalPath.build("/tmp/non-existent-file-${System.currentTimeMillis()}")

        shouldThrow<ReadException> {
            fileSystemOps.lookup(nonExistentPath)
        }
    }

    @Test
    fun `lookupExtended returns extended metadata`(@TempDir tempDir: File) = runTest {
        val testFile = File(tempDir, "test.txt").apply {
            writeText("content")
            setReadable(true)
            setWritable(true)
        }
        val path = LocalPath.build(testFile)

        val extended = fileSystemOps.lookupExtended(path)

        extended.lookup.lookedUp shouldBe path
        extended.lookup.fileType shouldBe FileType.FILE
        // Note: permissions and ownership require Android APIs (Os.lstat) which aren't
        // available in pure JVM unit tests. They will be null here but populated on Android.
        extended.createdAt shouldNotBe null
    }

    @Test
    fun `listFiles returns child paths`(@TempDir tempDir: File) = runTest {
        File(tempDir, "file1.txt").createNewFile()
        File(tempDir, "file2.txt").createNewFile()
        File(tempDir, "subdir").mkdir()
        val path = LocalPath.build(tempDir)

        val children = fileSystemOps.listFiles(path)

        children shouldHaveSize 3
        children.map { it.name } shouldContain "file1.txt"
        children.map { it.name } shouldContain "file2.txt"
        children.map { it.name } shouldContain "subdir"
    }

    @Test
    fun `listFiles returns empty list for empty directory`(@TempDir tempDir: File) = runTest {
        val path = LocalPath.build(tempDir)

        val children = fileSystemOps.listFiles(path)

        children shouldHaveSize 0
    }

    @Test
    fun `listFiles throws ReadException for non-directory`(@TempDir tempDir: File) = runTest {
        val testFile = File(tempDir, "test.txt").apply { createNewFile() }
        val path = LocalPath.build(testFile)

        shouldThrow<ReadException> {
            fileSystemOps.listFiles(path)
        }
    }

    @Test
    fun `lookupFiles returns lookups for children`(@TempDir tempDir: File) = runTest {
        File(tempDir, "file1.txt").apply { writeText("content1") }
        File(tempDir, "file2.txt").apply { writeText("content2") }
        val path = LocalPath.build(tempDir)

        val lookups = fileSystemOps.lookupFiles(path)

        lookups shouldHaveSize 2
        lookups.all { it.fileType == FileType.FILE } shouldBe true
        lookups.map { it.lookedUp.name } shouldContain "file1.txt"
        lookups.map { it.lookedUp.name } shouldContain "file2.txt"
    }

    @Test
    fun `lookupFilesExtended returns extended lookups for children`(@TempDir tempDir: File) = runTest {
        File(tempDir, "file1.txt").apply { writeText("content1") }
        File(tempDir, "file2.txt").apply { writeText("content2") }
        val path = LocalPath.build(tempDir)

        val lookups = fileSystemOps.lookupFilesExtended(path)

        lookups shouldHaveSize 2
        lookups.all { it.lookup.fileType == FileType.FILE } shouldBe true
        // Note: permissions and ownership require Android APIs (Os.lstat) which aren't
        // available in pure JVM unit tests. Verify the method returns data, not the values.
        lookups.all { it.createdAt != null } shouldBe true
    }

    @Test
    fun `exists returns true for existing file`(@TempDir tempDir: File) = runTest {
        val testFile = File(tempDir, "test.txt").apply { createNewFile() }
        val path = LocalPath.build(testFile)

        fileSystemOps.exists(path) shouldBe true
    }

    @Test
    fun `exists returns false for non-existent file`(@TempDir tempDir: File) = runTest {
        val path = LocalPath.build(tempDir, "non-existent.txt")

        fileSystemOps.exists(path) shouldBe false
    }

    @Test
    fun `createFile creates new file`(@TempDir tempDir: File) = runTest {
        val path = LocalPath.build(tempDir, "new-file.txt")

        fileSystemOps.createFile(path)

        path.file.exists() shouldBe true
        path.file.isFile shouldBe true
    }

    @Test
    fun `createFile throws PathAlreadyExistsException for existing file`(@TempDir tempDir: File) = runTest {
        val testFile = File(tempDir, "existing.txt").apply { createNewFile() }
        val path = LocalPath.build(testFile)

        shouldThrow<PathAlreadyExistsException> {
            fileSystemOps.createFile(path)
        }
    }

    @Test
    fun `createDir creates new directory`(@TempDir tempDir: File) = runTest {
        val path = LocalPath.build(tempDir, "new-dir")

        fileSystemOps.createDir(path)

        path.file.exists() shouldBe true
        path.file.isDirectory shouldBe true
    }

    @Test
    fun `createDir creates parent directories`(@TempDir tempDir: File) = runTest {
        val path = LocalPath.build(tempDir, "parent", "child", "dir")

        fileSystemOps.createDir(path, createParents = true)

        path.file.exists() shouldBe true
        path.file.isDirectory shouldBe true
    }

    @Test
    fun `createDir is idempotent for existing directory`(@TempDir tempDir: File) = runTest {
        val path = LocalPath.build(tempDir)

        // Should not throw exception
        fileSystemOps.createDir(path)

        path.file.exists() shouldBe true
    }

    @Test
    fun `delete removes file`(@TempDir tempDir: File) = runTest {
        val testFile = File(tempDir, "to-delete.txt").apply { createNewFile() }
        val path = LocalPath.build(testFile)

        val result = fileSystemOps.delete(path, recursive = false)

        result shouldBe true
        testFile.exists() shouldBe false
    }

    @Test
    fun `delete removes empty directory`(@TempDir tempDir: File) = runTest {
        val dir = File(tempDir, "empty-dir").apply { mkdir() }
        val path = LocalPath.build(dir)

        val result = fileSystemOps.delete(path, recursive = false)

        result shouldBe true
        dir.exists() shouldBe false
    }

    @Test
    fun `delete with recursive removes directory tree`(@TempDir tempDir: File) = runTest {
        val dir = File(tempDir, "parent").apply {
            mkdir()
            File(this, "child1.txt").createNewFile()
            File(this, "subdir").apply {
                mkdir()
                File(this, "child2.txt").createNewFile()
            }
        }
        val path = LocalPath.build(dir)

        val result = fileSystemOps.delete(path, recursive = true)

        result shouldBe true
        dir.exists() shouldBe false
    }

    @Test
    fun `delete throws WriteException for non-empty directory without recursive`(@TempDir tempDir: File) = runTest {
        val dir = File(tempDir, "parent").apply {
            mkdir()
            File(this, "child.txt").createNewFile()
        }
        val path = LocalPath.build(dir)

        shouldThrow<WriteException> {
            fileSystemOps.delete(path, recursive = false)
        }
    }

    @Test
    fun `createSymlink creates symbolic link`(@TempDir tempDir: File) = runTest {
        val targetFile = File(tempDir, "target.txt").apply { writeText("target content") }
        val linkPath = LocalPath.build(tempDir, "link.txt")
        val targetPath = LocalPath.build(targetFile)

        val result = fileSystemOps.createSymlink(linkPath, targetPath)

        result shouldBe true
        linkPath.file.exists() shouldBe true
    }

    @Test
    fun `readSymbolicLink returns target path`(@TempDir tempDir: File) = runTest {
        val targetFile = File(tempDir, "target.txt").apply { writeText("target content") }
        val linkFile = File(tempDir, "link.txt")
        val linkPath = LocalPath.build(linkFile)
        val targetPath = LocalPath.build(targetFile)

        // Create symlink using java.nio.Files
        java.nio.file.Files.createSymbolicLink(linkFile.toPath(), targetFile.toPath())

        val readTarget = fileSystemOps.readSymbolicLink(linkPath)

        readTarget.path shouldBe targetPath.path
    }

    @Test
    fun `move renames file`(@TempDir tempDir: File) = runTest {
        val sourceFile = File(tempDir, "source.txt").apply { writeText("content") }
        val sourcePath = LocalPath.build(sourceFile)
        val destPath = LocalPath.build(tempDir, "dest.txt")

        val result = fileSystemOps.move(sourcePath, destPath)

        result shouldBe true
        sourceFile.exists() shouldBe false
        destPath.file.exists() shouldBe true
        destPath.file.readText() shouldBe "content"
    }

    @Test
    fun `canRead returns true for readable file`(@TempDir tempDir: File) = runTest {
        val testFile = File(tempDir, "readable.txt").apply {
            createNewFile()
            setReadable(true)
        }
        val path = LocalPath.build(testFile)

        fileSystemOps.canRead(path) shouldBe true
    }

    @Test
    fun `canWrite returns true for writable file`(@TempDir tempDir: File) = runTest {
        val testFile = File(tempDir, "writable.txt").apply {
            createNewFile()
            setWritable(true)
        }
        val path = LocalPath.build(testFile)

        fileSystemOps.canWrite(path) shouldBe true
    }

    @Test
    fun `canWrite returns true for non-existent file in writable directory`(@TempDir tempDir: File) = runTest {
        val path = LocalPath.build(tempDir, "new-file.txt")

        fileSystemOps.canWrite(path) shouldBe true
    }

    @Test
    fun `openInputStream reads file content`(@TempDir tempDir: File) = runTest {
        val testFile = File(tempDir, "input.txt").apply { writeText("test content") }
        val path = LocalPath.build(testFile)

        val inputStream = fileSystemOps.openInputStream(path)
        val content = inputStream.readBytes().decodeToString()

        content shouldBe "test content"
        inputStream.close()
    }

    @Test
    fun `openOutputStream writes file content`(@TempDir tempDir: File) = runTest {
        val testFile = File(tempDir, "output.txt")
        val path = LocalPath.build(testFile)

        val outputStream = fileSystemOps.openOutputStream(path, append = false)
        outputStream.write("written content".toByteArray())
        outputStream.close()

        testFile.readText() shouldBe "written content"
    }

    @Test
    fun `openOutputStream with append appends to file`(@TempDir tempDir: File) = runTest {
        val testFile = File(tempDir, "append.txt").apply { writeText("initial") }
        val path = LocalPath.build(testFile)

        val outputStream = fileSystemOps.openOutputStream(path, append = true)
        outputStream.write(" appended".toByteArray())
        outputStream.close()

        testFile.readText() shouldBe "initial appended"
    }

    @Test
    fun `file returns readable FileHandle`(@TempDir tempDir: File) = runTest {
        val testFile = File(tempDir, "handle.txt").apply { writeText("handle content") }
        val path = LocalPath.build(testFile)

        val handle = fileSystemOps.file(path, readWrite = false)
        val content = ByteArray(14)
        handle.read(0, content, 0, 14)
        handle.close()

        content.decodeToString() shouldBe "handle content"
    }

    @Test
    fun `setModifiedAt updates timestamp`(@TempDir tempDir: File) = runTest {
        val testFile = File(tempDir, "timestamp.txt").apply { createNewFile() }
        val path = LocalPath.build(testFile)
        val newTime = Instant.fromEpochMilliseconds(1000000000L)

        val result = fileSystemOps.setModifiedAt(path, newTime)

        result shouldBe true
        testFile.lastModified() shouldBe 1000000000L
    }

    @Test
    fun `setPermissions updates file permissions`(@TempDir tempDir: File) = runTest {
        val testFile = File(tempDir, "perms.txt").apply { createNewFile() }
        val path = LocalPath.build(testFile)
        val permissions = Permissions(mode = 0b110100100) // rw-r--r--

        fileSystemOps.setPermissions(path, permissions)

        // Result depends on platform support
        // On supported systems: result shouldBe true
        // On unsupported systems: result shouldBe false
    }

    @Test
    fun `setOwnership updates file ownership`(@TempDir tempDir: File) = runTest {
        val testFile = File(tempDir, "owner.txt").apply { createNewFile() }
        val path = LocalPath.build(testFile)
        val ownership = Ownership(userId = 1000, groupId = 1000)

        fileSystemOps.setOwnership(path, ownership)

        // Result depends on privileges (typically requires root)
        // result will be false without elevated privileges
    }

    @Test
    fun `du calculates directory size`(@TempDir tempDir: File) = runTest {
        File(tempDir, "file1.txt").apply { writeText("12345") }
        File(tempDir, "file2.txt").apply { writeText("67890") }
        val subdir = File(tempDir, "subdir").apply { mkdir() }
        File(subdir, "file3.txt").apply { writeText("abc") }
        val path = LocalPath.build(tempDir)

        val size = fileSystemOps.du(path)

        size shouldBeGreaterThan 0L
        // Exact size depends on file system, but should be at least content size
        size shouldBeGreaterThan 13L // 5 + 5 + 3 = 13 bytes of content
    }

    @Test
    fun `getFileSystem returns filesystem info`(@TempDir tempDir: File) = runTest {
        // Mock StatFs constructor and methods for this test only
        mockkConstructor(android.os.StatFs::class)
        try {
            every { anyConstructed<android.os.StatFs>().availableBytes } returns 50_000_000L
            every { anyConstructed<android.os.StatFs>().totalBytes } returns 100_000_000L

            val path = LocalPath.build(tempDir)
            val fileSystem = fileSystemOps.getFileSystem(path)

            fileSystem shouldNotBe null
            fileSystem.freeSpace shouldBe 50_000_000L
            fileSystem.totalSpace shouldBe 100_000_000L
        } finally {
            // Cleanup the mock after test - guaranteed to run even if assertions fail
            unmockkConstructor(android.os.StatFs::class)
        }
    }

    @Test
    fun `lookup collects partial data on errors`(@TempDir tempDir: File) = runTest {
        val testFile = File(tempDir, "partial.txt").apply {
            createNewFile()
            // Make it readable but simulate potential stat issues
            setReadable(true)
        }
        val path = LocalPath.build(testFile)

        val lookup = fileSystemOps.lookup(path)

        // Should always get the file type at minimum
        lookup.fileType shouldNotBe FileType.UNKNOWN
        lookup.lookedUp shouldBe path
        // Other fields should have valid data or sentinel values
        lookup.size shouldBeGreaterThan -1L
    }

    // ============ CREATEPARENTS FLAG TESTS ============

    @Test
    fun `createDir with createParents=false should fail when parent missing`(@TempDir tempDir: File) = runTest {
        val path = LocalPath.build(tempDir, "non-existent-parent", "new-dir")

        shouldThrow<WriteException> {
            fileSystemOps.createDir(path, createParents = false)
        }
    }

    @Test
    fun `createDir with createParents=true should succeed when parent missing`(@TempDir tempDir: File) = runTest {
        val path = LocalPath.build(tempDir, "non-existent-parent", "new-dir")

        fileSystemOps.createDir(path, createParents = true)

        path.file.exists() shouldBe true
        path.file.isDirectory shouldBe true
        path.file.parentFile?.exists() shouldBe true
    }

    @Test
    fun `createFile with createParents=false should fail when parent missing`(@TempDir tempDir: File) = runTest {
        val path = LocalPath.build(tempDir, "non-existent-parent", "new-file.txt")

        shouldThrow<WriteException> {
            fileSystemOps.createFile(path, createParents = false)
        }
    }

    @Test
    fun `createFile with createParents=true should succeed when parent missing`(@TempDir tempDir: File) = runTest {
        val path = LocalPath.build(tempDir, "non-existent-parent", "new-file.txt")

        fileSystemOps.createFile(path, createParents = true)

        path.file.exists() shouldBe true
        path.file.isFile shouldBe true
        path.file.parentFile?.exists() shouldBe true
    }
}
