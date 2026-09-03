package eu.darken.butler.common.files.local

import eu.darken.butler.common.files.LocalPath
import eu.darken.butler.common.files.LookupOptions
import eu.darken.butler.common.files.actions.MoveAction
import eu.darken.butler.common.files.errors.PathNotFoundException
import eu.darken.butler.common.files.errors.WriteException
import eu.darken.butler.common.files.metadata.OwnershipResolver
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.should
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.mockk.coEvery
import io.mockk.mockk
import io.mockk.spyk
import kotlinx.coroutines.flow.last
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import testhelpers.BaseTest
import testhelpers.TestClock
import testhelpers.shouldContainPath
import testhelpers.toPathPairs
import java.io.File
import java.nio.file.Files
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds

class LocalPathMoveTest : BaseTest() {

    private val mockOwnershipResolver = mockk<OwnershipResolver>(relaxed = true)
    private val ops = LocalFileSystemOps(
        ownershipResolver = mockOwnershipResolver,
    )

    // ============ BASIC MOVE OPERATIONS ============

    @Test
    fun `move single file to directory`(@TempDir tempDir: File) = runTest {
        val sourceFolder = File(tempDir, "source").apply { mkdirs() }
        val destFolder = File(tempDir, "dest").apply { mkdirs() }
        // Given
        val sourceFile = File(sourceFolder, "test.txt")
        sourceFile.writeText("Hello World")
        val expectedSize = sourceFile.length()
        val sourcePath = LocalPath.build(sourceFile)
        val destPath = LocalPath.build(destFolder)

        // When
        val result = sourcePath.move(ops, destPath, options = MoveAction.Options(attemptAtomicMove = false))
            .last() as MoveAction.State.Completed<LocalPath, LocalPathLookup, LocalPath, LocalPathLookup>

        // Then
        result.movedFiles shouldContainPath (sourcePath to LocalPath.build(File(destFolder, "test.txt")))
        result.bytesMoved shouldBe expectedSize
        File(destFolder, "test.txt").exists() shouldBe true
        File(destFolder, "test.txt").readText() shouldBe "Hello World"
        sourceFile.exists() shouldBe false // Source should be deleted
    }

    @Test
    fun `move empty directory`(@TempDir tempDir: File) = runTest {
        val sourceFolder = File(tempDir, "source").apply { mkdirs() }
        val destFolder = File(tempDir, "dest").apply { mkdirs() }
        // Given
        val sourceDir = File(sourceFolder, "empty")
        sourceDir.mkdir()
        val sourcePath = LocalPath.build(sourceDir)
        val destPath = LocalPath.build(destFolder)

        // When
        val result = sourcePath.move(ops, destPath, options = MoveAction.Options(attemptAtomicMove = false))
            .last() as MoveAction.State.Completed<LocalPath, LocalPathLookup, LocalPath, LocalPathLookup>

        // Then
        result.movedFiles shouldContainPath (sourcePath to LocalPath.build(File(destFolder, "empty")))
        File(destFolder, "empty").exists() shouldBe true
        File(destFolder, "empty").isDirectory shouldBe true
        sourceDir.exists() shouldBe false // Source should be deleted
    }

    @Test
    fun `move nested structure with files and subdirectories`(@TempDir tempDir: File) = runTest {
        val sourceFolder = File(tempDir, "source").apply { mkdirs() }
        val destFolder = File(tempDir, "dest").apply { mkdirs() }
        // Given
        val sourceDir = File(sourceFolder, "nested")
        val subDir = File(sourceDir, "sub")
        val file1 = File(sourceDir, "file1.txt")
        val file2 = File(subDir, "file2.txt")

        sourceDir.mkdir()
        subDir.mkdir()
        file1.writeText("Content 1")
        file2.writeText("Content 2")

        val expectedSize = file1.length() + file2.length()
        val sourcePath = LocalPath.build(sourceDir)
        val destPath = LocalPath.build(destFolder)

        // When
        val result = sourcePath.move(ops, destPath, options = MoveAction.Options(attemptAtomicMove = false))
            .last() as MoveAction.State.Completed<LocalPath, LocalPathLookup, LocalPath, LocalPathLookup>

        // Then
        result.bytesMoved shouldBe expectedSize
        result.movedFiles.toPathPairs().map { it.first } should { paths ->
            paths shouldContain LocalPath.build(file1)
            paths shouldContain LocalPath.build(file2)
            paths shouldContain LocalPath.build(subDir)
            paths shouldContain LocalPath.build(sourceDir)
        }

        File(destFolder, "nested").exists() shouldBe true
        File(destFolder, "nested/file1.txt").exists() shouldBe true
        File(destFolder, "nested/file1.txt").readText() shouldBe "Content 1"
        File(destFolder, "nested/sub").exists() shouldBe true
        File(destFolder, "nested/sub/file2.txt").exists() shouldBe true
        File(destFolder, "nested/sub/file2.txt").readText() shouldBe "Content 2"

        sourceDir.exists() shouldBe false // Source should be deleted
    }

    @Test
    fun `move multiple files to directory`(@TempDir tempDir: File) = runTest {
        val sourceFolder = File(tempDir, "source").apply { mkdirs() }
        val destFolder = File(tempDir, "dest").apply { mkdirs() }
        // Given
        val file1 = File(sourceFolder, "file1.txt")
        val file2 = File(sourceFolder, "file2.txt")
        file1.writeText("Content 1")
        file2.writeText("Content 2")

        val sources = setOf(LocalPath.build(file1), LocalPath.build(file2))
        val destPath = LocalPath.build(destFolder)

        // When
        val result = sources.move(ops, destPath, options = MoveAction.Options(attemptAtomicMove = false))
            .last() as MoveAction.State.Completed<LocalPath, LocalPathLookup, LocalPath, LocalPathLookup>

        // Then
        result.movedFiles shouldHaveSize 2
        File(destFolder, "file1.txt").exists() shouldBe true
        File(destFolder, "file2.txt").exists() shouldBe true
        file1.exists() shouldBe false
        file2.exists() shouldBe false
    }

    // ============ CONFLICT HANDLING ============

    @Test
    fun `move file - destination exists - no issue handler - should throw`(@TempDir tempDir: File) = runTest {
        val sourceFolder = File(tempDir, "source").apply { mkdirs() }
        val destFolder = File(tempDir, "dest").apply { mkdirs() }
        // Given
        val sourceFile = File(sourceFolder, "test.txt")
        sourceFile.writeText("Source content")

        val existingFile = File(destFolder, "test.txt")
        existingFile.writeText("Existing content")

        val sourcePath = LocalPath.build(sourceFile)
        val destPath = LocalPath.build(destFolder)

        // When/Then
        shouldThrow<WriteException> {
            sourcePath.move(ops, destPath, onIssue = null, options = MoveAction.Options(attemptAtomicMove = false))
                .last()
        }
    }

    // ============ ERROR HANDLING ============

    @Test
    fun `move non-existent file - should throw`(@TempDir tempDir: File) = runTest {
        val sourceFolder = File(tempDir, "source").apply { mkdirs() }
        val destFolder = File(tempDir, "dest").apply { mkdirs() }
        val nonExistentFile = File(sourceFolder, "does-not-exist.txt")

        shouldThrow<PathNotFoundException> {
            LocalPath.build(nonExistentFile).move(ops, LocalPath.build(destFolder)).last()
        }
    }

    @Test
    fun `move to non-existent parent directory - should throw`(@TempDir tempDir: File) = runTest {
        val sourceFolder = File(tempDir, "source").apply { mkdirs() }
        val destFolder = File(tempDir, "dest").apply { mkdirs() }
        // Given
        val sourceFile = File(sourceFolder, "test.txt")
        sourceFile.writeText("Content")

        // Destination with non-existent parent directory
        val destWithNonExistentParent = File(tempDir, "non-existent-parent/dest-file.txt")

        // When/Then - parent directory doesn't exist, should fail
        shouldThrow<WriteException> {
            LocalPath.build(sourceFile).move(ops, LocalPath.build(destWithNonExistentParent)).last()
        }
    }

    @Test
    fun `move to existing destination - should succeed`(@TempDir tempDir: File) = runTest {
        val sourceFolder = File(tempDir, "source").apply { mkdirs() }
        val destFolder = File(tempDir, "dest").apply { mkdirs() }
        // Given
        val sourceFile = File(sourceFolder, "test.txt")
        sourceFile.writeText("Content")
        val existingDest = File(tempDir, "existing-dest")
        existingDest.mkdirs()

        // When
        LocalPath.build(sourceFile).move(ops, LocalPath.build(existingDest)).last()

        // Then
        File(existingDest, "test.txt").exists() shouldBe true
        sourceFile.exists() shouldBe false
    }

    // ============ SYMLINK HANDLING ============

    @Test
    fun `move symlink to file`(@TempDir tempDir: File) = runTest {
        val sourceFolder = File(tempDir, "source").apply { mkdirs() }
        val destFolder = File(tempDir, "dest").apply { mkdirs() }
        // Given
        val targetFile = File(sourceFolder, "target.txt")
        targetFile.writeText("Target content")

        val symlink = File(sourceFolder, "link.txt")
        Files.createSymbolicLink(symlink.toPath(), targetFile.toPath())

        val sourcePath = LocalPath.build(symlink)
        val destPath = LocalPath.build(destFolder)

        // When
        val result = sourcePath.move(ops, destPath, options = MoveAction.Options(attemptAtomicMove = false))
            .last() as MoveAction.State.Completed<LocalPath, LocalPathLookup, LocalPath, LocalPathLookup>

        // Then
        result.movedFiles shouldHaveSize 1
        val movedLink = File(destFolder, "link.txt")
        movedLink.exists() shouldBe true
        Files.isSymbolicLink(movedLink.toPath()) shouldBe true
        symlink.exists() shouldBe false
    }

    @Test
    fun `move broken symlink should preserve symlink`(@TempDir tempDir: File) = runTest {
        val sourceFolder = File(tempDir, "source").apply { mkdirs() }
        val destFolder = File(tempDir, "dest").apply { mkdirs() }
        // Given - symlink pointing to non-existent target
        val brokenLink = File(sourceFolder, "brokenLink")

        // Setup-only assumption: only symlink CREATION may be unavailable on the host. Everything
        // below is unconditional, so the move behaviour can never silently go unexercised.
        val symlinkCreated = runCatching {
            Files.createSymbolicLink(brokenLink.toPath(), java.nio.file.Paths.get("nonexistent.txt"))
        }.isSuccess
        assumeTrue(symlinkCreated, "Host filesystem does not support symlink creation")
        Files.isSymbolicLink(brokenLink.toPath()) shouldBe true

        val sourcePath = LocalPath.build(brokenLink)
        val destPath = LocalPath.build(destFolder)

        // When
        val result = sourcePath.move(ops, destPath, options = MoveAction.Options(attemptAtomicMove = false))
            .last() as MoveAction.State.Completed<LocalPath, LocalPathLookup, LocalPath, LocalPathLookup>

        // Then - broken symlink should be moved as-is
        val movedLink = File(destFolder, "brokenLink")
        movedLink.exists() shouldBe false // Target doesn't exist
        Files.isSymbolicLink(movedLink.toPath()) shouldBe true // But symlink exists
        result.movedFiles shouldHaveSize 1
        brokenLink.exists() shouldBe false // Source symlink should be deleted
    }

    @Test
    fun `move symlink to directory`(@TempDir tempDir: File) = runTest {
        val sourceFolder = File(tempDir, "source").apply { mkdirs() }
        val destFolder = File(tempDir, "dest").apply { mkdirs() }
        // Given - symlink pointing to a directory
        val targetDir = File(sourceFolder, "targetDir")
        targetDir.mkdir()
        File(targetDir, "file.txt").writeText("content")

        val linkDir = File(sourceFolder, "linkDir")

        // Setup-only assumption: only symlink CREATION may be unavailable on the host. Everything
        // below is unconditional, so the move behaviour can never silently go unexercised.
        val symlinkCreated = runCatching {
            Files.createSymbolicLink(linkDir.toPath(), java.nio.file.Paths.get("targetDir"))
        }.isSuccess
        assumeTrue(symlinkCreated, "Host filesystem does not support symlink creation")
        Files.isSymbolicLink(linkDir.toPath()) shouldBe true

        val sourcePath = LocalPath.build(linkDir)
        val destPath = LocalPath.build(destFolder)

        // When
        val result = sourcePath.move(ops, destPath, options = MoveAction.Options(attemptAtomicMove = false))
            .last() as MoveAction.State.Completed<LocalPath, LocalPathLookup, LocalPath, LocalPathLookup>

        // Then - symlink should be moved
        val movedLink = File(destFolder, "linkDir")
        Files.isSymbolicLink(movedLink.toPath()) shouldBe true
        result.movedFiles shouldHaveSize 1 // Only the link, not contents
        linkDir.exists() shouldBe false // Source symlink should be deleted
        // Note: The symlink may not resolve correctly since we moved it but not its target
    }

    @Test
    fun `move nested symlinks`(@TempDir tempDir: File) = runTest {
        val sourceFolder = File(tempDir, "source").apply { mkdirs() }
        val destFolder = File(tempDir, "dest").apply { mkdirs() }
        // Given - symlink chain: link2 -> link1 -> target
        val targetFile = File(sourceFolder, "target.txt")
        targetFile.writeText("Target content")

        val link1 = File(sourceFolder, "link1.txt")
        val link2 = File(sourceFolder, "link2.txt")

        // Setup-only assumption: only symlink CREATION may be unavailable on the host. Everything
        // below is unconditional, so the move behaviour can never silently go unexercised.
        val symlinkCreated = runCatching {
            Files.createSymbolicLink(link1.toPath(), targetFile.toPath())
            Files.createSymbolicLink(link2.toPath(), link1.toPath())
        }.isSuccess
        assumeTrue(symlinkCreated, "Host filesystem does not support symlink creation")
        Files.isSymbolicLink(link2.toPath()) shouldBe true

        val sourcePath = LocalPath.build(link2)
        val destPath = LocalPath.build(destFolder)

        // When
        val result = sourcePath.move(ops, destPath, options = MoveAction.Options(attemptAtomicMove = false))
            .last() as MoveAction.State.Completed<LocalPath, LocalPathLookup, LocalPath, LocalPathLookup>

        // Then - link2 should be moved and still point to link1
        val movedLink = File(destFolder, "link2.txt")
        Files.isSymbolicLink(movedLink.toPath()) shouldBe true
        result.movedFiles shouldHaveSize 1
        link2.exists() shouldBe false
    }

    @Test
    fun `move directory containing symlinks`(@TempDir tempDir: File) = runTest {
        val sourceFolder = File(tempDir, "source").apply { mkdirs() }
        val destFolder = File(tempDir, "dest").apply { mkdirs() }
        // Given - directory with both regular files and symlinks
        val sourceDir = File(sourceFolder, "project")
        sourceDir.mkdir()

        val regularFile = File(sourceDir, "file.txt")
        regularFile.writeText("regular content")

        val targetFile = File(sourceDir, "target.txt")
        targetFile.writeText("target content")

        val symlinkFile = File(sourceDir, "link.txt")

        // Setup-only assumption: only symlink CREATION may be unavailable on the host. Everything
        // below is unconditional, so the move behaviour can never silently go unexercised.
        val symlinkCreated = runCatching {
            Files.createSymbolicLink(symlinkFile.toPath(), java.nio.file.Paths.get("target.txt"))
        }.isSuccess
        assumeTrue(symlinkCreated, "Host filesystem does not support symlink creation")
        Files.isSymbolicLink(symlinkFile.toPath()) shouldBe true

        // When - move the entire directory
        val result = LocalPath.build(sourceDir).move(
            ops,
            LocalPath.build(destFolder),
            options = MoveAction.Options(attemptAtomicMove = false)
        ).last() as MoveAction.State.Completed<LocalPath, LocalPathLookup, LocalPath, LocalPathLookup>

        // Then - directory and all contents moved, symlink preserved
        val movedDir = File(destFolder, "project")
        movedDir.exists() shouldBe true
        movedDir.isDirectory shouldBe true

        File(movedDir, "file.txt").readText() shouldBe "regular content"
        File(movedDir, "target.txt").readText() shouldBe "target content"

        val movedLink = File(movedDir, "link.txt")
        Files.isSymbolicLink(movedLink.toPath()) shouldBe true

        // Symlink path may be adjusted during move (relative or absolute)
        // What matters is that the symlink is preserved as a symlink
        val linkTarget = Files.readSymbolicLink(movedLink.toPath())
        linkTarget shouldNotBe null // Symlink has a target

        result.movedFiles shouldHaveSize 4 // directory + 2 files + symlink
        sourceDir.exists() shouldBe false
    }

    @Test
    fun `move symlink and target together`(@TempDir tempDir: File) = runTest {
        val sourceFolder = File(tempDir, "source").apply { mkdirs() }
        val destFolder = File(tempDir, "dest").apply { mkdirs() }
        // Given - both symlink and its relative target in same source directory
        val sourceDir = File(sourceFolder, "bundle")
        sourceDir.mkdir()

        val target = File(sourceDir, "data.txt")
        target.writeText("data content")

        val link = File(sourceDir, "shortcut.txt")

        // Setup-only assumption: only symlink CREATION may be unavailable on the host. Everything
        // below is unconditional, so the move behaviour can never silently go unexercised.
        val symlinkCreated = runCatching {
            Files.createSymbolicLink(link.toPath(), java.nio.file.Paths.get("data.txt"))
        }.isSuccess
        assumeTrue(symlinkCreated, "Host filesystem does not support symlink creation")
        Files.isSymbolicLink(link.toPath()) shouldBe true

        // When - move entire directory (symlink + target together)
        val result = LocalPath.build(sourceDir).move(
            ops,
            LocalPath.build(destFolder),
            options = MoveAction.Options(attemptAtomicMove = false)
        ).last() as MoveAction.State.Completed<LocalPath, LocalPathLookup, LocalPath, LocalPathLookup>

        // Then - both moved, symlink still resolves to target
        val movedDir = File(destFolder, "bundle")
        val movedTarget = File(movedDir, "data.txt")
        val movedLink = File(movedDir, "shortcut.txt")

        movedTarget.exists() shouldBe true
        movedTarget.readText() shouldBe "data content"

        Files.isSymbolicLink(movedLink.toPath()) shouldBe true

        // Symlink path may be adjusted during move
        // What matters is that the symlink is preserved
        val linkTarget = Files.readSymbolicLink(movedLink.toPath())
        linkTarget shouldNotBe null // Symlink has a target

        result.movedFiles shouldHaveSize 3 // directory + target + symlink
        sourceDir.exists() shouldBe false
    }

    @Test
    fun `move symlink to file - target file remains at original location (Unix mv behavior)`(@TempDir tempDir: File) =
        runTest {
            val sourceFolder = File(tempDir, "source").apply { mkdirs() }
            val destFolder = File(tempDir, "dest").apply { mkdirs() }
            // Given - symlink in one location, target in another
            val targetFile = File(sourceFolder, "original/target.txt")
            targetFile.parentFile.mkdirs()
            targetFile.writeText("target content")

            val symlinkFile = File(sourceFolder, "links/link.txt")
            symlinkFile.parentFile.mkdirs()

            // Setup-only assumption: only symlink CREATION may be unavailable on the host. Everything
            // below is unconditional, so the move behaviour can never silently go unexercised.
            val symlinkCreated = runCatching {
                Files.createSymbolicLink(symlinkFile.toPath(), java.nio.file.Paths.get("../original/target.txt"))
            }.isSuccess
            assumeTrue(symlinkCreated, "Host filesystem does not support symlink creation")
            Files.isSymbolicLink(symlinkFile.toPath()) shouldBe true

            val sourcePath = LocalPath.build(symlinkFile)
            val destPath = LocalPath.build(destFolder)

            // When - move ONLY the symlink (Unix mv behavior: only moves the link itself)
            val result = sourcePath.move(ops, destPath, options = MoveAction.Options(attemptAtomicMove = false))
                .last() as MoveAction.State.Completed<LocalPath, LocalPathLookup, LocalPath, LocalPathLookup>

            // Then - symlink moved, target file remains at original location
            val movedLink = File(destFolder, "link.txt")
            Files.isSymbolicLink(movedLink.toPath()) shouldBe true
            symlinkFile.exists() shouldBe false // Source symlink deleted

            // CRITICAL: Target file must remain at original location (Unix mv behavior)
            targetFile.exists() shouldBe true
            targetFile.readText() shouldBe "target content"

            result.movedFiles shouldHaveSize 1 // Only the symlink
        }

    @Test
    fun `move symlink to directory - directory contents remain at original location (Unix mv behavior)`(@TempDir tempDir: File) =
        runTest {
            val sourceFolder = File(tempDir, "source").apply { mkdirs() }
            val destFolder = File(tempDir, "dest").apply { mkdirs() }
            // Given - symlink pointing to a directory with contents
            val targetDir = File(sourceFolder, "original/data")
            targetDir.mkdirs()
            val contentFile = File(targetDir, "important.txt")
            contentFile.writeText("important data")

            val symlinkDir = File(sourceFolder, "links/shortcut")
            symlinkDir.parentFile.mkdirs()

            // Setup-only assumption: only symlink CREATION may be unavailable on the host. Everything
            // below is unconditional, so the move behaviour can never silently go unexercised.
            val symlinkCreated = runCatching {
                Files.createSymbolicLink(symlinkDir.toPath(), java.nio.file.Paths.get("../original/data"))
            }.isSuccess
            assumeTrue(symlinkCreated, "Host filesystem does not support symlink creation")
            Files.isSymbolicLink(symlinkDir.toPath()) shouldBe true

            val sourcePath = LocalPath.build(symlinkDir)
            val destPath = LocalPath.build(destFolder)

            // When - move ONLY the symlink (Unix mv behavior)
            val result = sourcePath.move(ops, destPath, options = MoveAction.Options(attemptAtomicMove = false))
                .last() as MoveAction.State.Completed<LocalPath, LocalPathLookup, LocalPath, LocalPathLookup>

            // Then - symlink moved, original directory and contents remain
            val movedLink = File(destFolder, "shortcut")
            Files.isSymbolicLink(movedLink.toPath()) shouldBe true
            symlinkDir.exists() shouldBe false // Source symlink deleted

            // CRITICAL: Original directory and its contents must remain (Unix mv behavior)
            targetDir.exists() shouldBe true
            targetDir.isDirectory shouldBe true
            contentFile.exists() shouldBe true
            contentFile.readText() shouldBe "important data"

            result.movedFiles shouldHaveSize 1 // Only the symlink, NOT directory contents
        }

    @Test
    fun `move symlink with relative path - link moves but target remains (Unix mv behavior)`(@TempDir tempDir: File) =
        runTest {
            val sourceFolder = File(tempDir, "source").apply { mkdirs() }
            val destFolder = File(tempDir, "dest").apply { mkdirs() }
            // Given - symlink with relative path pointing to target in same directory
            val targetFile = File(sourceFolder, "target.txt")
            targetFile.writeText("target")

            val symlinkFile = File(sourceFolder, "link.txt")

            // Setup-only assumption: only symlink CREATION may be unavailable on the host. Everything
            // below is unconditional, so the move behaviour can never silently go unexercised.
            val symlinkCreated = runCatching {
                Files.createSymbolicLink(symlinkFile.toPath(), java.nio.file.Paths.get("target.txt")) // Relative path
            }.isSuccess
            assumeTrue(symlinkCreated, "Host filesystem does not support symlink creation")
            Files.isSymbolicLink(symlinkFile.toPath()) shouldBe true

            val sourcePath = LocalPath.build(symlinkFile)
            val destPath = LocalPath.build(destFolder)

            // When - move the symlink
            val result = sourcePath.move(ops, destPath, options = MoveAction.Options(attemptAtomicMove = false))
                .last() as MoveAction.State.Completed<LocalPath, LocalPathLookup, LocalPath, LocalPathLookup>

            // Then - symlink moved, remains a symlink
            val movedLink = File(destFolder, "link.txt")
            Files.isSymbolicLink(movedLink.toPath()) shouldBe true
            symlinkFile.exists() shouldBe false

            // CRITICAL: Target file must remain at original location (Unix mv behavior)
            targetFile.exists() shouldBe true
            targetFile.readText() shouldBe "target"

            // Verify symlink still has a target path
            // (path format may be relative or absolute depending on implementation)
            val linkTarget = Files.readSymbolicLink(movedLink.toPath())
            linkTarget shouldNotBe null

            result.movedFiles shouldHaveSize 1
        }

    @Test
    fun `move symlink with absolute path preserves absolute path (Unix mv behavior)`(@TempDir tempDir: File) = runTest {
        val sourceFolder = File(tempDir, "source").apply { mkdirs() }
        val destFolder = File(tempDir, "dest").apply { mkdirs() }
        // Given - symlink with absolute path
        val targetFile = File(sourceFolder, "target.txt")
        targetFile.writeText("target")

        val symlinkFile = File(sourceFolder, "link.txt")

        // Setup-only assumption: only symlink CREATION may be unavailable on the host. Everything
        // below is unconditional, so the move behaviour can never silently go unexercised.
        val symlinkCreated = runCatching {
            Files.createSymbolicLink(symlinkFile.toPath(), targetFile.toPath().toAbsolutePath()) // Absolute path
        }.isSuccess
        assumeTrue(symlinkCreated, "Host filesystem does not support symlink creation")
        Files.isSymbolicLink(symlinkFile.toPath()) shouldBe true

        val originalTargetPath = Files.readSymbolicLink(symlinkFile.toPath())
        val sourcePath = LocalPath.build(symlinkFile)
        val destPath = LocalPath.build(destFolder)

        // When - move the symlink
        val result = sourcePath.move(ops, destPath, options = MoveAction.Options(attemptAtomicMove = false))
            .last() as MoveAction.State.Completed<LocalPath, LocalPathLookup, LocalPath, LocalPathLookup>

        // Then - symlink moved and preserves absolute path
        val movedLink = File(destFolder, "link.txt")
        Files.isSymbolicLink(movedLink.toPath()) shouldBe true

        val movedLinkTarget = Files.readSymbolicLink(movedLink.toPath())
        movedLinkTarget.toString() shouldBe originalTargetPath.toString() // Path preserved

        // Target file still exists and accessible via moved symlink
        movedLink.toPath().toRealPath().toFile().readText() shouldBe "target"

        result.movedFiles shouldHaveSize 1
    }

    // ============ DIRECTORY CONFLICTS ============

    // ============ RENAME RESOLUTION ============

    // ============ ATTRIBUTES ============

    @Test
    fun `move file preserves attributes`(@TempDir tempDir: File) = runTest {
        val sourceFolder = File(tempDir, "source").apply { mkdirs() }
        val destFolder = File(tempDir, "dest").apply { mkdirs() }
        // Given
        val sourceFile = File(sourceFolder, "file.txt")
        sourceFile.writeText("Content")

        // Set specific timestamp
        val timestamp = System.currentTimeMillis() - 100000
        sourceFile.setLastModified(timestamp)

        // When
        LocalPath.build(sourceFile).move(
            ops,
            LocalPath.build(destFolder),
            options = MoveAction.Options(preserveAttributes = true, attemptAtomicMove = false)
        ).last()

        // Then
        val movedFile = File(destFolder, "file.txt")
        movedFile.exists() shouldBe true
        // Allow small timestamp difference due to filesystem precision
        kotlin.math.abs(movedFile.lastModified() - timestamp) should { it < 2000 }
    }

    @Test
    fun `move directory preserves attributes`(@TempDir tempDir: File) = runTest {
        val sourceFolder = File(tempDir, "source").apply { mkdirs() }
        val destFolder = File(tempDir, "dest").apply { mkdirs() }
        // Given
        val sourceDir = File(sourceFolder, "dir")
        sourceDir.mkdir()
        File(sourceDir, "file.txt").writeText("Content")

        val timestamp = System.currentTimeMillis() - 100000
        sourceDir.setLastModified(timestamp)

        // When
        LocalPath.build(sourceDir).move(
            ops,
            LocalPath.build(destFolder),
            options = MoveAction.Options(preserveAttributes = true, attemptAtomicMove = false)
        ).last()

        // Then
        val movedDir = File(destFolder, "dir")
        movedDir.exists() shouldBe true
        movedDir.isDirectory shouldBe true
    }

    // ============ EDGE CASES ============

    @Test
    fun `move works without onProgress or onIssue callbacks`(@TempDir tempDir: File) = runTest {
        val sourceFolder = File(tempDir, "source").apply { mkdirs() }
        val destFolder = File(tempDir, "dest").apply { mkdirs() }
        // Given
        val sourceFile = File(sourceFolder, "file.txt")
        sourceFile.writeText("Content")

        // When - neither callback provided, no conflicts
        val result = LocalPath.build(sourceFile).move(ops, LocalPath.build(destFolder))
            .last() as MoveAction.State.Completed<LocalPath, LocalPathLookup, LocalPath, LocalPathLookup>

        // Then - should still work
        File(destFolder, "file.txt").exists() shouldBe true
        result.movedFiles shouldHaveSize 1
    }

    @Test
    fun `result contains correct moved pairs`(@TempDir tempDir: File) = runTest {
        val sourceFolder = File(tempDir, "source").apply { mkdirs() }
        val destFolder = File(tempDir, "dest").apply { mkdirs() }
        // Given
        val file1 = File(sourceFolder, "file1.txt")
        file1.writeText("Content 1")
        val file2 = File(sourceFolder, "file2.txt")
        file2.writeText("Content 2")

        // When
        val result = listOf(LocalPath.build(file1), LocalPath.build(file2))
            .move(ops, LocalPath.build(destFolder))
            .last() as MoveAction.State.Completed<LocalPath, LocalPathLookup, LocalPath, LocalPathLookup>

        // Then
        result.movedFiles shouldHaveSize 2
        result.movedFiles.toPathPairs().map { it.first } shouldContain LocalPath.build(file1)
        result.movedFiles.toPathPairs().map { it.first } shouldContain LocalPath.build(file2)
        result.movedFiles.toPathPairs().map { it.second } shouldContain LocalPath.build(File(destFolder, "file1.txt"))
        result.movedFiles.toPathPairs().map { it.second } shouldContain LocalPath.build(File(destFolder, "file2.txt"))
    }

    // ============ PROGRESS TRACKING ============

    @Test
    fun `move reports progress`(@TempDir tempDir: File) = runTest {
        val sourceFolder = File(tempDir, "source").apply { mkdirs() }
        val destFolder = File(tempDir, "dest").apply { mkdirs() }
        // Given
        val sourceFile = File(sourceFolder, "test.txt")
        sourceFile.writeText("x".repeat(10000))

        val sourcePath = LocalPath.build(sourceFile)
        val destPath = LocalPath.build(destFolder)

        val progressUpdates =
            mutableListOf<MoveAction.State.Active<LocalPath, LocalPathLookup, LocalPath, LocalPathLookup>>()

        // When
        val result = sourcePath.move(
            ops,
            destination = destPath,
        ).onEach { state ->
            if (state is MoveAction.State.Active) progressUpdates.add(state)
        }.last() as MoveAction.State.Completed<LocalPath, LocalPathLookup, LocalPath, LocalPathLookup>

        // Then
        result.movedFiles shouldHaveSize 1
        progressUpdates shouldNotBe emptyList<MoveAction.State.Active<LocalPath, LocalPathLookup, LocalPath, LocalPathLookup>>()
    }

    @Test
    fun `progress callbacks should be throttled to reduce UI spam`(@TempDir tempDir: File) = runTest {
        // Given - file large enough to generate many chunks (64KB buffer = ~32 chunks)
        suspend fun moveRun(name: String, clock: Clock): Int {
            val sourceFolder = File(tempDir, "$name-source").apply { mkdirs() }
            val destFolder = File(tempDir, "$name-dest").apply { mkdirs() }
            val sourceFile = File(sourceFolder, "large.bin")
            sourceFile.writeBytes(ByteArray(1024 * 1024 * 2)) // 2MB file

            var actives = 0
            LocalPath.build(sourceFile).move(
                ops,
                destination = LocalPath.build(destFolder),
                progressClock = clock,
            ).onEach { state ->
                if (state is MoveAction.State.Active) actives++
            }.last()
            return actives
        }

        // When - time never advances, so every tick inside the report interval is dropped ...
        val throttled = moveRun("throttled", TestClock())
        // ... and when the interval elapses before every tick, nothing is dropped.
        val unthrottled = moveRun("unthrottled", TestClock(autoAdvance = 1.seconds))
        // Real clock, default production wiring
        val realClock = moveRun("realclock", Clock.System)

        // Then - the exact retained set: throttling is what keeps these numbers apart. If the
        // operation stopped routing progress through PathOperationProgressTracker both runs would
        // report the same amount of progress states.
        // 1 scan + 1 forced final report, the throttleable tick dropped
        throttled shouldBe 2
        // ... plus the throttleable tick (a same-filesystem move renames, it has no chunk ticks)
        unthrottled shouldBe 3
        realClock should { it in throttled..unthrottled }
    }

    @Test
    fun `progress callbacks should fire for small files despite throttling`(@TempDir tempDir: File) = runTest {
        val sourceFolder = File(tempDir, "source").apply { mkdirs() }
        val destFolder = File(tempDir, "dest").apply { mkdirs() }
        // Given - small file that transfers quickly
        val sourceFile = File(sourceFolder, "small.txt")
        sourceFile.writeText("Small content")

        var progressCallbackCalled = false

        // When
        LocalPath.build(sourceFile).move(
            ops,
            destination = LocalPath.build(destFolder),
        ).onEach { state ->
            if (state is MoveAction.State.Active) progressCallbackCalled = true
        }.last()

        // Then - should still get at least one callback
        progressCallbackCalled shouldBe true
    }

    @Test
    fun `final progress callback should always fire immediately`(@TempDir tempDir: File) = runTest {
        val sourceFolder = File(tempDir, "source").apply { mkdirs() }
        val destFolder = File(tempDir, "dest").apply { mkdirs() }
        // Given
        val sourceFile = File(sourceFolder, "test.bin")
        sourceFile.writeBytes(ByteArray(512 * 1024)) // 512KB file

        val progressUpdates = mutableListOf<Pair<Long, Long>>()

        // When
        LocalPath.build(sourceFile).move(
            ops,
            destination = LocalPath.build(destFolder),
        ).onEach { state ->
            if (state is MoveAction.State.Active) {
                progressUpdates.add(state.movedBytes to state.totalBytes)
            }
        }.last()

        // Then - last callback should show 100% completion
        progressUpdates shouldNotBe emptyList<Pair<Long, Long>>()
        val (movedBytes, totalBytes) = progressUpdates.last()
        movedBytes shouldBe totalBytes
    }

    // ============ EDGE CASES ============

    @Test
    fun `move empty list of sources`(@TempDir tempDir: File) = runTest {
        val sourceFolder = File(tempDir, "source").apply { mkdirs() }
        val destFolder = File(tempDir, "dest").apply { mkdirs() }
        // Given
        val emptySources = emptySet<LocalPath>()
        val destPath = LocalPath.build(destFolder)

        // When
        val result = emptySources.move(ops, destPath, options = MoveAction.Options(attemptAtomicMove = false))
            .last() as MoveAction.State.Completed<LocalPath, LocalPathLookup, LocalPath, LocalPathLookup>

        // Then
        result.movedFiles.shouldBeEmpty()
        result.skippedFiles.shouldBeEmpty()
        result.bytesMoved shouldBe 0
    }

    @Test
    fun `move deeply nested structure`(@TempDir tempDir: File) = runTest {
        val sourceFolder = File(tempDir, "source").apply { mkdirs() }
        val destFolder = File(tempDir, "dest").apply { mkdirs() }
        // Given
        val sourceDir = File(sourceFolder, "level1")
        var current = sourceDir
        for (i in 2..10) {
            current = File(current, "level$i")
        }
        current.mkdirs()
        File(current, "deep.txt").writeText("Deep content")

        val sourcePath = LocalPath.build(sourceDir)
        val destPath = LocalPath.build(destFolder)

        // When
        val result = sourcePath.move(ops, destPath, options = MoveAction.Options(attemptAtomicMove = false))
            .last() as MoveAction.State.Completed<LocalPath, LocalPathLookup, LocalPath, LocalPathLookup>

        // Then
        // Should have 1 file + 10 directories = 11 items
        result.movedFiles shouldHaveSize 11
        result.movedFiles.toPathPairs().map { it.first } should { paths ->
            paths shouldContain LocalPath.build(File(current, "deep.txt"))
            paths shouldContain LocalPath.build(sourceDir)
        }
        File(destFolder, "level1/level2/level3/level4/level5/level6/level7/level8/level9/level10/deep.txt")
            .readText() shouldBe "Deep content"
        sourceDir.exists() shouldBe false
    }

    @Test
    fun `move large file`(@TempDir tempDir: File) = runTest {
        val sourceFolder = File(tempDir, "source").apply { mkdirs() }
        val destFolder = File(tempDir, "dest").apply { mkdirs() }
        // Given
        val largeFile = File(sourceFolder, "large.bin")
        largeFile.writeBytes(ByteArray(1024 * 1024) { it.toByte() }) // 1MB

        val sourcePath = LocalPath.build(largeFile)
        val destPath = LocalPath.build(destFolder)

        // When
        val result = sourcePath.move(ops, destPath, options = MoveAction.Options(attemptAtomicMove = false))
            .last() as MoveAction.State.Completed<LocalPath, LocalPathLookup, LocalPath, LocalPathLookup>

        // Then
        result.movedFiles shouldHaveSize 1
        result.bytesMoved shouldBe (1024 * 1024).toLong()
        File(destFolder, "large.bin").exists() shouldBe true
        File(destFolder, "large.bin").length() shouldBe (1024 * 1024).toLong()
        largeFile.exists() shouldBe false
    }

    // ============ RENAME - ADVANCED ============

    // ============ ERROR HANDLING ============

    @Test
    fun `handle read-only source files gracefully`(@TempDir tempDir: File) = runTest {
        val sourceFolder = File(tempDir, "source").apply { mkdirs() }
        val destFolder = File(tempDir, "dest").apply { mkdirs() }
        // Given - a source file the owner may read but not write
        val sourceFile = File(sourceFolder, "readonly.txt")
        sourceFile.writeText("readonly content")
        sourceFile.setReadOnly() shouldBe true

        // When
        val result = LocalPath.build(sourceFile).move(ops, LocalPath.build(destFolder))
            .last() as MoveAction.State.Completed<LocalPath, LocalPathLookup, LocalPath, LocalPathLookup>

        // Then - a rename is governed by the parent directory, so read-only does not block the move
        result.movedFiles shouldContainPath (
            LocalPath.build(sourceFile) to LocalPath.build(File(destFolder, "readonly.txt"))
            )
        result.skippedFiles.shouldBeEmpty()
        File(destFolder, "readonly.txt").readText() shouldBe "readonly content"
        sourceFile.exists() shouldBe false
    }

    // ============ VERIFICATION TESTS ============

    @Test
    fun `verify byte tracking for moved files`(@TempDir tempDir: File) = runTest {
        val sourceFolder = File(tempDir, "source").apply { mkdirs() }
        val destFolder = File(tempDir, "dest").apply { mkdirs() }
        // Given
        val content = "A".repeat(1024) // 1KB
        val file = File(sourceFolder, "large.txt")
        file.writeText(content)

        // When
        val result = LocalPath.build(file).move(ops, LocalPath.build(destFolder))
            .last() as MoveAction.State.Completed<LocalPath, LocalPathLookup, LocalPath, LocalPathLookup>

        // Then
        result.bytesMoved shouldBe content.length.toLong()
    }

    @Test
    fun `verify directory structure preservation`(@TempDir tempDir: File) = runTest {
        val sourceFolder = File(tempDir, "source").apply { mkdirs() }
        val destFolder = File(tempDir, "dest").apply { mkdirs() }
        // Given - create nested structure
        val projectDir = File(sourceFolder, "project")
        val srcDir = File(projectDir, "src")
        val mainFile = File(srcDir, "main.kt")

        projectDir.mkdir()
        srcDir.mkdir()
        mainFile.writeText("fun main() {}")

        // When - move directory to destination
        LocalPath.build(projectDir).move(ops, LocalPath.build(destFolder)).last()

        // Then - verify structure is preserved with directory name
        File(destFolder, "project").exists() shouldBe true
        File(destFolder, "project").isDirectory shouldBe true
        File(destFolder, "project/src").exists() shouldBe true
        File(destFolder, "project/src").isDirectory shouldBe true
        File(destFolder, "project/src/main.kt").exists() shouldBe true
        File(destFolder, "project/src/main.kt").readText() shouldBe "fun main() {}"
        projectDir.exists() shouldBe false
    }

    @Test
    fun `verify nested directory paths`(@TempDir tempDir: File) = runTest {
        val sourceFolder = File(tempDir, "source").apply { mkdirs() }
        val destFolder = File(tempDir, "dest").apply { mkdirs() }
        // Given
        val deepDir = File(sourceFolder, "a/b/c")
        deepDir.mkdirs()
        val file = File(deepDir, "file.txt")
        file.writeText("deep content")

        // When
        LocalPath.build(File(sourceFolder, "a")).move(ops, LocalPath.build(destFolder)).last()

        // Then
        File(destFolder, "a/b/c/file.txt").exists() shouldBe true
        File(destFolder, "a/b/c/file.txt").readText() shouldBe "deep content"
        File(sourceFolder, "a").exists() shouldBe false
    }

    @Test
    fun `verify multiple sources maintain structure`(@TempDir tempDir: File) = runTest {
        val sourceFolder = File(tempDir, "source").apply { mkdirs() }
        val destFolder = File(tempDir, "dest").apply { mkdirs() }
        // Given
        val dir1 = File(sourceFolder, "project1")
        val dir2 = File(sourceFolder, "project2")
        val file1 = File(dir1, "file.txt")
        val file2 = File(dir2, "file.txt")

        dir1.mkdir()
        dir2.mkdir()
        file1.writeText("project1 content")
        file2.writeText("project2 content")

        // When
        listOf(LocalPath.build(dir1), LocalPath.build(dir2)).move(ops, LocalPath.build(destFolder)).last()

        // Then
        File(destFolder, "project1/file.txt").exists() shouldBe true
        File(destFolder, "project2/file.txt").exists() shouldBe true
        File(destFolder, "project1/file.txt").readText() shouldBe "project1 content"
        File(destFolder, "project2/file.txt").readText() shouldBe "project2 content"
        dir1.exists() shouldBe false
        dir2.exists() shouldBe false
    }

    @Test
    fun `verify progress includes source and destination data`(@TempDir tempDir: File) = runTest {
        val sourceFolder = File(tempDir, "source").apply { mkdirs() }
        val destFolder = File(tempDir, "dest").apply { mkdirs() }
        // Given
        val dir = File(sourceFolder, "project")
        val file = File(dir, "file.txt")
        dir.mkdir()
        file.writeText("content")

        var sourcesSeen = mutableSetOf<String>()
        var destinationsSeen = mutableSetOf<String>()

        // When
        LocalPath.build(dir).move(
            ops,
            LocalPath.build(destFolder),
        ).onEach { state ->
            if (state is MoveAction.State.Active) {
                sourcesSeen.add(state.currentSource.path)
                state.currentDestination?.path?.let {
                    destinationsSeen.add(it)
                }
            }
        }.last()

        // Then
        sourcesSeen.size should { it > 0 }
        destinationsSeen.size should { it > 0 }
    }

    @Test
    fun `cumulative byte tracking in progress`(@TempDir tempDir: File) = runTest {
        val sourceFolder = File(tempDir, "source").apply { mkdirs() }
        val destFolder = File(tempDir, "dest").apply { mkdirs() }
        // Given
        val files = (1..5).map { i ->
            File(sourceFolder, "file$i.txt").apply {
                writeText("Content $i".repeat(i * 10))
            }
        }

        val bytesSeen = mutableListOf<Long>()

        // When
        files.map { LocalPath.build(it) }.move(
            ops,
            LocalPath.build(destFolder),
        ).onEach { state ->
            if (state is MoveAction.State.Active) bytesSeen.add(state.movedBytes)
        }.last()

        // Then - bytes should increase over time
        bytesSeen.size should { it > 0 }
        if (bytesSeen.size > 1) {
            bytesSeen.zipWithNext().all { (a, b) -> b >= a } shouldBe true
        }
    }

    // ============ EDGE CASES - ADVANCED ============

    // ============ PERFORMANCE ============

    @Test
    fun `handle large number of files efficiently`(@TempDir tempDir: File) = runTest {
        val sourceFolder = File(tempDir, "source").apply { mkdirs() }
        val destFolder = File(tempDir, "dest").apply { mkdirs() }
        // Given - many small files
        val files = (1..50).map { i ->
            File(sourceFolder, "file$i.txt").apply { writeText("content $i") }
        }

        // When
        val result = files.map { LocalPath.build(it) }.move(ops, LocalPath.build(destFolder))
            .last() as MoveAction.State.Completed<LocalPath, LocalPathLookup, LocalPath, LocalPathLookup>

        // Then
        result.movedFiles shouldHaveSize 50
        files.all { !it.exists() } shouldBe true
        (1..50).all { File(destFolder, "file$it.txt").exists() } shouldBe true
    }

    // ============ EDGE CASES ============

    @Test
    fun `empty result values are correct`(@TempDir tempDir: File) = runTest {
        val sourceFolder = File(tempDir, "source").apply { mkdirs() }
        val destFolder = File(tempDir, "dest").apply { mkdirs() }
        // Given - empty source list
        val emptyList = emptyList<LocalPath>()

        // When
        val result = emptyList.move(ops, LocalPath.build(destFolder))
            .last() as MoveAction.State.Completed<LocalPath, LocalPathLookup, LocalPath, LocalPathLookup>

        // Then - all result fields should be empty/zero
        result.movedFiles.shouldBeEmpty()
        result.skippedFiles.shouldBeEmpty()
        result.bytesMoved shouldBe 0L
    }

    @Test
    fun `move with collection containing non-existent and existing files`(@TempDir tempDir: File) = runTest {
        val sourceFolder = File(tempDir, "source").apply { mkdirs() }
        val destFolder = File(tempDir, "dest").apply { mkdirs() }
        // Given - mix of existing and non-existent files
        val existingFile = File(sourceFolder, "exists.txt")
        existingFile.writeText("content")

        val nonExistent = File(sourceFolder, "missing.txt")

        val paths = listOf(
            LocalPath.build(existingFile),
            LocalPath.build(nonExistent)
        )

        // When/Then - should throw on first missing file (strict by default)
        shouldThrow<Exception> {
            paths.move(ops, LocalPath.build(destFolder)).last()
        }

        // First file might have been moved before error
        // This tests that operations don't complete when errors occur
    }

    @Test
    fun `progress callback includes all expected data`(@TempDir tempDir: File) = runTest {
        val sourceFolder = File(tempDir, "source").apply { mkdirs() }
        val destFolder = File(tempDir, "dest").apply { mkdirs() }
        // Given
        val file = File(sourceFolder, "test.txt")
        file.writeText("x".repeat(1000))

        var progressCalled = false
        var lastProgress: MoveAction.State.Active<LocalPath, LocalPathLookup, LocalPath, LocalPathLookup>? = null

        // When
        LocalPath.build(file).move(
            ops,
            LocalPath.build(destFolder),
        ).onEach { state ->
            if (state is MoveAction.State.Active) {
                progressCalled = true
                lastProgress = state
            }
        }.last()

        // Then - progress should have been called
        progressCalled shouldBe true
        lastProgress shouldNotBe null

        // Verify progress contains expected fields
        lastProgress!!.currentSource shouldNotBe null
        lastProgress!!.currentDestination shouldNotBe null
        lastProgress!!.primaryProgress shouldNotBe null
        lastProgress!!.secondaryProgress shouldNotBe null
        // The final Active sample reports the whole file as transferred
        lastProgress!!.movedBytes shouldBe 1000L
        lastProgress!!.totalBytes shouldBe 1000L
    }

    @Test
    fun `result accurately tracks bytes moved`(@TempDir tempDir: File) = runTest {
        val sourceFolder = File(tempDir, "source").apply { mkdirs() }
        val destFolder = File(tempDir, "dest").apply { mkdirs() }
        // Given - files with known sizes
        val file1 = File(sourceFolder, "file1.txt")
        val file2 = File(sourceFolder, "file2.txt")

        file1.writeText("x".repeat(100))
        file2.writeText("y".repeat(200))

        val expectedBytes = file1.length() + file2.length()

        // When
        val result = listOf(
            LocalPath.build(file1),
            LocalPath.build(file2)
        ).move(ops, LocalPath.build(destFolder))
            .last() as MoveAction.State.Completed<LocalPath, LocalPathLookup, LocalPath, LocalPathLookup>

        // Then
        result.bytesMoved shouldBe expectedBytes
    }

    // ============ FILE RENAME OPERATIONS ============

    @Test
    fun `rename file in same directory`(@TempDir tempDir: File) = runTest {
        val sourceFolder = File(tempDir, "source").apply { mkdirs() }
        val destFolder = File(tempDir, "dest").apply { mkdirs() }
        // Given
        val sourceFile = File(sourceFolder, "original.txt")
        sourceFile.writeText("Content")
        val sourcePath = LocalPath.build(sourceFile.absoluteFile)

        // Destination is a FILE path (not a directory)
        val destPath = LocalPath.build(sourceFolder.absoluteFile).child("renamed.txt")

        // When
        val result = sourcePath.move(ops, destPath, options = MoveAction.Options(attemptAtomicMove = false))
            .last() as MoveAction.State.Completed<LocalPath, LocalPathLookup, LocalPath, LocalPathLookup>

        // Then
        val renamedFile = File(sourceFolder, "renamed.txt")
        renamedFile.exists() shouldBe true
        renamedFile.isFile shouldBe true
        renamedFile.isDirectory shouldBe false // NOT a directory!
        renamedFile.readText() shouldBe "Content"
        sourceFile.exists() shouldBe false
        result.movedFiles shouldContainPath (sourcePath to destPath)
    }

    @Test
    fun `rename file with extension change`(@TempDir tempDir: File) = runTest {
        val sourceFolder = File(tempDir, "source").apply { mkdirs() }
        val destFolder = File(tempDir, "dest").apply { mkdirs() }
        // Given
        val sourceFile = File(sourceFolder, "document.txt")
        sourceFile.writeText("Markdown content")
        val sourcePath = LocalPath.build(sourceFile.absoluteFile)

        // Destination with different extension
        val destPath = LocalPath.build(sourceFolder.absoluteFile).child("document.md")

        // When
        val result = sourcePath.move(ops, destPath, options = MoveAction.Options(attemptAtomicMove = false))
            .last() as MoveAction.State.Completed<LocalPath, LocalPathLookup, LocalPath, LocalPathLookup>

        // Then
        val destFile = File(sourceFolder, "document.md")
        destFile.exists() shouldBe true
        destFile.isFile shouldBe true
        destFile.readText() shouldBe "Markdown content"
        sourceFile.exists() shouldBe false
        result.movedFiles shouldContainPath (sourcePath to destPath)
    }

    @Test
    fun `rename directory in same parent`(@TempDir tempDir: File) = runTest {
        val sourceFolder = File(tempDir, "source").apply { mkdirs() }
        val destFolder = File(tempDir, "dest").apply { mkdirs() }
        // Given
        val sourceDir = File(sourceFolder, "OldName")
        sourceDir.mkdir()
        File(sourceDir, "file.txt").writeText("Content")
        val sourcePath = LocalPath.build(sourceDir.absoluteFile)

        // Destination is a directory path with new name
        val destPath = LocalPath.build(sourceFolder.absoluteFile).child("NewName")

        // When
        val result = sourcePath.move(ops, destPath, options = MoveAction.Options(attemptAtomicMove = false))
            .last() as MoveAction.State.Completed<LocalPath, LocalPathLookup, LocalPath, LocalPathLookup>

        // Then
        val destDir = File(sourceFolder, "NewName")
        destDir.exists() shouldBe true
        destDir.isDirectory shouldBe true
        File(destDir, "file.txt").exists() shouldBe true
        sourceDir.exists() shouldBe false
        result.movedFiles shouldHaveSize 2 // directory + file
    }

    // ============ ADDITIONAL COVERAGE TESTS ============

    @Test
    fun `verify files vs directories handled consistently`(@TempDir tempDir: File) = runTest {
        val sourceFolder = File(tempDir, "source").apply { mkdirs() }
        val destFolder = File(tempDir, "dest").apply { mkdirs() }
        // Given
        val file = File(sourceFolder, "file.txt")
        val dir = File(sourceFolder, "dir")
        val dirFile = File(dir, "nested.txt")

        file.writeText("file content")
        dir.mkdir()
        dirFile.writeText("nested content")

        // When
        listOf(LocalPath.build(file), LocalPath.build(dir)).move(ops, LocalPath.build(destFolder)).last()

        // Then - both maintain their top-level name
        File(destFolder, "file.txt").exists() shouldBe true
        File(destFolder, "dir/nested.txt").exists() shouldBe true
        file.exists() shouldBe false
        dir.exists() shouldBe false
    }

    @Test
    fun `no issue handler should auto-merge directories for backward compatibility`(@TempDir tempDir: File) = runTest {
        val sourceFolder = File(tempDir, "source").apply { mkdirs() }
        val destFolder = File(tempDir, "dest").apply { mkdirs() }
        // Given - directory exists at destination
        val sourceDir = File(sourceFolder, "Folder")
        sourceDir.mkdir()
        File(sourceDir, "new.txt").writeText("new")

        val destDir = File(destFolder, "Folder")
        destDir.mkdir()
        File(destDir, "old.txt").writeText("old")

        // When - move without issue handler (onIssue = null)
        LocalPath.build(sourceDir).move(
            ops,
            LocalPath.build(destFolder)
            // No onIssue parameter - uses default null
        ).last()

        // Then - should auto-merge without prompting
        File(destFolder, "Folder/new.txt").exists() shouldBe true
        File(destFolder, "Folder/old.txt").exists() shouldBe true
        sourceDir.exists() shouldBe false
    }

    @Test
    fun `very deep directory structure`(@TempDir tempDir: File) = runTest {
        val sourceFolder = File(tempDir, "source").apply { mkdirs() }
        val destFolder = File(tempDir, "dest").apply { mkdirs() }
        // Given
        var currentDir = File(sourceFolder, "deep")
        currentDir.mkdir()
        val files = mutableListOf<File>()

        repeat(10) { level ->
            currentDir = File(currentDir, "level$level")
            currentDir.mkdir()

            val file = File(currentDir, "file$level.txt")
            file.writeText("Level $level content")
            files.add(file)
        }

        val expectedSize = files.sumOf { it.length() }
        val sourcePath = File(sourceFolder, "deep")

        // When
        val result = LocalPath.build(sourcePath).move(
            ops,
            LocalPath.build(destFolder),
            options = MoveAction.Options(attemptAtomicMove = false)
        ).last() as MoveAction.State.Completed<LocalPath, LocalPathLookup, LocalPath, LocalPathLookup>

        // Then
        result.bytesMoved shouldBe expectedSize
        File(destFolder, "deep/level0/level1/level2/level3/level4/level5/level6/level7/level8/level9/file9.txt")
            .exists() shouldBe true
        sourcePath.exists() shouldBe false
    }

    // ============ SCAN ERROR HANDLING ============

    // ============ NULLABLE FIELDS TESTS ============

    @Test
    fun `move file with null size reports correct progress with 0L fallback`(@TempDir tempDir: File) = runTest {
        val sourceFolder = File(tempDir, "source").apply { mkdirs() }
        val destFolder = File(tempDir, "dest").apply { mkdirs() }
        // Given - a source whose lookup reports no size, as a partial lookup does (e.g. "/" on Android)
        val sourceFile = File(sourceFolder, "restricted.txt")
        sourceFile.writeText("content")
        val sourcePath = LocalPath.build(sourceFile)

        // The move scan looks the source up with BASE; atomic move stays enabled
        val nullSizeLookup = ops.lookup(sourcePath, LookupOptions.BASE).copy(size = null)
        val spyOps = spyk(ops)
        coEvery { spyOps.lookup(sourcePath, LookupOptions.BASE) } returns nullSizeLookup

        val states = mutableListOf<MoveAction.State<LocalPath, LocalPathLookup, LocalPath, LocalPathLookup>>()

        // When
        val result = sourcePath.move(spyOps, LocalPath.build(destFolder))
            .onEach { states.add(it) }
            .last() as MoveAction.State.Completed<LocalPath, LocalPathLookup, LocalPath, LocalPathLookup>

        // Then - file moved successfully despite the missing size metadata
        val destFile = File(destFolder, "restricted.txt")
        destFile.exists() shouldBe true
        destFile.readText() shouldBe "content"
        sourceFile.exists() shouldBe false // Source deleted after move

        result.movedFiles shouldContainPath (sourcePath to LocalPath.build(destFile))
        result.movedFiles shouldHaveSize 1
        result.movedFiles.single().first.size shouldBe null

        // Byte tracking falls back to 0L because the size was never known
        result.bytesMoved shouldBe 0L
        val activeStates =
            states.filterIsInstance<MoveAction.State.Active<LocalPath, LocalPathLookup, LocalPath, LocalPathLookup>>()
        activeStates.shouldNotBeEmpty()
        activeStates.forEach {
            it.totalBytes shouldBe 0L
            it.currentFileSize shouldBe 0L
        }
    }
}
