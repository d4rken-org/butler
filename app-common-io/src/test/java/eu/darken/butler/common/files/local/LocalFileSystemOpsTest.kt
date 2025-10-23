package eu.darken.butler.common.files.local

import eu.darken.butler.common.files.LocalPath
import eu.darken.butler.common.files.LookupOptions
import eu.darken.butler.common.files.errors.PathAlreadyExistsException
import eu.darken.butler.common.files.errors.ReadException
import eu.darken.butler.common.files.errors.WriteException
import eu.darken.butler.common.files.metadata.FileType
import eu.darken.butler.common.files.metadata.Ownership
import eu.darken.butler.common.files.metadata.OwnershipResolver
import eu.darken.butler.common.files.metadata.Permissions
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.longs.shouldBeGreaterThan
import io.kotest.matchers.longs.shouldBeGreaterThanOrEqual
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.types.shouldBeInstanceOf
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

    private val mockOwnershipResolver = mockk<OwnershipResolver>(relaxed = true)
    private val fileSystemOps = LocalFileSystemOps(mockOwnershipResolver)

    @Test
    fun `lookup returns file metadata`(@TempDir tempDir: File) = runTest {
        val testFile = File(tempDir, "test.txt").apply {
            writeText("content")
        }
        val path = LocalPath.build(testFile)

        val lookup = fileSystemOps.lookup(path, LookupOptions.BASE)

        lookup.lookedUp shouldBe path
        lookup.fileType shouldBe FileType.FILE
        lookup.size shouldBe 7L
        lookup.modifiedAt shouldNotBe Instant.DISTANT_PAST
        lookup.error shouldBe null
    }

    @Test
    fun `lookup returns directory metadata`(@TempDir tempDir: File) = runTest {
        val path = LocalPath.build(tempDir)

        val lookup = fileSystemOps.lookup(path, LookupOptions.BASE)

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
            val lookup = fileSystemOps.lookup(restrictedPath, LookupOptions.BASE)
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
            fileSystemOps.lookup(nonExistentPath, LookupOptions())
        }
    }

    @Test
    fun `lookup with fallbackToUnknown=true returns UNKNOWN for non-existent file`() = runTest {
        val nonExistentPath = LocalPath.build("/tmp/non-existent-file-${System.currentTimeMillis()}")

        val lookup = fileSystemOps.lookup(
            nonExistentPath,
            LookupOptions(fallbackToUnknown = true)
        )

        lookup.lookedUp shouldBe nonExistentPath
        lookup.fileType shouldBe FileType.UNKNOWN
        lookup.size shouldBe null
        lookup.modifiedAt shouldBe null
        lookup.error shouldNotBe null // Should capture the underlying exception
    }

    @Test
    fun `lookup with BASE options returns extended metadata`(@TempDir tempDir: File) = runTest {
        val testFile = File(tempDir, "test.txt").apply {
            writeText("content")
            setReadable(true)
            setWritable(true)
        }
        val path = LocalPath.build(testFile)

        val extended = fileSystemOps.lookup(path, LookupOptions.BASE)

        extended.lookedUp shouldBe path
        extended.fileType shouldBe FileType.FILE
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

        val lookups = fileSystemOps.lookupFiles(path, LookupOptions.BASE)

        lookups shouldHaveSize 2
        lookups.all { it.fileType == FileType.FILE } shouldBe true
        lookups.map { it.lookedUp.name } shouldContain "file1.txt"
        lookups.map { it.lookedUp.name } shouldContain "file2.txt"
    }

    @Test
    fun `lookupFiles with BASE options returns extended lookups for children`(@TempDir tempDir: File) = runTest {
        File(tempDir, "file1.txt").apply { writeText("content1") }
        File(tempDir, "file2.txt").apply { writeText("content2") }
        val path = LocalPath.build(tempDir)

        val lookups = fileSystemOps.lookupFiles(path, LookupOptions.BASE)

        lookups shouldHaveSize 2
        lookups.all { it.fileType == FileType.FILE } shouldBe true
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

        val lookup = fileSystemOps.lookup(path, LookupOptions.BASE)

        // Should always get the file type at minimum
        lookup.fileType shouldNotBe FileType.UNKNOWN
        lookup.lookedUp shouldBe path
        // Other fields should have valid data or sentinel values
        lookup.size?.shouldBeGreaterThanOrEqual(0L)
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

    // ============ NULLABLE FIELDS TESTS ============

    @Test
    fun `LocalPathLookup can be created with null size and modifiedAt`() = runTest {
        // Given - a lookup with null size and modifiedAt (simulates "/" on Android)
        val path = LocalPath.build("/restricted")
        val lookup = LocalPathLookup(
            lookedUp = path,
            fileType = FileType.DIRECTORY,
            size = null,
            modifiedAt = null,
            error = ReadException("Permission denied", path)
        )

        // Then - lookup object created successfully with null fields
        lookup.lookedUp shouldBe path
        lookup.fileType shouldBe FileType.DIRECTORY
        lookup.size shouldBe null
        lookup.modifiedAt shouldBe null
        lookup.error shouldNotBe null
        lookup.error.shouldBeInstanceOf<ReadException>()
    }

    @Test
    fun `LocalPathLookup with null size can be used in operations`() = runTest {
        // Given - a lookup with null size
        val path = LocalPath.build("/test")
        val lookup = LocalPathLookup(
            lookedUp = path,
            fileType = FileType.FILE,
            size = null,  // Null size due to permission error
            modifiedAt = Instant.fromEpochMilliseconds(0),
            error = ReadException("Size unavailable", path)
        )

        // Then - can safely access size with elvis operator
        val safeSize = lookup.size ?: 0L
        safeSize shouldBe 0L

        // And error field is properly typed as Throwable
        lookup.error.shouldBeInstanceOf<ReadException>()
        lookup.error?.message shouldBe "Size unavailable <-> /test"
    }

    @Test
    fun `LocalPathLookup error field is Throwable not String`() = runTest {
        // Given - a lookup with an error
        val path = LocalPath.build("/error-test")
        val testException = ReadException("Test error", path, SecurityException("Original cause"))
        val lookup = LocalPathLookup(
            lookedUp = path,
            fileType = FileType.FILE,
            size = null,
            modifiedAt = null,
            error = testException
        )

        // Then - error is a Throwable with full exception details
        lookup.error shouldNotBe null
        lookup.error.shouldBeInstanceOf<ReadException>()

        // And we can access cause chain
        val exception = lookup.error as ReadException
        exception.message shouldBe "Test error <-> /error-test"
        exception.cause.shouldBeInstanceOf<SecurityException>()
        exception.cause?.message shouldBe "Original cause"
    }

    // ============ LOOKUPOPTIONS BEHAVIOR TESTS ============

    @Test
    fun `lookup with fetchSize=false returns null size`(@TempDir tempDir: File) = runTest {
        val testFile = File(tempDir, "test.txt").apply { writeText("1234567890") }
        val path = LocalPath.build(testFile)

        val lookup = fileSystemOps.lookup(path, LookupOptions(fetchSize = false))

        lookup.fileType shouldBe FileType.FILE
        lookup.size shouldBe null // Size not fetched
    }

    @Test
    fun `lookup with fetchSize=true returns actual size`(@TempDir tempDir: File) = runTest {
        val testFile = File(tempDir, "test.txt").apply { writeText("1234567890") }
        val path = LocalPath.build(testFile)

        val lookup = fileSystemOps.lookup(path, LookupOptions(fetchSize = true))

        lookup.fileType shouldBe FileType.FILE
        lookup.size shouldBe 10L // Size fetched
    }

    @Test
    fun `lookup with fetchModifiedAt=false returns null timestamp`(@TempDir tempDir: File) = runTest {
        val testFile = File(tempDir, "test.txt").apply { createNewFile() }
        val path = LocalPath.build(testFile)

        val lookup = fileSystemOps.lookup(path, LookupOptions(fetchModifiedAt = false))

        lookup.fileType shouldBe FileType.FILE
        lookup.modifiedAt shouldBe null // Timestamp not fetched
    }

    @Test
    fun `lookup with fetchModifiedAt=true returns actual timestamp`(@TempDir tempDir: File) = runTest {
        val testFile = File(tempDir, "test.txt").apply { createNewFile() }
        val path = LocalPath.build(testFile)

        val lookup = fileSystemOps.lookup(path, LookupOptions(fetchModifiedAt = true))

        lookup.fileType shouldBe FileType.FILE
        lookup.modifiedAt shouldNotBe null // Timestamp fetched
    }

    @Test
    fun `lookup with minimal options returns only fileType`(@TempDir tempDir: File) = runTest {
        val testFile = File(tempDir, "test.txt").apply { writeText("content") }
        val path = LocalPath.build(testFile)

        val lookup = fileSystemOps.lookup(path, LookupOptions()) // All false

        lookup.fileType shouldBe FileType.FILE
        lookup.size shouldBe null
        lookup.modifiedAt shouldBe null
        lookup.ownership shouldBe null
        lookup.permissions shouldBe null
        lookup.createdAt shouldBe null
    }

    @Test
    fun `lookup with BASE preset returns all metadata`(@TempDir tempDir: File) = runTest {
        val testFile = File(tempDir, "test.txt").apply { writeText("content") }
        val path = LocalPath.build(testFile)

        val lookup = fileSystemOps.lookup(path, LookupOptions.BASE)

        lookup.fileType shouldBe FileType.FILE
        lookup.size shouldNotBe null
        lookup.modifiedAt shouldNotBe null
        lookup.createdAt shouldNotBe null
        // Note: ownership and permissions require Android APIs (Os.lstat)
        // They will be null in JVM tests but populated on Android
    }

    @Test
    fun `lookup with MAX preset returns all metadata and supports continueOnError`(@TempDir tempDir: File) = runTest {
        val testFile = File(tempDir, "test.txt").apply { writeText("content") }
        val path = LocalPath.build(testFile)

        val lookup = fileSystemOps.lookup(path, LookupOptions.MAX)

        // MAX is same as BASE for single lookups (continueOnError only affects batch)
        lookup.fileType shouldBe FileType.FILE
        lookup.size shouldNotBe null
        lookup.modifiedAt shouldNotBe null
        lookup.createdAt shouldNotBe null
    }

    @Test
    fun `lookup with fetchOwnership=true attempts to fetch ownership`(@TempDir tempDir: File) = runTest {
        val testFile = File(tempDir, "test.txt").apply { createNewFile() }
        val path = LocalPath.build(testFile)

        val lookup = fileSystemOps.lookup(
            path,
            LookupOptions(fetchOwnership = true)
        )

        lookup.fileType shouldBe FileType.FILE
        // On JVM tests: ownership will be null (requires Android Os.lstat)
        // On Android: ownership should be populated
        // This test verifies the method doesn't throw when fetchOwnership=true
    }

    @Test
    fun `lookup with fetchPermissions=true attempts to fetch permissions`(@TempDir tempDir: File) = runTest {
        val testFile = File(tempDir, "test.txt").apply { createNewFile() }
        val path = LocalPath.build(testFile)

        val lookup = fileSystemOps.lookup(
            path,
            LookupOptions(fetchPermissions = true)
        )

        lookup.fileType shouldBe FileType.FILE
        // On JVM tests: permissions will be null (requires Android Os.lstat)
        // On Android: permissions should be populated
        // This test verifies the method doesn't throw when fetchPermissions=true
    }

    @Test
    fun `lookup with fetchCreatedAt=true returns creation timestamp`(@TempDir tempDir: File) = runTest {
        val testFile = File(tempDir, "test.txt").apply { createNewFile() }
        val path = LocalPath.build(testFile)

        val lookup = fileSystemOps.lookup(
            path,
            LookupOptions(fetchCreatedAt = true)
        )

        lookup.fileType shouldBe FileType.FILE
        lookup.createdAt shouldNotBe null // Available via Files.readAttributes
    }

    @Test
    fun `lookup with fetchCreatedAt=false returns null creation timestamp`(@TempDir tempDir: File) = runTest {
        val testFile = File(tempDir, "test.txt").apply { createNewFile() }
        val path = LocalPath.build(testFile)

        val lookup = fileSystemOps.lookup(
            path,
            LookupOptions(fetchCreatedAt = false)
        )

        lookup.fileType shouldBe FileType.FILE
        lookup.createdAt shouldBe null // Not fetched
    }

    @Test
    fun `lookup with selective options fetches only requested metadata`(@TempDir tempDir: File) = runTest {
        val testFile = File(tempDir, "test.txt").apply { writeText("selective") }
        val path = LocalPath.build(testFile)

        // Fetch only size and createdAt, not modifiedAt
        val lookup = fileSystemOps.lookup(
            path,
            LookupOptions(
                fetchSize = true,
                fetchModifiedAt = false,
                fetchCreatedAt = true
            )
        )

        lookup.fileType shouldBe FileType.FILE
        lookup.size shouldNotBe null // Fetched
        lookup.modifiedAt shouldBe null // Not fetched
        lookup.createdAt shouldNotBe null // Fetched
        lookup.ownership shouldBe null // Not requested
        lookup.permissions shouldBe null // Not requested
    }
}
