package eu.darken.butler.common.files.local

import eu.darken.butler.common.files.LocalPath
import eu.darken.butler.common.files.actions.MoveAction
import eu.darken.butler.common.files.actions.PathActionIssue
import eu.darken.butler.common.files.errors.PathPermissionDeniedException
import eu.darken.butler.common.files.errors.WriteException
import eu.darken.butler.common.files.metadata.OwnershipResolver
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.should
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.spyk
import kotlinx.coroutines.flow.last
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import testhelpers.BaseTest
import testhelpers.shouldContainPath
import testhelpers.toPathPairs
import java.io.File
import java.io.IOException
import java.nio.file.Files
import kotlin.coroutines.cancellation.CancellationException

class LocalPathMoveIssueTest : BaseTest() {

    private val mockOwnershipResolver = mockk<OwnershipResolver>(relaxed = true)
    private val ops = LocalFileSystemOps(
        ownershipResolver = mockOwnershipResolver,
    )

    @Test
    fun `move file - destination exists - skip`(@TempDir tempDir: File) = runTest {
        val sourceFolder = File(tempDir, "source").apply { mkdirs() }
        val destFolder = File(tempDir, "dest").apply { mkdirs() }
        // Given
        val sourceFile = File(sourceFolder, "test.txt")
        sourceFile.writeText("Source content")

        val existingFile = File(destFolder, "test.txt")
        existingFile.writeText("Existing content")

        val sourcePath = LocalPath.build(sourceFile)
        val destPath = LocalPath.build(destFolder)

        // When
        val result = sourcePath.move(ops, destPath, options = MoveAction.Options(attemptAtomicMove = false)) { issue ->
            when (issue) {
                is PathActionIssue.PathAlreadyExists -> PathActionIssue.PathAlreadyExists.Resolution.Skip()
                else -> error("Unexpected issue: $issue")
            }
        }.last() as MoveAction.State.Completed<LocalPath, LocalPathLookup, LocalPath, LocalPathLookup>

        // Then
        result.skippedFiles shouldContainPath sourcePath
        existingFile.readText() shouldBe "Existing content" // Destination unchanged
        sourceFile.exists() shouldBe true // Source still exists
    }

    @Test
    fun `move file - destination exists - overwrite`(@TempDir tempDir: File) = runTest {
        val sourceFolder = File(tempDir, "source").apply { mkdirs() }
        val destFolder = File(tempDir, "dest").apply { mkdirs() }
        // Given
        val sourceFile = File(sourceFolder, "test.txt")
        sourceFile.writeText("Source content")

        val existingFile = File(destFolder, "test.txt")
        existingFile.writeText("Existing content")

        val sourcePath = LocalPath.build(sourceFile)
        val destPath = LocalPath.build(destFolder)

        // When
        val result = sourcePath.move(ops, destPath, options = MoveAction.Options(attemptAtomicMove = false)) { issue ->
            when (issue) {
                is PathActionIssue.PathAlreadyExists -> PathActionIssue.PathAlreadyExists.Resolution.Overwrite()
                else -> error("Unexpected issue: $issue")
            }
        }.last() as MoveAction.State.Completed<LocalPath, LocalPathLookup, LocalPath, LocalPathLookup>

        // Then
        result.movedFiles shouldContainPath (sourcePath to LocalPath.build(existingFile))
        existingFile.readText() shouldBe "Source content" // Overwritten
        sourceFile.exists() shouldBe false // Source deleted
    }

    @Test
    fun `move file - destination exists - rename source`(@TempDir tempDir: File) = runTest {
        val sourceFolder = File(tempDir, "source").apply { mkdirs() }
        val destFolder = File(tempDir, "dest").apply { mkdirs() }
        // Given
        val sourceFile = File(sourceFolder, "test.txt")
        sourceFile.writeText("Source content")

        val existingFile = File(destFolder, "test.txt")
        existingFile.writeText("Existing content")

        val sourcePath = LocalPath.build(sourceFile)
        val destPath = LocalPath.build(destFolder)

        // When
        val result = sourcePath.move(ops, destPath, options = MoveAction.Options(attemptAtomicMove = false)) { issue ->
            when (issue) {
                is PathActionIssue.PathAlreadyExists -> {
                    PathActionIssue.PathAlreadyExists.Resolution.RenameSource("test-renamed.txt")
                }
                else -> error("Unexpected issue: $issue")
            }
        }.last() as MoveAction.State.Completed<LocalPath, LocalPathLookup, LocalPath, LocalPathLookup>

        // Then
        result.movedFiles shouldContainPath (sourcePath to LocalPath.build(File(destFolder, "test-renamed.txt")))
        File(destFolder, "test-renamed.txt").exists() shouldBe true
        File(destFolder, "test-renamed.txt").readText() shouldBe "Source content"
        existingFile.readText() shouldBe "Existing content" // Original unchanged
        sourceFile.exists() shouldBe false // Source deleted
    }

    @Test
    fun `move directory - destination exists and is directory - merge`(@TempDir tempDir: File) = runTest {
        val sourceFolder = File(tempDir, "source").apply { mkdirs() }
        val destFolder = File(tempDir, "dest").apply { mkdirs() }
        // Given
        val sourceDir = File(sourceFolder, "docs")
        sourceDir.mkdir()
        File(sourceDir, "file1.txt").writeText("File 1")

        val existingDir = File(destFolder, "docs")
        existingDir.mkdir()
        File(existingDir, "file2.txt").writeText("File 2")

        val sourcePath = LocalPath.build(sourceDir)
        val destPath = LocalPath.build(destFolder)

        // When
        sourcePath.move(ops, destPath, options = MoveAction.Options(attemptAtomicMove = false)) { issue ->
            when (issue) {
                is PathActionIssue.PathAlreadyExists -> PathActionIssue.PathAlreadyExists.Resolution.Merge()
                else -> error("Unexpected issue: $issue")
            }
        }.last()

        // Then
        File(destFolder, "docs/file1.txt").exists() shouldBe true
        File(destFolder, "docs/file1.txt").readText() shouldBe "File 1"
        File(destFolder, "docs/file2.txt").exists() shouldBe true
        File(destFolder, "docs/file2.txt").readText() shouldBe "File 2"
        sourceDir.exists() shouldBe false // Source deleted
    }

    @Test
    fun `move file - apply to all - skip`(@TempDir tempDir: File) = runTest {
        val sourceFolder = File(tempDir, "source").apply { mkdirs() }
        val destFolder = File(tempDir, "dest").apply { mkdirs() }
        // Given
        val file1 = File(sourceFolder, "file1.txt")
        val file2 = File(sourceFolder, "file2.txt")
        file1.writeText("Source 1")
        file2.writeText("Source 2")

        File(destFolder, "file1.txt").writeText("Existing 1")
        File(destFolder, "file2.txt").writeText("Existing 2")

        val sources = setOf(LocalPath.build(file1), LocalPath.build(file2))
        val destPath = LocalPath.build(destFolder)

        var issueCount = 0

        // When
        val result = sources.move(ops, destPath, options = MoveAction.Options(attemptAtomicMove = false)) { issue ->
            issueCount++
            when (issue) {
                is PathActionIssue.PathAlreadyExists -> PathActionIssue.PathAlreadyExists.Resolution.Skip(applyToAll = true)
                else -> error("Unexpected issue: $issue")
            }
        }.last() as MoveAction.State.Completed<LocalPath, LocalPathLookup, LocalPath, LocalPathLookup>

        // Then
        issueCount shouldBe 1 // Only asked once due to apply-to-all
        result.skippedFiles shouldHaveSize 2
        file1.exists() shouldBe true
        file2.exists() shouldBe true
    }

    @Test
    fun `move file - apply to all - overwrite`(@TempDir tempDir: File) = runTest {
        val sourceFolder = File(tempDir, "source").apply { mkdirs() }
        val destFolder = File(tempDir, "dest").apply { mkdirs() }
        // Given
        val file1 = File(sourceFolder, "file1.txt")
        val file2 = File(sourceFolder, "file2.txt")
        file1.writeText("Source 1")
        file2.writeText("Source 2")

        File(destFolder, "file1.txt").writeText("Existing 1")
        File(destFolder, "file2.txt").writeText("Existing 2")

        val sources = setOf(LocalPath.build(file1), LocalPath.build(file2))
        val destPath = LocalPath.build(destFolder)

        var issueCount = 0

        // When
        val result = sources.move(ops, destPath, options = MoveAction.Options(attemptAtomicMove = false)) { issue ->
            issueCount++
            when (issue) {
                is PathActionIssue.PathAlreadyExists -> PathActionIssue.PathAlreadyExists.Resolution.Overwrite(
                    applyToAll = true
                )
                else -> error("Unexpected issue: $issue")
            }
        }.last() as MoveAction.State.Completed<LocalPath, LocalPathLookup, LocalPath, LocalPathLookup>

        // Then
        issueCount shouldBe 1 // Only asked once due to apply-to-all
        result.movedFiles shouldHaveSize 2
        File(destFolder, "file1.txt").readText() shouldBe "Source 1"
        File(destFolder, "file2.txt").readText() shouldBe "Source 2"
        file1.exists() shouldBe false
        file2.exists() shouldBe false
    }

    @Test
    fun `move symlink conflict - source symlink destination regular file`(@TempDir tempDir: File) = runTest {
        val sourceFolder = File(tempDir, "source").apply { mkdirs() }
        val destFolder = File(tempDir, "dest").apply { mkdirs() }
        // Given - source is symlink, destination is regular file
        val targetFile = File(sourceFolder, "target.txt")
        targetFile.writeText("target")

        val sourceLink = File(sourceFolder, "item.txt")
        Files.createSymbolicLink(sourceLink.toPath(), java.nio.file.Paths.get("target.txt"))

        val destFile = File(destFolder, "item.txt")
        destFile.writeText("existing file")

        if (!Files.isSymbolicLink(sourceLink.toPath())) {
            return@runTest
        }

        // When - move with overwrite resolution
        val result = LocalPath.build(sourceLink).move(
            ops,
            LocalPath.build(destFolder),
            options = MoveAction.Options(attemptAtomicMove = false),
            onIssue = { issue ->
                when (issue) {
                    is PathActionIssue.PathAlreadyExists -> {
                        PathActionIssue.PathAlreadyExists.Resolution.Overwrite()
                    }
                    else -> throw AssertionError("Unexpected issue: $issue")
                }
            }
        ).last() as MoveAction.State.Completed<LocalPath, LocalPathLookup, LocalPath, LocalPathLookup>

        // Then - symlink should overwrite the regular file
        val movedItem = File(destFolder, "item.txt")
        Files.isSymbolicLink(movedItem.toPath()) shouldBe true
        result.movedFiles shouldHaveSize 1
        sourceLink.exists() shouldBe false
    }

    @Test
    fun `move directory overwrite`(@TempDir tempDir: File) = runTest {
        val sourceFolder = File(tempDir, "source").apply { mkdirs() }
        val destFolder = File(tempDir, "dest").apply { mkdirs() }
        // Given
        val sourceDir = File(sourceFolder, "dir")
        sourceDir.mkdir()
        File(sourceDir, "file.txt").writeText("Source content")

        val destDir = File(destFolder, "dir")
        destDir.mkdir()
        File(destDir, "old.txt").writeText("Old content")

        val sourcePath = LocalPath.build(sourceDir)
        val destPath = LocalPath.build(destFolder)

        var issueReceived: PathActionIssue? = null

        // When
        sourcePath.move(
            ops,
            destPath,
            options = MoveAction.Options(attemptAtomicMove = false),
            onIssue = { issue ->
                issueReceived = issue
                PathActionIssue.PathAlreadyExists.Resolution.Overwrite()
            }
        ).last()

        // Then
        issueReceived shouldNotBe null
        (issueReceived is PathActionIssue.PathAlreadyExists) shouldBe true
        File(destFolder, "dir/file.txt").exists() shouldBe true
        File(destFolder, "dir/old.txt").exists() shouldBe false // Old content removed
        sourceDir.exists() shouldBe false
    }

    @Test
    fun `move directory overwrite with apply to all`(@TempDir tempDir: File) = runTest {
        val sourceFolder = File(tempDir, "source").apply { mkdirs() }
        val destFolder = File(tempDir, "dest").apply { mkdirs() }
        // Given
        val dir1 = File(sourceFolder, "dir1")
        dir1.mkdir()
        File(dir1, "file1.txt").writeText("Content 1")

        val dir2 = File(sourceFolder, "dir2")
        dir2.mkdir()
        File(dir2, "file2.txt").writeText("Content 2")

        // Create conflicting destinations
        File(destFolder, "dir1").mkdir()
        File(destFolder, "dir2").mkdir()

        var issueCount = 0

        // When
        listOf(LocalPath.build(dir1), LocalPath.build(dir2)).move(
            ops,
            LocalPath.build(destFolder),
            options = MoveAction.Options(attemptAtomicMove = false),
            onIssue = { issue ->
                issueCount++
                PathActionIssue.PathAlreadyExists.Resolution.Overwrite(applyToAll = true)
            }
        ).last()

        // Then - only one issue for apply-to-all
        issueCount shouldBe 1
        File(destFolder, "dir1/file1.txt").exists() shouldBe true
        File(destFolder, "dir2/file2.txt").exists() shouldBe true
        dir1.exists() shouldBe false
        dir2.exists() shouldBe false
    }

    @Test
    fun `move directory skip with apply to all`(@TempDir tempDir: File) = runTest {
        val sourceFolder = File(tempDir, "source").apply { mkdirs() }
        val destFolder = File(tempDir, "dest").apply { mkdirs() }
        // Given
        val dir1 = File(sourceFolder, "dir1")
        dir1.mkdir()
        File(dir1, "file1.txt").writeText("Content 1")

        val dir2 = File(sourceFolder, "dir2")
        dir2.mkdir()
        File(dir2, "file2.txt").writeText("Content 2")

        // Create conflicting destinations
        val dest1 = File(destFolder, "dir1")
        dest1.mkdir()
        File(dest1, "existing1.txt").writeText("Existing 1")

        val dest2 = File(destFolder, "dir2")
        dest2.mkdir()
        File(dest2, "existing2.txt").writeText("Existing 2")

        var issueCount = 0

        // When
        val result = listOf(LocalPath.build(dir1), LocalPath.build(dir2)).move(
            ops,
            LocalPath.build(destFolder),
            options = MoveAction.Options(attemptAtomicMove = false),
            onIssue = { issue ->
                issueCount++
                PathActionIssue.PathAlreadyExists.Resolution.Skip(applyToAll = true)
            }
        ).last() as MoveAction.State.Completed<LocalPath, LocalPathLookup, LocalPath, LocalPathLookup>

        // Then
        issueCount shouldBe 1
        File(dest1, "file1.txt").exists() shouldBe false // Not moved
        File(dest1, "existing1.txt").exists() shouldBe true // Original preserved
        File(dest2, "file2.txt").exists() shouldBe false // Not moved
        File(dest2, "existing2.txt").exists() shouldBe true // Original preserved
        result.skippedFiles.size shouldBe 4 // 2 directories + 2 files (cascading skip)
        dir1.exists() shouldBe true // Source still exists
        dir2.exists() shouldBe true // Source still exists
    }

    @Test
    fun `move file to directory conflict - overwrite directory`(@TempDir tempDir: File) = runTest {
        val sourceFolder = File(tempDir, "source").apply { mkdirs() }
        val destFolder = File(tempDir, "dest").apply { mkdirs() }
        // Given - file in source, directory at destination
        val sourceFile = File(sourceFolder, "item")
        sourceFile.writeText("File content")

        val destDir = File(destFolder, "item")
        destDir.mkdir()
        File(destDir, "nested.txt").writeText("Nested")

        var issueReceived: PathActionIssue? = null

        // When
        LocalPath.build(sourceFile).move(
            ops,
            LocalPath.build(destFolder),
            options = MoveAction.Options(attemptAtomicMove = false),
            onIssue = { issue ->
                issueReceived = issue
                PathActionIssue.PathAlreadyExists.Resolution.Overwrite()
            }
        ).last()

        // Then
        issueReceived shouldNotBe null
        File(destFolder, "item").isFile shouldBe true // Now a file
        File(destFolder, "item").readText() shouldBe "File content"
        sourceFile.exists() shouldBe false
    }

    @Test
    fun `move directory to file conflict - overwrite file`(@TempDir tempDir: File) = runTest {
        val sourceFolder = File(tempDir, "source").apply { mkdirs() }
        val destFolder = File(tempDir, "dest").apply { mkdirs() }
        // Given - directory in source, file at destination
        val sourceDir = File(sourceFolder, "item")
        sourceDir.mkdir()
        File(sourceDir, "file.txt").writeText("Content")

        val destFile = File(destFolder, "item")
        destFile.writeText("Existing file")

        var issueReceived: PathActionIssue? = null

        // When
        LocalPath.build(sourceDir).move(
            ops,
            LocalPath.build(destFolder),
            options = MoveAction.Options(attemptAtomicMove = false),
            onIssue = { issue ->
                issueReceived = issue
                PathActionIssue.PathAlreadyExists.Resolution.Overwrite()
            }
        ).last()

        // Then
        issueReceived shouldNotBe null
        File(destFolder, "item").isDirectory shouldBe true // Now a directory
        File(destFolder, "item/file.txt").readText() shouldBe "Content"
        sourceDir.exists() shouldBe false
    }

    @Test
    fun `move with rename source`(@TempDir tempDir: File) = runTest {
        val sourceFolder = File(tempDir, "source").apply { mkdirs() }
        val destFolder = File(tempDir, "dest").apply { mkdirs() }
        // Given - file with conflict
        val sourceFile = File(sourceFolder, "file.txt")
        sourceFile.writeText("Source content")

        val destFile = File(destFolder, "file.txt")
        destFile.writeText("Dest content")

        // When
        LocalPath.build(sourceFile).move(
            ops,
            LocalPath.build(destFolder),
            options = MoveAction.Options(attemptAtomicMove = false),
            onIssue = { issue ->
                when (issue) {
                    is PathActionIssue.PathAlreadyExists -> {
                        PathActionIssue.PathAlreadyExists.Resolution.RenameSource("file-renamed.txt")
                    }
                    else -> throw AssertionError("Unexpected issue")
                }
            }
        ).last()

        // Then
        File(destFolder, "file.txt").readText() shouldBe "Dest content" // Original unchanged
        File(destFolder, "file-renamed.txt").readText() shouldBe "Source content" // Renamed
        sourceFile.exists() shouldBe false
    }

    @Test
    fun `move with rename destination`(@TempDir tempDir: File) = runTest {
        val sourceFolder = File(tempDir, "source").apply { mkdirs() }
        val destFolder = File(tempDir, "dest").apply { mkdirs() }
        // Given - file with conflict
        val sourceFile = File(sourceFolder, "file.txt")
        sourceFile.writeText("Source content")

        val destFile = File(destFolder, "file.txt")
        destFile.writeText("Dest content")

        // When
        LocalPath.build(sourceFile).move(
            ops,
            LocalPath.build(destFolder),
            options = MoveAction.Options(attemptAtomicMove = false),
            onIssue = { issue ->
                when (issue) {
                    is PathActionIssue.PathAlreadyExists -> {
                        PathActionIssue.PathAlreadyExists.Resolution.RenameDestination("file-old.txt")
                    }
                    else -> throw AssertionError("Unexpected issue")
                }
            }
        ).last()

        // Then
        File(destFolder, "file.txt").readText() shouldBe "Source content" // New file
        File(destFolder, "file-old.txt").readText() shouldBe "Dest content" // Renamed old
        sourceFile.exists() shouldBe false
    }

    @Test
    fun `move directory with rename source`(@TempDir tempDir: File) = runTest {
        val sourceFolder = File(tempDir, "source").apply { mkdirs() }
        val destFolder = File(tempDir, "dest").apply { mkdirs() }
        // Given
        val sourceDir = File(sourceFolder, "dir")
        sourceDir.mkdir()
        File(sourceDir, "file.txt").writeText("Source")

        val destDir = File(destFolder, "dir")
        destDir.mkdir()
        File(destDir, "existing.txt").writeText("Existing")

        // When
        LocalPath.build(sourceDir).move(
            ops,
            LocalPath.build(destFolder),
            options = MoveAction.Options(attemptAtomicMove = false),
            onIssue = { issue ->
                PathActionIssue.PathAlreadyExists.Resolution.RenameSource("dir-renamed")
            }
        ).last()

        // Then
        File(destFolder, "dir/existing.txt").exists() shouldBe true // Original preserved
        File(destFolder, "dir-renamed/file.txt").readText() shouldBe "Source"
        sourceDir.exists() shouldBe false
    }

    @Test
    fun `result contains correct skipped sources`(@TempDir tempDir: File) = runTest {
        val sourceFolder = File(tempDir, "source").apply { mkdirs() }
        val destFolder = File(tempDir, "dest").apply { mkdirs() }
        // Given
        val file1 = File(sourceFolder, "file1.txt")
        file1.writeText("New 1")
        val file2 = File(sourceFolder, "file2.txt")
        file2.writeText("New 2")

        File(destFolder, "file1.txt").writeText("Existing 1")
        File(destFolder, "file2.txt").writeText("Existing 2")

        // When
        val result = listOf(LocalPath.build(file1), LocalPath.build(file2)).move(
            ops,
            LocalPath.build(destFolder),
            options = MoveAction.Options(attemptAtomicMove = false),
            onIssue = { issue ->
                PathActionIssue.PathAlreadyExists.Resolution.Skip(applyToAll = true)
            }
        ).last() as MoveAction.State.Completed<LocalPath, LocalPathLookup, LocalPath, LocalPathLookup>

        // Then
        result.skippedFiles shouldHaveSize 2
        result.skippedFiles shouldContainPath LocalPath.build(file1)
        result.skippedFiles shouldContainPath LocalPath.build(file2)
    }

    @Test
    fun `directory rename destination should move existing directory and create new`(@TempDir tempDir: File) = runTest {
        val sourceFolder = File(tempDir, "source").apply { mkdirs() }
        val destFolder = File(tempDir, "dest").apply { mkdirs() }
        // Given - source directory and destination directory already exists
        val sourceDir = File(sourceFolder, "Dir")
        sourceDir.mkdir()
        File(sourceDir, "new.txt").writeText("new")

        val destDir = File(destFolder, "Dir")
        destDir.mkdir()
        File(destDir, "old.txt").writeText("old")

        // When - rename destination
        val result = LocalPath.build(sourceDir).move(
            ops,
            LocalPath.build(destFolder),
            options = MoveAction.Options(attemptAtomicMove = false),
            onIssue = { issue ->
                when (issue) {
                    is PathActionIssue.PathAlreadyExists -> {
                        PathActionIssue.PathAlreadyExists.Resolution.RenameDestination("Dir (1)")
                    }
                    else -> throw AssertionError("Unexpected issue: $issue")
                }
            }
        ).last() as MoveAction.State.Completed<LocalPath, LocalPathLookup, LocalPath, LocalPathLookup>

        // Then - old directory renamed, new directory created with original name
        File(destFolder, "Dir/new.txt").exists() shouldBe true
        File(destFolder, "Dir/old.txt").exists() shouldBe false
        File(destFolder, "Dir (1)/old.txt").exists() shouldBe true
        File(destFolder, "Dir (1)/new.txt").exists() shouldBe false
        result.movedFiles shouldHaveSize 2 // directory + file
        sourceDir.exists() shouldBe false
    }

    @Test
    fun `nested directory rename source should update all subdirectories and files`(@TempDir tempDir: File) = runTest {
        val sourceFolder = File(tempDir, "source").apply { mkdirs() }
        val destFolder = File(tempDir, "dest").apply { mkdirs() }
        // Given - nested source structure and conflicting destination
        val sourceDir = File(sourceFolder, "Parent")
        sourceDir.mkdir()
        val subDir1 = File(sourceDir, "SubDir1")
        subDir1.mkdir()
        val subDir2 = File(subDir1, "SubDir2")
        subDir2.mkdir()
        File(sourceDir, "file1.txt").writeText("content1")
        File(subDir1, "file2.txt").writeText("content2")
        File(subDir2, "file3.txt").writeText("content3")

        // Destination has conflicting Parent directory
        val destDir = File(destFolder, "Parent")
        destDir.mkdir()
        File(destDir, "existing.txt").writeText("existing")

        // When - rename source to Parent-new
        LocalPath.build(sourceDir).move(
            ops,
            LocalPath.build(destFolder),
            options = MoveAction.Options(attemptAtomicMove = false),
            onIssue = { issue ->
                when (issue) {
                    is PathActionIssue.PathAlreadyExists -> {
                        PathActionIssue.PathAlreadyExists.Resolution.RenameSource("Parent-new")
                    }
                    else -> throw AssertionError("Unexpected issue: $issue")
                }
            }
        ).last()

        // Then - all paths updated to use Parent-new
        File(destFolder, "Parent-new").exists() shouldBe true
        File(destFolder, "Parent-new/file1.txt").readText() shouldBe "content1"
        File(destFolder, "Parent-new/SubDir1/file2.txt").readText() shouldBe "content2"
        File(destFolder, "Parent-new/SubDir1/SubDir2/file3.txt").readText() shouldBe "content3"
        File(destFolder, "Parent/existing.txt").exists() shouldBe true // Original unchanged
        sourceDir.exists() shouldBe false
    }

    @Test
    fun `handle write-protected destination`(@TempDir tempDir: File) = runTest {
        val sourceFolder = File(tempDir, "source").apply { mkdirs() }
        val destFolder = File(tempDir, "dest").apply { mkdirs() }
        val sourceFile = File(sourceFolder, "file.txt")
        sourceFile.writeText("content")
        val sourcePath = LocalPath.build(sourceFile)
        val destFilePath = LocalPath.build(File(destFolder, "file.txt"))

        // setReadOnly() is not a reliable barrier (a privileged test process writes through it), so
        // inject exactly the failure a real EACCES produces inside LocalFileSystemOps.
        val spyOps = spyk(ops)
        coEvery { spyOps.move(sourcePath, destFilePath) } throws WriteException(
            path = destFilePath,
            cause = java.nio.file.AccessDeniedException(destFilePath.path),
        )

        val issues = mutableListOf<PathActionIssue>()
        val result = sourcePath.move(
            spyOps,
            LocalPath.build(destFolder),
            options = MoveAction.Options(attemptAtomicMove = false),
            onIssue = { issue ->
                issues.add(issue)
                when (issue) {
                    is PathActionIssue.InsufficientPermission -> PathActionIssue.InsufficientPermission.Resolution.Skip()
                    else -> throw AssertionError("Unexpected issue: $issue")
                }
            }
        ).last() as MoveAction.State.Completed<LocalPath, LocalPathLookup, LocalPath, LocalPathLookup>

        // A permission-classified write failure maps to InsufficientPermission, which offers no Retry
        issues shouldHaveSize 1
        val issue = issues.single().shouldBeInstanceOf<PathActionIssue.InsufficientPermission>()
        issue.source?.lookedUp shouldBe sourcePath
        issue.destinationPath shouldBe destFilePath

        result.skippedFiles shouldContainPath sourcePath
        result.movedFiles.shouldBeEmpty()
        File(destFolder, "file.txt").exists() shouldBe false
        sourceFile.readText() shouldBe "content"
    }

    @Test
    fun `insufficient permission with apply to all`(@TempDir tempDir: File) = runTest {
        val sourceFolder = File(tempDir, "source").apply { mkdirs() }
        val destFolder = File(tempDir, "dest").apply { mkdirs() }
        val files = (1..3).map { i -> File(sourceFolder, "file$i.txt").apply { writeText("content$i") } }
        val paths = files.map { LocalPath.build(it) }
        val destPaths = files.map { LocalPath.build(File(destFolder, it.name)) }

        // Every move fails with a permission-classified error
        val spyOps = spyk(ops)
        paths.forEachIndexed { index, path ->
            coEvery { spyOps.move(path, destPaths[index]) } throws PathPermissionDeniedException(
                path = destPaths[index],
                operation = "move",
                reason = PathPermissionDeniedException.Reason.ACCESS_DENIED,
            )
        }

        val issuesEncountered = mutableListOf<PathActionIssue>()
        val result = paths.move(
            spyOps,
            LocalPath.build(destFolder),
            options = MoveAction.Options(attemptAtomicMove = false),
            onIssue = { issue ->
                issuesEncountered.add(issue)
                when (issue) {
                    is PathActionIssue.InsufficientPermission ->
                        PathActionIssue.InsufficientPermission.Resolution.Skip(applyToAll = true)
                    else -> throw AssertionError("Unexpected issue: $issue")
                }
            }
        ).last() as MoveAction.State.Completed<LocalPath, LocalPathLookup, LocalPath, LocalPathLookup>

        // Only the first issue reaches the handler - the stored resolution covers the other two
        issuesEncountered shouldHaveSize 1
        issuesEncountered.single().shouldBeInstanceOf<PathActionIssue.InsufficientPermission>()

        result.skippedFiles.map { it.lookedUp } shouldContainExactlyInAnyOrder paths
        result.movedFiles.shouldBeEmpty()
        // Every source was still attempted exactly once; apply-to-all suppresses the prompt, not the work
        paths.forEachIndexed { index, path -> coVerify(exactly = 1) { spyOps.move(path, destPaths[index]) } }
        files.forEach { it.exists() shouldBe true }
        destFolder.list()!!.toList().shouldBeEmpty()
    }

    @Test
    fun `handle unknown errors with retry resolution`(@TempDir tempDir: File) = runTest {
        val sourceFolder = File(tempDir, "source").apply { mkdirs() }
        val destFolder = File(tempDir, "dest").apply { mkdirs() }
        val sourceFile = File(sourceFolder, "file.txt")
        sourceFile.writeText("content")
        val sourcePath = LocalPath.build(sourceFile)
        val destFilePath = LocalPath.build(File(destFolder, "file.txt"))

        // The move fails twice, then succeeds for real
        val spyOps = spyk(ops)
        var attempts = 0
        coEvery { spyOps.move(sourcePath, destFilePath) } coAnswers {
            attempts++
            if (attempts <= 2) throw IOException("injected move failure") else callOriginal()
        }

        val issues = mutableListOf<PathActionIssue>()
        val result = sourcePath.move(
            spyOps,
            LocalPath.build(destFolder),
            options = MoveAction.Options(attemptAtomicMove = false),
            onIssue = { issue ->
                issues.add(issue)
                when (issue) {
                    is PathActionIssue.UnknownError -> PathActionIssue.UnknownError.Resolution.Retry
                    else -> throw AssertionError("Unexpected issue: $issue")
                }
            }
        ).last() as MoveAction.State.Completed<LocalPath, LocalPathLookup, LocalPath, LocalPathLookup>

        // Two failures, two prompts, three attempts, then the real move lands
        attempts shouldBe 3
        issues shouldHaveSize 2
        issues.forEach {
            val unknown = it.shouldBeInstanceOf<PathActionIssue.UnknownError>()
            unknown.canRetry shouldBe true
        }
        result.movedFiles shouldContainPath (sourcePath to destFilePath)
        result.skippedFiles.shouldBeEmpty()
        File(destFolder, "file.txt").readText() shouldBe "content"
        sourceFile.exists() shouldBe false
    }

    @Test
    fun `handle unknown errors with cancel resolution`(@TempDir tempDir: File) = runTest {
        val sourceFolder = File(tempDir, "source").apply { mkdirs() }
        val destFolder = File(tempDir, "dest").apply { mkdirs() }
        val file1 = File(sourceFolder, "file1.txt")
        val file2 = File(sourceFolder, "file2.txt")
        file1.writeText("content1")
        file2.writeText("content2")

        val path1 = LocalPath.build(file1)
        val path2 = LocalPath.build(file2)
        val dest1 = LocalPath.build(File(destFolder, "file1.txt"))
        val dest2 = LocalPath.build(File(destFolder, "file2.txt"))

        // Move processes the batch in input order, so file1 fails before file2 is ever touched
        val spyOps = spyk(ops)
        coEvery { spyOps.move(path1, dest1) } throws IOException("injected move failure")

        val issues = mutableListOf<PathActionIssue>()
        val states = mutableListOf<MoveAction.State<LocalPath, LocalPathLookup, LocalPath, LocalPathLookup>>()

        // Cancel has no emitted state - PathOperationIssueResolver signals it by throwing
        shouldThrow<CancellationException> {
            listOf(path1, path2).move(
                spyOps,
                LocalPath.build(destFolder),
                options = MoveAction.Options(attemptAtomicMove = false),
                onIssue = { issue ->
                    issues.add(issue)
                    when (issue) {
                        is PathActionIssue.UnknownError -> PathActionIssue.UnknownError.Resolution.Cancel()
                        else -> throw AssertionError("Unexpected issue: $issue")
                    }
                }
            ).toList(states)
        }

        issues shouldHaveSize 1
        issues.single().shouldBeInstanceOf<PathActionIssue.UnknownError>()
        states.none { it is MoveAction.State.Completed } shouldBe true
        // The unprocessed item was never attempted
        coVerify(exactly = 0) { spyOps.move(path2, dest2) }
        file1.exists() shouldBe true
        file2.exists() shouldBe true
        destFolder.list()!!.toList().shouldBeEmpty()
    }

    @Test
    fun `issue should provide suggested name for conflicts`(@TempDir tempDir: File) = runTest {
        val sourceFolder = File(tempDir, "source").apply { mkdirs() }
        val destFolder = File(tempDir, "dest").apply { mkdirs() }
        // Given - file that will conflict
        val sourceFile = File(sourceFolder, "document.pdf")
        sourceFile.writeText("content")

        val destFile = File(destFolder, "document.pdf")
        destFile.writeText("existing")

        // When - move and capture issue
        var capturedIssue: PathActionIssue.PathAlreadyExists? = null
        LocalPath.build(sourceFile).move(
            ops,
            LocalPath.build(destFolder),
            options = MoveAction.Options(attemptAtomicMove = false),
            onIssue = { issue ->
                when (issue) {
                    is PathActionIssue.PathAlreadyExists -> {
                        capturedIssue = issue
                        PathActionIssue.PathAlreadyExists.Resolution.Skip()
                    }
                    else -> throw AssertionError("Unexpected issue: $issue")
                }
            }
        ).last()

        // Then - issue contains suggested name
        capturedIssue shouldNotBe null
        capturedIssue!!.suggestedName shouldBe "document (1).pdf"
        capturedIssue!!.canRenameSource shouldBe true
        capturedIssue!!.canRenameDestination shouldBe true
    }

    @Test
    fun `collection with duplicates should handle gracefully`(@TempDir tempDir: File) = runTest {
        val sourceFolder = File(tempDir, "source").apply { mkdirs() }
        val destFolder = File(tempDir, "dest").apply { mkdirs() }
        // Given - collection with duplicate paths
        val file = File(sourceFolder, "document.txt")
        file.writeText("content")

        val path = LocalPath.build(file)
        val duplicatePaths = listOf(path, path, path)

        // When - with skip resolution for conflicts
        val result = duplicatePaths.move(
            ops,
            LocalPath.build(destFolder),
            options = MoveAction.Options(attemptAtomicMove = false),
            onIssue = { issue ->
                when (issue) {
                    is PathActionIssue.PathAlreadyExists -> PathActionIssue.PathAlreadyExists.Resolution.Skip()
                    else -> throw AssertionError("Unexpected issue: $issue")
                }
            }
        ).last() as MoveAction.State.Completed<LocalPath, LocalPathLookup, LocalPath, LocalPathLookup>

        // Then - should move file once, skip duplicates
        result.movedFiles.size + result.skippedFiles.size should { it >= 1 }
        file.exists() shouldBe false
        File(destFolder, "document.txt").exists() shouldBe true
        File(destFolder, "document.txt").readText() shouldBe "content"
    }

    @Test
    fun `handle unknown errors with skip resolution`(@TempDir tempDir: File) = runTest {
        val sourceFolder = File(tempDir, "source").apply { mkdirs() }
        val destFolder = File(tempDir, "dest").apply { mkdirs() }
        // Given - three sources, only the middle one fails and it keeps failing
        val file1 = File(sourceFolder, "file1.txt").apply { writeText("content1") }
        val file2 = File(sourceFolder, "file2.txt").apply { writeText("content2") }
        val file3 = File(sourceFolder, "file3.txt").apply { writeText("content3") }
        val path1 = LocalPath.build(file1)
        val path2 = LocalPath.build(file2)
        val path3 = LocalPath.build(file3)
        val dest2 = LocalPath.build(File(destFolder, "file2.txt"))

        val spyOps = spyk(ops)
        coEvery { spyOps.move(path2, dest2) } throws IOException("injected move failure")

        val issues = mutableListOf<PathActionIssue>()

        // When - move with skip resolution for unknown errors
        val result = listOf(path1, path2, path3).move(
            spyOps,
            LocalPath.build(destFolder),
            options = MoveAction.Options(attemptAtomicMove = false),
            onIssue = { issue ->
                issues.add(issue)
                when (issue) {
                    is PathActionIssue.UnknownError -> PathActionIssue.UnknownError.Resolution.Skip()
                    else -> throw AssertionError("Unexpected issue: $issue")
                }
            }
        ).last() as MoveAction.State.Completed<LocalPath, LocalPathLookup, LocalPath, LocalPathLookup>

        // Then - Skip retires the failing item and lets the rest of the batch through
        issues shouldHaveSize 1
        issues.single().shouldBeInstanceOf<PathActionIssue.UnknownError>()

        result.skippedFiles.map { it.lookedUp } shouldBe listOf(path2)
        result.movedFiles.toPathPairs().map { it.first } shouldContainExactlyInAnyOrder listOf(path1, path3)
        file2.readText() shouldBe "content2"
        File(destFolder, "file1.txt").readText() shouldBe "content1"
        File(destFolder, "file2.txt").exists() shouldBe false
        File(destFolder, "file3.txt").readText() shouldBe "content3"
    }

    @Test
    fun `file rename destination should move existing file`(@TempDir tempDir: File) = runTest {
        val sourceFolder = File(tempDir, "source").apply { mkdirs() }
        val destFolder = File(tempDir, "dest").apply { mkdirs() }
        // Given - source file and destination file already exists
        val sourceFile = File(sourceFolder, "file.txt")
        sourceFile.writeText("new content")

        val destFile = File(destFolder, "file.txt")
        destFile.writeText("old content")

        // When - rename destination
        val result = LocalPath.build(sourceFile).move(
            ops,
            LocalPath.build(destFolder),
            options = MoveAction.Options(attemptAtomicMove = false),
            onIssue = { issue ->
                when (issue) {
                    is PathActionIssue.PathAlreadyExists -> {
                        PathActionIssue.PathAlreadyExists.Resolution.RenameDestination("file (1).txt")
                    }
                    else -> throw AssertionError("Unexpected issue: $issue")
                }
            }
        ).last() as MoveAction.State.Completed<LocalPath, LocalPathLookup, LocalPath, LocalPathLookup>

        // Then - old file renamed, new file moved to original name
        File(destFolder, "file.txt").readText() shouldBe "new content"
        File(destFolder, "file (1).txt").readText() shouldBe "old content"
        sourceFile.exists() shouldBe false
        result.movedFiles shouldContainPath (LocalPath.build(sourceFile) to LocalPath.build(
            File(
                destFolder,
                "file.txt"
            )
        ))
    }

    @Test
    fun `file-directory conflict rename destination should move file and create directory`(@TempDir tempDir: File) =
        runTest {
            val sourceFolder = File(tempDir, "source").apply { mkdirs() }
            val destFolder = File(tempDir, "dest").apply { mkdirs() }
            // Given - source directory but destination has a file with same name
            val sourceDir = File(sourceFolder, "Item")
            sourceDir.mkdir()
            File(sourceDir, "content.txt").writeText("content")

            val destFile = File(destFolder, "Item")
            destFile.writeText("blocking file")

            // When - rename destination
            val result = LocalPath.build(sourceDir).move(
                ops,
                LocalPath.build(destFolder),
                options = MoveAction.Options(attemptAtomicMove = false),
                onIssue = { issue ->
                    when (issue) {
                        is PathActionIssue.PathAlreadyExists -> {
                            PathActionIssue.PathAlreadyExists.Resolution.RenameDestination("Item (1)")
                        }
                        else -> throw AssertionError("Unexpected issue: $issue")
                    }
                }
            ).last() as MoveAction.State.Completed<LocalPath, LocalPathLookup, LocalPath, LocalPathLookup>

            // Then - file renamed, directory created with original name
            File(destFolder, "Item").isDirectory shouldBe true
            File(destFolder, "Item/content.txt").exists() shouldBe true
            File(destFolder, "Item (1)").isFile shouldBe true
            File(destFolder, "Item (1)").readText() shouldBe "blocking file"
            sourceDir.exists() shouldBe false
            result.movedFiles shouldHaveSize 2 // directory + file
        }

    @Test
    fun `file-directory conflict rename source should create directory with new name`(@TempDir tempDir: File) =
        runTest {
            val sourceFolder = File(tempDir, "source").apply { mkdirs() }
            val destFolder = File(tempDir, "dest").apply { mkdirs() }
            // Given - source directory but destination has a file with same name
            val sourceDir = File(sourceFolder, "Item")
            sourceDir.mkdir()
            File(sourceDir, "content.txt").writeText("content")

            val destFile = File(destFolder, "Item")
            destFile.writeText("blocking file")

            // When - rename source
            val result = LocalPath.build(sourceDir).move(
                ops,
                LocalPath.build(destFolder),
                options = MoveAction.Options(attemptAtomicMove = false),
                onIssue = { issue ->
                    when (issue) {
                        is PathActionIssue.PathAlreadyExists -> {
                            PathActionIssue.PathAlreadyExists.Resolution.RenameSource("Item (1)")
                        }
                        else -> throw AssertionError("Unexpected issue: $issue")
                    }
                }
            ).last() as MoveAction.State.Completed<LocalPath, LocalPathLookup, LocalPath, LocalPathLookup>

            // Then - file unchanged, directory created with new name
            File(destFolder, "Item").isFile shouldBe true
            File(destFolder, "Item").readText() shouldBe "blocking file"
            File(destFolder, "Item (1)").isDirectory shouldBe true
            File(destFolder, "Item (1)/content.txt").exists() shouldBe true
            sourceDir.exists() shouldBe false
            result.movedFiles shouldHaveSize 2 // directory + file
        }

    @Test
    fun `directory merge with apply to all should merge all directories`(@TempDir tempDir: File) = runTest {
        val sourceFolder = File(tempDir, "source").apply { mkdirs() }
        val destFolder = File(tempDir, "dest").apply { mkdirs() }
        // Given - multiple directories that exist at destination
        val source1 = File(sourceFolder, "Dir1")
        source1.mkdir()
        File(source1, "file1.txt").writeText("content1")

        val source2 = File(sourceFolder, "Dir2")
        source2.mkdir()
        File(source2, "file2.txt").writeText("content2")

        // Destination has these directories too
        val dest1 = File(destFolder, "Dir1")
        dest1.mkdir()
        File(dest1, "existing1.txt").writeText("old1")

        val dest2 = File(destFolder, "Dir2")
        dest2.mkdir()
        File(dest2, "existing2.txt").writeText("old2")

        // When - move both with merge apply-to-all
        val issuesEncountered = mutableListOf<PathActionIssue>()
        listOf(LocalPath.build(source1), LocalPath.build(source2)).move(
            ops,
            LocalPath.build(destFolder),
            options = MoveAction.Options(attemptAtomicMove = false),
            onIssue = { issue ->
                issuesEncountered.add(issue)
                when (issue) {
                    is PathActionIssue.PathAlreadyExists -> {
                        PathActionIssue.PathAlreadyExists.Resolution.Merge(applyToAll = true)
                    }
                    else -> throw AssertionError("Unexpected issue: $issue")
                }
            }
        ).last()

        // Then - only asked once due to "apply to all"
        issuesEncountered shouldHaveSize 1

        // All files merged
        File(destFolder, "Dir1/file1.txt").exists() shouldBe true
        File(destFolder, "Dir1/existing1.txt").exists() shouldBe true
        File(destFolder, "Dir2/file2.txt").exists() shouldBe true
        File(destFolder, "Dir2/existing2.txt").exists() shouldBe true
        source1.exists() shouldBe false
        source2.exists() shouldBe false
    }

    @Test
    fun `directory scan error then skip should not appear in moved`(@TempDir tempDir: File) = runTest {
        val sourceFolder = File(tempDir, "source").apply { mkdirs() }
        val destFolder = File(tempDir, "dest").apply { mkdirs() }
        // Given - Create directory structure
        val parentDir = File(sourceFolder, "parent")
        val childFile = File(parentDir, "child.txt")

        parentDir.mkdir()
        childFile.writeText("content")

        // Make directory unreadable to trigger permission error during scan
        parentDir.setReadable(false)

        try {
            // When
            val result = LocalPath.build(parentDir).move(
                ops,
                LocalPath.build(destFolder),
                options = MoveAction.Options(attemptAtomicMove = false),
                onIssue = { issue ->
                    when (issue) {
                        is PathActionIssue.InsufficientPermission -> PathActionIssue.InsufficientPermission.Resolution.Skip()
                        is PathActionIssue.UnknownError -> PathActionIssue.UnknownError.Resolution.Skip()
                        else -> TODO("Unexpected issue type: $issue")
                    }
                }
            )

            // Then - Directory should be ONLY in skipped, NOT in moved
            val finalResult =
                result.last() as MoveAction.State.Completed<LocalPath, LocalPathLookup, LocalPath, LocalPathLookup>
            finalResult.movedFiles.map { it.first.lookedUp } shouldNotBe setOf(LocalPath.build(parentDir))
            finalResult.skippedFiles.map { it.lookedUp } shouldContain LocalPath.build(parentDir)

            // Source should still exist (move was skipped)
            parentDir.exists() shouldBe true
            childFile.exists() shouldBe true

            // Destination should not have the directory or child
            File(destFolder, "parent").exists() shouldBe false
            File(destFolder, "parent/child.txt").exists() shouldBe false
        } finally {
            // Restore permissions for cleanup
            if (parentDir.exists()) {
                parentDir.setReadable(true)
            }
        }
    }
}
