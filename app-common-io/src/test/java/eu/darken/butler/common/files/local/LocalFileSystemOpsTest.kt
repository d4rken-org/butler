package eu.darken.butler.common.files.local

import eu.darken.butler.common.files.Existence
import eu.darken.butler.common.files.LocalPath
import eu.darken.butler.common.files.LookupOptions
import eu.darken.butler.common.files.MoveOutcome
import eu.darken.butler.common.files.errors.PathAlreadyExistsException
import eu.darken.butler.common.files.errors.ReadException
import eu.darken.butler.common.files.errors.WriteException
import eu.darken.butler.common.files.metadata.FileType
import eu.darken.butler.common.files.metadata.OwnershipResolver
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.longs.shouldBeGreaterThan
import io.kotest.matchers.longs.shouldBeGreaterThanOrEqual
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkConstructor
import io.mockk.spyk
import io.mockk.unmockkConstructor
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import testhelpers.BaseTest
import java.io.File
import java.io.IOException
import java.nio.file.AccessDeniedException
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.NoSuchFileException
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
    fun `lookupFiles with continueOnError=true keeps an unreadable child as UNKNOWN`(@TempDir tempDir: File) = runTest {
        File(tempDir, "good1.txt").apply { writeText("a") }
        File(tempDir, "unreadable.txt").apply { writeText("b") }
        File(tempDir, "good2.txt").apply { writeText("c") }
        val failingChild = LocalPath.build(tempDir, "unreadable.txt")

        val spyOps = spyk(fileSystemOps)
        coEvery { spyOps.lookup(failingChild, any()) } throws ReadException(path = failingChild)

        val lookups = spyOps.lookupFiles(
            LocalPath.build(tempDir),
            LookupOptions.BASE.copy(continueOnError = true),
        )

        // Still exists but can't be read: stays visible as UNKNOWN instead of silently vanishing
        lookups.map { it.lookedUp.name } shouldContainExactlyInAnyOrder listOf(
            "good1.txt",
            "good2.txt",
            "unreadable.txt",
        )
        val unreadable = lookups.single { it.lookedUp.name == "unreadable.txt" }
        unreadable.fileType shouldBe FileType.UNKNOWN
        unreadable.error shouldNotBe null
    }

    @Test
    fun `lookupFiles with continueOnError=true silently skips a vanished child`(@TempDir tempDir: File) = runTest {
        File(tempDir, "good.txt").apply { writeText("a") }
        val vanishing = File(tempDir, "vanished.txt").apply { writeText("b") }
        val vanishedChild = LocalPath.build(vanishing)

        val spyOps = spyk(fileSystemOps)
        // The child disappears between the directory listing and its lookup
        coEvery { spyOps.lookup(vanishedChild, any()) } answers {
            vanishing.delete()
            throw ReadException(path = vanishedChild)
        }

        val lookups = spyOps.lookupFiles(
            LocalPath.build(tempDir),
            LookupOptions.BASE.copy(continueOnError = true),
        )

        lookups.map { it.lookedUp.name } shouldContainExactlyInAnyOrder listOf("good.txt")
    }

    @Test
    fun `lookupFiles with continueOnError=false fails the directory when a child fails lookup`(@TempDir tempDir: File) = runTest {
        File(tempDir, "good.txt").apply { writeText("a") }
        File(tempDir, "vanished.txt").apply { writeText("b") }
        val failingChild = LocalPath.build(tempDir, "vanished.txt")

        val spyOps = spyk(fileSystemOps)
        coEvery { spyOps.lookup(failingChild, any()) } throws ReadException(path = failingChild)

        shouldThrow<ReadException> {
            spyOps.lookupFiles(
                LocalPath.build(tempDir),
                LookupOptions.BASE.copy(continueOnError = false),
            )
        }
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
    fun `existsStrict reports an existing file as present`(@TempDir tempDir: File) = runTest {
        val testFile = File(tempDir, "test.txt").apply { createNewFile() }

        fileSystemOps.existsStrict(LocalPath.build(testFile)) shouldBe Existence.PRESENT
    }

    @Test
    fun `existsStrict reports a missing file as absent`(@TempDir tempDir: File) = runTest {
        fileSystemOps.existsStrict(LocalPath.build(tempDir, "non-existent.txt")) shouldBe Existence.ABSENT
    }

    /** The link itself is what is checked, so a target that is gone does not make the link absent. */
    @Test
    fun `existsStrict reports a dangling symlink as present`(@TempDir tempDir: File) = runTest {
        val link = File(tempDir, "dangling")
        Files.createSymbolicLink(link.toPath(), File(tempDir, "nowhere").toPath())

        fileSystemOps.existsStrict(LocalPath.build(link)) shouldBe Existence.PRESENT
    }

    @Test
    fun `a denied stat is not an absence`(@TempDir tempDir: File) = runTest {
        val path = LocalPath.build(tempDir, "secret.txt")

        fileSystemOps.classifyExistence(path, AccessDeniedException(path.path)) shouldBe Existence.UNKNOWN
        fileSystemOps.classifyExistence(path, NoSuchFileException(path.path)) shouldBe Existence.ABSENT
        fileSystemOps.classifyExistence(path, IOException("I/O error")) shouldBe Existence.UNKNOWN
        fileSystemOps.classifyExistence(path, SecurityException("no")) shouldBe Existence.UNKNOWN
    }

    /** The same on a real file system, where the denial comes from the kernel rather than a stub. */
    @Test
    fun `a stat denied by the file system is not an absence`(@TempDir tempDir: File) = runTest {
        assumeTrue(FileSystems.getDefault().supportedFileAttributeViews().contains("posix"))
        val locked = File(tempDir, "locked").apply { mkdirs() }
        val target = File(locked, "secret.txt").apply { writeText("content") }
        val originalPermissions = Files.getPosixFilePermissions(locked.toPath())

        try {
            Files.setPosixFilePermissions(locked.toPath(), emptySet())
            // Root ignores the permission bits, there is nothing to observe then.
            assumeTrue(!Files.isReadable(locked.toPath()))

            fileSystemOps.existsStrict(LocalPath.build(target)) shouldBe Existence.UNKNOWN
        } finally {
            Files.setPosixFilePermissions(locked.toPath(), originalPermissions)
        }
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
    fun `canonicalize resolves a symlink to its real path`(@TempDir tempDir: File) = runTest {
        val targetDir = File(tempDir, "realdir").apply { mkdirs() }
        val linkFile = File(tempDir, "link")
        java.nio.file.Files.createSymbolicLink(linkFile.toPath(), targetDir.toPath())

        val canonical = fileSystemOps.canonicalize(LocalPath.build(linkFile))

        canonical.path shouldBe targetDir.toPath().toRealPath().toString()
    }

    @Test
    fun `canonicalize returns a real directory as itself`(@TempDir tempDir: File) = runTest {
        val dir = File(tempDir, "plain").apply { mkdirs() }

        val canonical = fileSystemOps.canonicalize(LocalPath.build(dir))

        canonical.path shouldBe dir.toPath().toRealPath().toString()
    }

    @Test
    fun `canonicalize throws on a broken symlink`(@TempDir tempDir: File) = runTest {
        val linkFile = File(tempDir, "broken")
        java.nio.file.Files.createSymbolicLink(linkFile.toPath(), File(tempDir, "missing").toPath())

        shouldThrow<ReadException> {
            fileSystemOps.canonicalize(LocalPath.build(linkFile))
        }
    }

    @Test
    fun `canonicalize throws on a non-existent path`(@TempDir tempDir: File) = runTest {
        shouldThrow<ReadException> {
            fileSystemOps.canonicalize(LocalPath.build(File(tempDir, "nope")))
        }
    }

    @Test
    fun `move renames file`(@TempDir tempDir: File) = runTest {
        val sourceFile = File(tempDir, "source.txt").apply { writeText("content") }
        val sourcePath = LocalPath.build(sourceFile)
        val destPath = LocalPath.build(tempDir, "dest.txt")

        val result = fileSystemOps.move(sourcePath, destPath)

        result shouldBe MoveOutcome.Moved
        sourceFile.exists() shouldBe false
        destPath.file.exists() shouldBe true
        destPath.file.readText() shouldBe "content"
    }

    @Test
    fun `move refuses existing destination and leaves both files untouched`(@TempDir tempDir: File) = runTest {
        val sourceFile = File(tempDir, "source.txt").apply { writeText("source content") }
        val destFile = File(tempDir, "dest.txt").apply { writeText("dest content") }
        val sourcePath = LocalPath.build(sourceFile)
        val destPath = LocalPath.build(destFile)

        val result = fileSystemOps.move(sourcePath, destPath)

        result.shouldBeInstanceOf<MoveOutcome.NotSupported>()
        sourceFile.readText() shouldBe "source content"
        destFile.readText() shouldBe "dest content"
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
    fun `openOutputStream after createFile should truncate existing file`(@TempDir tempDir: File) = runTest {
        // Given - create an empty file first (simulates GenericCrossTypeCopyStrategy behavior)
        val path = LocalPath.build(tempDir, "file.txt")
        fileSystemOps.createFile(path)

        // When - open output stream with append=false on existing file
        val outputStream = fileSystemOps.openOutputStream(path, append = false)
        outputStream.write("new content".toByteArray())
        outputStream.close()

        // Then - should not throw FileAlreadyExistsException and should contain new content
        path.file.readText() shouldBe "new content"
    }

    @Test
    fun `openOutputStream with append false truncates existing content`(@TempDir tempDir: File) = runTest {
        // Given - file with existing content
        val testFile = File(tempDir, "existing.txt").apply { writeText("old content here") }
        val path = LocalPath.build(testFile)

        // When - open with append=false and write shorter content
        val outputStream = fileSystemOps.openOutputStream(path, append = false)
        outputStream.write("new".toByteArray())
        outputStream.close()

        // Then - should be truncated to new content only
        testFile.readText() shouldBe "new"
    }

    @Test
    fun `openOutputStream throws when path exists as directory`(@TempDir tempDir: File) = runTest {
        // Given - path exists as a directory
        val path = LocalPath.build(tempDir, "subdir")
        fileSystemOps.createDir(path)

        // When/Then - should throw WriteException (can't write to directory)
        shouldThrow<WriteException> {
            fileSystemOps.openOutputStream(path, append = false)
        }
    }

    @Test
    fun `openOutputStream follows symlink to file`(@TempDir tempDir: File) = runTest {
        // Given - symlink pointing to a file
        val targetFile = File(tempDir, "target.txt").apply { writeText("old") }
        val target = LocalPath.build(targetFile)
        val link = LocalPath.build(tempDir, "link.txt")
        fileSystemOps.createSymlink(link, target)

        // When - open stream via symlink with append=false
        val outputStream = fileSystemOps.openOutputStream(link, append = false)
        outputStream.write("new".toByteArray())
        outputStream.close()

        // Then - target file should be truncated
        targetFile.readText() shouldBe "new"
    }

    @Test
    fun `openOutputStream throws when symlink points to directory`(@TempDir tempDir: File) = runTest {
        // Given - symlink pointing to a directory
        val targetDir = File(tempDir, "targetdir").apply { mkdirs() }
        val target = LocalPath.build(targetDir)
        val link = LocalPath.build(tempDir, "link")
        fileSystemOps.createSymlink(link, target)

        // When/Then - should throw WriteException (follows symlink, can't write to directory)
        shouldThrow<WriteException> {
            fileSystemOps.openOutputStream(link, append = false)
        }
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
            error = "Permission denied <-> /restricted"
        )

        // Then - lookup object created successfully with null fields
        lookup.lookedUp shouldBe path
        lookup.fileType shouldBe FileType.DIRECTORY
        lookup.size shouldBe null
        lookup.modifiedAt shouldBe null
        lookup.error shouldNotBe null
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
            error = "Size unavailable <-> /test"
        )

        // Then - can safely access size with elvis operator
        val safeSize = lookup.size ?: 0L
        safeSize shouldBe 0L

        // And error field contains the error message
        lookup.error shouldBe "Size unavailable <-> /test"
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

    @Test
    fun `lookup - symlink reports its own size, not the target's`(@TempDir tempDir: File) = runTest {
        val target = File(tempDir, "target.bin").apply { writeBytes(ByteArray(5000)) }
        val link = File(tempDir, "link")
        java.nio.file.Files.createSymbolicLink(link.toPath(), target.toPath())

        val lookup = fileSystemOps.lookup(LocalPath.build(link), LookupOptions.BASE)

        lookup.fileType shouldBe FileType.SYMBOLIC_LINK
        // The link node, NOT the 5000-byte target — deletion only removes the link. (When lstat is
        // unavailable size is null; either way it must never be the target's size.)
        lookup.size shouldNotBe 5000L
        lookup.target shouldBe LocalPath.build(target.path)
    }

    @Test
    fun `lookup - relative symlink target is resolved against the link's parent`(@TempDir tempDir: File) = runTest {
        File(tempDir, "realfile").apply { writeText("x") }
        val link = File(tempDir, "rellink")
        java.nio.file.Files.createSymbolicLink(link.toPath(), java.nio.file.Paths.get("realfile")) // relative

        val lookup = fileSystemOps.lookup(LocalPath.build(link), LookupOptions.BASE)

        lookup.fileType shouldBe FileType.SYMBOLIC_LINK
        // Resolved to <parent>/realfile, not the old bogus "/realfile".
        lookup.target shouldBe LocalPath.build(File(tempDir, "realfile").path)
    }

    @Test
    fun `lookup - relative symlink target with parent traversal is normalized`(@TempDir tempDir: File) = runTest {
        File(tempDir, "realfile").apply { writeText("x") }
        val sub = File(tempDir, "sub").apply { mkdirs() }
        val link = File(sub, "uplink")
        java.nio.file.Files.createSymbolicLink(link.toPath(), java.nio.file.Paths.get("../realfile")) // traverses up

        val lookup = fileSystemOps.lookup(LocalPath.build(link), LookupOptions.BASE)

        lookup.fileType shouldBe FileType.SYMBOLIC_LINK
        // <sub>/../realfile is lexically normalized to <tempDir>/realfile, not left as "/sub/../realfile".
        lookup.target shouldBe LocalPath.build(File(tempDir, "realfile").path)
    }

    @Test
    fun `lookup - broken symlink is still a SYMBOLIC_LINK`(@TempDir tempDir: File) = runTest {
        val link = File(tempDir, "broken")
        java.nio.file.Files.createSymbolicLink(link.toPath(), File(tempDir, "does-not-exist").toPath())

        val lookup = fileSystemOps.lookup(LocalPath.build(link), LookupOptions.BASE)

        lookup.fileType shouldBe FileType.SYMBOLIC_LINK
        // The target is read from the link itself, so a missing target is still resolved & reported.
        lookup.target shouldBe LocalPath.build(File(tempDir, "does-not-exist").path)
    }
}
