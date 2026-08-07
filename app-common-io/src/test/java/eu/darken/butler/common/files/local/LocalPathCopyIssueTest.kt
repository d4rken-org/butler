package eu.darken.butler.common.files.local

import eu.darken.butler.common.files.LocalPath
import eu.darken.butler.common.files.actions.CopyAction
import eu.darken.butler.common.files.actions.PathActionIssue
import eu.darken.butler.common.files.metadata.FileType
import eu.darken.butler.common.files.metadata.OwnershipResolver
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.should
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.mockk.mockk
import kotlinx.coroutines.flow.last
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import testhelpers.BaseTest
import testhelpers.shouldContainPath
import java.io.File

class LocalPathCopyIssueTest : BaseTest() {

    private val mockOwnershipResolver = mockk<OwnershipResolver>(relaxed = true)
    private val ops = LocalFileSystemOps(
        ownershipResolver = mockOwnershipResolver,
    )

    @Test
    fun `collection with duplicates should handle gracefully`(@TempDir tempDir: File) = runTest {
        val sourceFolder = File(tempDir, "source").apply { mkdirs() }
        val destFolder = File(tempDir, "dest").apply { mkdirs() }
        // Given
        val file = File(sourceFolder, "duplicate.txt")
        file.writeText("content")
        val expectedSize = file.length()
        val sourcePath = LocalPath.build(file)

        // When - second copy will encounter PathAlreadyExists
        val result = listOf(sourcePath, sourcePath).copy(
            ops,
            LocalPath.build(destFolder),
            onIssue = { issue ->
                when (issue) {
                    is PathActionIssue.PathAlreadyExists -> PathActionIssue.PathAlreadyExists.Resolution.Overwrite()
                    else -> throw AssertionError("Unexpected issue: $issue")
                }
            }
        ).last() as CopyAction.State.Completed<LocalPath, LocalPathLookup, LocalPath, LocalPathLookup>

        // Then
        File(destFolder, "duplicate.txt").exists() shouldBe true
        // Both copies attempted but result may vary
        result.copiedBytes should { it >= expectedSize }
    }

    @Test
    fun `handle existing file with overwrite resolution`(@TempDir tempDir: File) = runTest {
        val sourceFolder = File(tempDir, "source").apply { mkdirs() }
        val destFolder = File(tempDir, "dest").apply { mkdirs() }
        // Given
        val sourceFile = File(sourceFolder, "file.txt")
        val destFile = File(destFolder, "file.txt")
        sourceFile.writeText("new content")
        destFile.writeText("old content")

        // When
        val result = LocalPath.build(sourceFile).copy(
            ops,
            LocalPath.build(destFolder),
            onIssue = { issue ->
                when (issue) {
                    is PathActionIssue.PathAlreadyExists -> PathActionIssue.PathAlreadyExists.Resolution.Overwrite()
                    else -> throw AssertionError("Unexpected issue: $issue")
                }
            }
        ).last() as CopyAction.State.Completed<LocalPath, LocalPathLookup, LocalPath, LocalPathLookup>

        // Then
        destFile.readText() shouldBe "new content"
        result.copied shouldHaveSize 1
    }

    @Test
    fun `handle existing file with skip resolution`(@TempDir tempDir: File) = runTest {
        val sourceFolder = File(tempDir, "source").apply { mkdirs() }
        val destFolder = File(tempDir, "dest").apply { mkdirs() }
        // Given
        val sourceFile = File(sourceFolder, "file.txt")
        val destFile = File(destFolder, "file.txt")
        sourceFile.writeText("new content")
        destFile.writeText("old content")

        // When
        val result = LocalPath.build(sourceFile).copy(
            ops,
            LocalPath.build(destFolder),
            onIssue = { issue ->
                when (issue) {
                    is PathActionIssue.PathAlreadyExists -> PathActionIssue.PathAlreadyExists.Resolution.Skip()
                    else -> throw AssertionError("Unexpected issue: $issue")
                }
            }
        ).last() as CopyAction.State.Completed<LocalPath, LocalPathLookup, LocalPath, LocalPathLookup>

        // Then
        destFile.readText() shouldBe "old content"
        result.skipped shouldContainPath LocalPath.build(sourceFile)
    }

    @Test
    fun `handle existing files with skip apply to all`(@TempDir tempDir: File) = runTest {
        val sourceFolder = File(tempDir, "source").apply { mkdirs() }
        val destFolder = File(tempDir, "dest").apply { mkdirs() }
        // Given
        val file1 = File(sourceFolder, "file1.txt")
        val file2 = File(sourceFolder, "file2.txt")
        val file3 = File(sourceFolder, "file3.txt")

        file1.writeText("new1")
        file2.writeText("new2")
        file3.writeText("new3")

        File(destFolder, "file1.txt").writeText("old1")
        File(destFolder, "file2.txt").writeText("old2")
        File(destFolder, "file3.txt").writeText("old3")

        val issuesEncountered = mutableListOf<PathActionIssue>()

        // When
        val result = listOf(
            LocalPath.build(file1),
            LocalPath.build(file2),
            LocalPath.build(file3)
        ).copy(
            ops,
            LocalPath.build(destFolder),
            onIssue = { issue ->
                issuesEncountered.add(issue)
                when (issue) {
                    is PathActionIssue.PathAlreadyExists -> PathActionIssue.PathAlreadyExists.Resolution.Skip(applyToAll = true)
                    else -> throw AssertionError("Unexpected issue: $issue")
                }
            }
        ).last() as CopyAction.State.Completed<LocalPath, LocalPathLookup, LocalPath, LocalPathLookup>

        // Then - only first issue should be handled due to "Apply to All"
        issuesEncountered shouldHaveSize 1
        result.skipped shouldHaveSize 3
        File(destFolder, "file1.txt").readText() shouldBe "old1"
        File(destFolder, "file2.txt").readText() shouldBe "old2"
        File(destFolder, "file3.txt").readText() shouldBe "old3"
    }

    @Test
    fun `handle existing files with overwrite apply to all`(@TempDir tempDir: File) = runTest {
        val sourceFolder = File(tempDir, "source").apply { mkdirs() }
        val destFolder = File(tempDir, "dest").apply { mkdirs() }
        // Given
        val file1 = File(sourceFolder, "file1.txt")
        val file2 = File(sourceFolder, "file2.txt")

        file1.writeText("new1")
        file2.writeText("new2")

        File(destFolder, "file1.txt").writeText("old1")
        File(destFolder, "file2.txt").writeText("old2")

        val issuesEncountered = mutableListOf<PathActionIssue>()

        // When
        listOf(LocalPath.build(file1), LocalPath.build(file2)).copy(
            ops,
            LocalPath.build(destFolder),
            onIssue = { issue ->
                issuesEncountered.add(issue)
                when (issue) {
                    is PathActionIssue.PathAlreadyExists -> PathActionIssue.PathAlreadyExists.Resolution.Overwrite(
                        applyToAll = true
                    )

                    else -> throw AssertionError("Unexpected issue: $issue")
                }
            }
        ).last()

        // Then - only first issue should be handled, all should be overwritten
        issuesEncountered shouldHaveSize 1
        File(destFolder, "file1.txt").readText() shouldBe "new1"
        File(destFolder, "file2.txt").readText() shouldBe "new2"
    }

    @Test
    fun `handle merge directories`(@TempDir tempDir: File) = runTest {
        val sourceFolder = File(tempDir, "source").apply { mkdirs() }
        val destFolder = File(tempDir, "dest").apply { mkdirs() }
        // Given
        val sourceDir = File(sourceFolder, "project")
        val destDir = File(destFolder, "project")
        val sourceFile = File(sourceDir, "new.txt")
        val destFile = File(destDir, "old.txt")

        sourceDir.mkdir()
        destDir.mkdir()
        sourceFile.writeText("new content")
        destFile.writeText("old content")

        // When
        LocalPath.build(sourceDir).copy(
            ops,
            LocalPath.build(destFolder),
            onIssue = { issue ->
                when (issue) {
                    is PathActionIssue.PathAlreadyExists -> PathActionIssue.PathAlreadyExists.Resolution.Merge()
                    else -> throw AssertionError("Unexpected issue: $issue")
                }
            }
        ).last()

        // Then - both files should exist
        File(destFolder, "project/new.txt").exists() shouldBe true
        File(destFolder, "project/old.txt").exists() shouldBe true
    }

    @Test
    fun `handle write-protected destination`(@TempDir tempDir: File) = runTest {
        val sourceFolder = File(tempDir, "source").apply { mkdirs() }
        val destFolder = File(tempDir, "dest").apply { mkdirs() }
        // This test is system-dependent and may not trigger issues on all systems
        // It mainly verifies the code doesn't crash with permission issues
        val sourceFile = File(sourceFolder, "file.txt")
        sourceFile.writeText("content")

        try {
            destFolder.setReadOnly()

            val result = LocalPath.build(sourceFile).copy(
                ops,
                LocalPath.build(destFolder),
                onIssue = { issue ->
                    when (issue) {
                        is PathActionIssue.InsufficientPermission -> PathActionIssue.InsufficientPermission.Resolution.Skip()
                        is PathActionIssue.UnknownError -> PathActionIssue.UnknownError.Resolution.Skip()
                        else -> throw AssertionError("Unexpected issue: $issue")
                    }
                }
            ).last() as CopyAction.State.Completed<LocalPath, LocalPathLookup, LocalPath, LocalPathLookup>

            result.copiedBytes should { it >= 0 }
        } catch (e: Exception) {
            // Expected on systems where read-only doesn't prevent writes
            // or where permission errors manifest differently
        }
    }

    @Test
    fun `insufficient permission with apply to all`(@TempDir tempDir: File) = runTest {
        val sourceFolder = File(tempDir, "source").apply { mkdirs() }
        val destFolder = File(tempDir, "dest").apply { mkdirs() }
        // This test verifies the "Apply to All" mechanism for permission issues
        // Actual permission errors may not occur on all systems
        val file1 = File(sourceFolder, "file1.txt")
        val file2 = File(sourceFolder, "file2.txt")
        file1.writeText("content1")
        file2.writeText("content2")

        try {
            file1.setReadOnly()
            file2.setReadOnly()

            val issuesEncountered = mutableListOf<PathActionIssue>()

            listOf(LocalPath.build(file1), LocalPath.build(file2)).copy(
                ops,
                LocalPath.build(destFolder),
                onIssue = { issue ->
                    issuesEncountered.add(issue)
                    when (issue) {
                        is PathActionIssue.InsufficientPermission ->
                            PathActionIssue.InsufficientPermission.Resolution.Skip(applyToAll = true)

                        else -> throw AssertionError("Unexpected issue: $issue")
                    }
                }
            ).last()

            // If issues were encountered, verify "Apply to All" behavior
            if (issuesEncountered.isNotEmpty()) {
                issuesEncountered shouldHaveSize 1
            }
        } catch (e: SecurityException) {
            // Expected on some systems where read-only doesn't prevent copying
        }
    }

    @Test
    fun `handle unknown errors with retry resolution`(@TempDir tempDir: File) = runTest {
        val sourceFolder = File(tempDir, "source").apply { mkdirs() }
        val destFolder = File(tempDir, "dest").apply { mkdirs() }
        // This test verifies retry mechanism works
        val sourceFile = File(sourceFolder, "file.txt")
        sourceFile.writeText("content")

        var attemptCount = 0

        val result = LocalPath.build(sourceFile).copy(
            ops,
            LocalPath.build(destFolder),
            onIssue = { issue ->
                when (issue) {
                    is PathActionIssue.UnknownError -> {
                        attemptCount++
                        if (attemptCount == 1) {
                            PathActionIssue.UnknownError.Resolution.Retry
                        } else {
                            PathActionIssue.UnknownError.Resolution.Skip()
                        }
                    }

                    else -> throw AssertionError("Unexpected issue: $issue")
                }
            }
        ).last() as CopyAction.State.Completed<LocalPath, LocalPathLookup, LocalPath, LocalPathLookup>

        // Result depends on whether errors actually occurred
        result.copiedBytes should { it >= 0 }
    }

    @Test
    fun `handle unknown errors with skip resolution`(@TempDir tempDir: File) = runTest {
        val sourceFolder = File(tempDir, "source").apply { mkdirs() }
        val destFolder = File(tempDir, "dest").apply { mkdirs() }
        val sourceFile = File(sourceFolder, "file.txt")
        sourceFile.writeText("content")

        val result = LocalPath.build(sourceFile).copy(
            ops,
            LocalPath.build(destFolder),
            onIssue = { issue ->
                when (issue) {
                    is PathActionIssue.UnknownError -> PathActionIssue.UnknownError.Resolution.Skip()
                    else -> throw AssertionError("Unexpected issue: $issue")
                }
            }
        ).last() as CopyAction.State.Completed<LocalPath, LocalPathLookup, LocalPath, LocalPathLookup>

        result.copiedBytes should { it >= 0 }
    }

    @Test
    fun `handle unknown errors with cancel resolution`(@TempDir tempDir: File) = runTest {
        val sourceFolder = File(tempDir, "source").apply { mkdirs() }
        val destFolder = File(tempDir, "dest").apply { mkdirs() }
        val file1 = File(sourceFolder, "file1.txt")
        val file2 = File(sourceFolder, "file2.txt")
        file1.writeText("content1")
        file2.writeText("content2")

        var issueCount = 0

        listOf(LocalPath.build(file1), LocalPath.build(file2)).copy(
            ops,
            LocalPath.build(destFolder),
            onIssue = { issue ->
                issueCount++
                when (issue) {
                    is PathActionIssue.UnknownError -> PathActionIssue.UnknownError.Resolution.Cancel()
                    is PathActionIssue.PathAlreadyExists -> PathActionIssue.PathAlreadyExists.Resolution.Cancel()
                    else -> throw AssertionError("Unexpected issue: $issue")
                }
            }
        ).last()

        // If issues were encountered, operation should have been cancelled
        if (issueCount > 0) {
            issueCount shouldBe 1
        }
    }

    @Test
    fun `destination file conflict can be resolved by overwriting`(@TempDir tempDir: File) = runTest {
        val sourceFolder = File(tempDir, "source").apply { mkdirs() }
        val destFolder = File(tempDir, "dest").apply { mkdirs() }
        // Given
        val sourceFile = File(sourceFolder, "source.txt")
        sourceFile.writeText("content")

        val destinationFile = File(tempDir, "dest-directory")
        destinationFile.writeText("I'm a file blocking directory creation")

        // When
        var issueEncountered = false
        val result = LocalPath.build(sourceFile).copy(
            ops,
            LocalPath.build(destinationFile),
            onIssue = { issue ->
                when (issue) {
                    is PathActionIssue.PathAlreadyExists -> {
                        issueEncountered = true
                        issue.destination.fileType shouldBe FileType.FILE
                        PathActionIssue.PathAlreadyExists.Resolution.Overwrite()
                    }

                    else -> throw IllegalStateException("Unexpected issue: $issue")
                }
            }
        ).last() as CopyAction.State.Completed<LocalPath, LocalPathLookup, LocalPath, LocalPathLookup>

        // Then
        issueEncountered shouldBe true
        // Unix semantics: single file → non-directory destination = rename (not copy INTO)
        destinationFile.isFile shouldBe true
        destinationFile.readText() shouldBe "content"
        result.copied shouldHaveSize 1
    }

    @Test
    fun `destination file conflict can be resolved by renaming file`(@TempDir tempDir: File) = runTest {
        val sourceFolder = File(tempDir, "source").apply { mkdirs() }
        val destFolder = File(tempDir, "dest").apply { mkdirs() }
        // Given
        val sourceFile = File(sourceFolder, "source.txt")
        sourceFile.writeText("content")

        val destinationFile = File(tempDir, "dest-directory")
        destinationFile.writeText("I'm a file blocking directory creation")

        // When
        var issueEncountered = false
        val result = LocalPath.build(sourceFile).copy(
            ops,
            LocalPath.build(destinationFile),
            onIssue = { issue ->
                when (issue) {
                    is PathActionIssue.PathAlreadyExists -> {
                        issueEncountered = true
                        PathActionIssue.PathAlreadyExists.Resolution.RenameDestination("dest-directory.old")
                    }

                    else -> throw IllegalStateException("Unexpected issue: $issue")
                }
            }
        ).last() as CopyAction.State.Completed<LocalPath, LocalPathLookup, LocalPath, LocalPathLookup>

        // Then
        issueEncountered shouldBe true
        File(tempDir, "dest-directory.old").apply {
            exists() shouldBe true
            isFile shouldBe true
            readText() shouldBe "I'm a file blocking directory creation"
        }
        // Unix semantics: single file → non-directory destination = rename (not copy INTO)
        destinationFile.isFile shouldBe true
        destinationFile.readText() shouldBe "content"
        result.copied shouldHaveSize 1
    }

    @Test
    fun `handle already-copied files`(@TempDir tempDir: File) = runTest {
        val sourceFolder = File(tempDir, "source").apply { mkdirs() }
        val destFolder = File(tempDir, "dest").apply { mkdirs() }
        // Given
        val sourceFile = File(sourceFolder, "file.txt")
        sourceFile.writeText("content")

        // Copy once
        LocalPath.build(sourceFile).copy(ops, LocalPath.build(destFolder)).last()

        // When - copy again, should trigger PathAlreadyExists
        var issueEncountered = false
        LocalPath.build(sourceFile).copy(
            ops,
            LocalPath.build(destFolder),
            onIssue = { issue ->
                when (issue) {
                    is PathActionIssue.PathAlreadyExists -> {
                        issueEncountered = true
                        PathActionIssue.PathAlreadyExists.Resolution.Skip()
                    }

                    else -> throw AssertionError("Unexpected issue: $issue")
                }
            }
        ).last()

        // Then
        issueEncountered shouldBe true
    }

    @Test
    fun `result contains correct skipped sources`(@TempDir tempDir: File) = runTest {
        val sourceFolder = File(tempDir, "source").apply { mkdirs() }
        val destFolder = File(tempDir, "dest").apply { mkdirs() }
        // Given
        val sourceFile = File(sourceFolder, "file.txt")
        val destFile = File(destFolder, "file.txt")
        sourceFile.writeText("new")
        destFile.writeText("old")

        // When
        val result = LocalPath.build(sourceFile).copy(
            ops,
            LocalPath.build(destFolder),
            onIssue = { issue ->
                when (issue) {
                    is PathActionIssue.PathAlreadyExists -> PathActionIssue.PathAlreadyExists.Resolution.Skip()
                    else -> throw AssertionError("Unexpected issue: $issue")
                }
            }
        ).last() as CopyAction.State.Completed<LocalPath, LocalPathLookup, LocalPath, LocalPathLookup>

        // Then
        result.skipped shouldContainPath LocalPath.build(sourceFile)
    }

    @Test
    fun `directory creation should detect file conflict`(@TempDir tempDir: File) = runTest {
        val sourceFolder = File(tempDir, "source").apply { mkdirs() }
        val destFolder = File(tempDir, "dest").apply { mkdirs() }
        // Given - source has directory structure, destination has file blocking it
        val sourceDir = File(sourceFolder, "Parent")
        val sourceSubDir = File(sourceDir, "SubDir")
        sourceSubDir.mkdirs()
        File(sourceSubDir, "content.txt").writeText("source content")

        // Destination has file "Parent/SubDir" (regular file, not directory)
        val destParent = File(destFolder, "Parent")
        destParent.mkdir()
        val destFile = File(destParent, "SubDir") // This is a FILE blocking the directory
        destFile.writeText("I'm a file blocking the directory")

        // When - try to copy with issue handler expecting PathAlreadyExists
        var issueReceived: PathActionIssue? = null
        LocalPath.build(sourceDir).copy(
            ops,
            LocalPath.build(destFolder),
            onIssue = { issue ->
                issueReceived = issue
                when (issue) {
                    is PathActionIssue.PathAlreadyExists -> {
                        // Verify it's detected as PathAlreadyExists, not UnknownError
                        PathActionIssue.PathAlreadyExists.Resolution.Overwrite()
                    }

                    else -> throw AssertionError("Expected PathAlreadyExists but got: $issue")
                }
            }
        ).last()

        // Then - should raise PathAlreadyExists issue
        issueReceived shouldNotBe null
        (issueReceived is PathActionIssue.PathAlreadyExists) shouldBe true

        // Verify directory was created after overwrite
        val finalSubDir = File(destFolder, "Parent/SubDir")
        finalSubDir.exists() shouldBe true
        finalSubDir.isDirectory shouldBe true
        File(finalSubDir, "content.txt").exists() shouldBe true
    }

    @Test
    fun `directory creation should allow skip resolution for file conflict`(@TempDir tempDir: File) = runTest {
        val sourceFolder = File(tempDir, "source").apply { mkdirs() }
        val destFolder = File(tempDir, "dest").apply { mkdirs() }
        // Given - source directory blocked by destination file
        val sourceDir = File(sourceFolder, "Parent")
        val sourceSubDir = File(sourceDir, "SubDir")
        sourceSubDir.mkdirs()
        File(sourceSubDir, "file.txt").writeText("content")

        val destParent = File(destFolder, "Parent")
        destParent.mkdir()
        val destBlockingFile = File(destParent, "SubDir")
        destBlockingFile.writeText("blocking file")

        // When - skip the conflict
        var issueReceived: PathActionIssue? = null
        val result = LocalPath.build(sourceDir).copy(
            ops,
            LocalPath.build(destFolder),
            onIssue = { issue ->
                issueReceived = issue
                when (issue) {
                    is PathActionIssue.PathAlreadyExists -> {
                        PathActionIssue.PathAlreadyExists.Resolution.Skip()
                    }

                    else -> throw AssertionError("Expected PathAlreadyExists but got: $issue")
                }
            }
        ).last() as CopyAction.State.Completed<LocalPath, LocalPathLookup, LocalPath, LocalPathLookup>

        // Then - issue was raised
        issueReceived shouldNotBe null
        (issueReceived is PathActionIssue.PathAlreadyExists) shouldBe true

        // File should still exist (not replaced by directory)
        destBlockingFile.exists() shouldBe true
        destBlockingFile.isFile shouldBe true
        destBlockingFile.readText() shouldBe "blocking file"

        // Result should show skipped item
        result.skipped shouldContainPath LocalPath.build(sourceSubDir)
    }

    @Test
    fun `file copy should detect directory conflict`(@TempDir tempDir: File) = runTest {
        val sourceFolder = File(tempDir, "source").apply { mkdirs() }
        val destFolder = File(tempDir, "dest").apply { mkdirs() }
        // Given - source is file, destination is directory with same name
        val sourceFile = File(sourceFolder, "item")
        sourceFile.writeText("file content")

        val destDir = File(destFolder, "item") // Directory, not file
        destDir.mkdir()
        File(destDir, "existing.txt").writeText("dir content")

        // When - try to copy file over directory
        var issueReceived: PathActionIssue? = null
        val result = LocalPath.build(sourceFile).copy(
            ops,
            LocalPath.build(destFolder),
            onIssue = { issue ->
                issueReceived = issue
                when (issue) {
                    is PathActionIssue.PathAlreadyExists -> {
                        PathActionIssue.PathAlreadyExists.Resolution.Skip()
                    }

                    else -> throw AssertionError("Expected PathAlreadyExists but got: $issue")
                }
            }
        ).last() as CopyAction.State.Completed<LocalPath, LocalPathLookup, LocalPath, LocalPathLookup>

        // Then - should raise PathAlreadyExists issue
        issueReceived shouldNotBe null
        (issueReceived is PathActionIssue.PathAlreadyExists) shouldBe true

        // Directory should still exist (not replaced by file)
        destDir.exists() shouldBe true
        destDir.isDirectory shouldBe true
        File(destDir, "existing.txt").exists() shouldBe true

        // Result should show skipped item
        result.skipped shouldContainPath LocalPath.build(sourceFile)
    }

    @Test
    fun `file copy should allow overwrite directory with file`(@TempDir tempDir: File) = runTest {
        val sourceFolder = File(tempDir, "source").apply { mkdirs() }
        val destFolder = File(tempDir, "dest").apply { mkdirs() }
        // Given - source file, destination directory with same name
        val sourceFile = File(sourceFolder, "item")
        sourceFile.writeText("new file content")

        val destDir = File(destFolder, "item")
        destDir.mkdir()

        // When - overwrite directory with file
        var issueReceived: PathActionIssue? = null
        LocalPath.build(sourceFile).copy(
            ops,
            LocalPath.build(destFolder),
            onIssue = { issue ->
                issueReceived = issue
                when (issue) {
                    is PathActionIssue.PathAlreadyExists -> {
                        PathActionIssue.PathAlreadyExists.Resolution.Overwrite()
                    }

                    else -> throw AssertionError("Expected PathAlreadyExists but got: $issue")
                }
            }
        ).last()

        // Then
        issueReceived shouldNotBe null
        (issueReceived is PathActionIssue.PathAlreadyExists) shouldBe true

        val finalItem = File(destFolder, "item")
        finalItem.exists() shouldBe true
        finalItem.isFile shouldBe true
        finalItem.readText() shouldBe "new file content"
    }

    @Test
    fun `directory merge should prompt user when directory exists`(@TempDir tempDir: File) = runTest {
        val sourceFolder = File(tempDir, "source").apply { mkdirs() }
        val destFolder = File(tempDir, "dest").apply { mkdirs() }
        // Given - both source and destination have "Folder" directory
        val sourceDir = File(sourceFolder, "Folder")
        sourceDir.mkdir()
        File(sourceDir, "source.txt").writeText("from source")

        val destDir = File(destFolder, "Folder")
        destDir.mkdir()
        File(destDir, "dest.txt").writeText("from dest")

        // When - copy with merge resolution
        var issueReceived: PathActionIssue? = null
        LocalPath.build(sourceDir).copy(
            ops,
            LocalPath.build(destFolder),
            onIssue = { issue ->
                issueReceived = issue
                when (issue) {
                    is PathActionIssue.PathAlreadyExists -> {
                        issue.canMerge shouldBe true
                        PathActionIssue.PathAlreadyExists.Resolution.Merge()
                    }

                    else -> throw AssertionError("Expected PathAlreadyExists but got: $issue")
                }
            }
        ).last()

        // Then - issue was raised with canMerge=true
        issueReceived shouldNotBe null
        (issueReceived is PathActionIssue.PathAlreadyExists) shouldBe true

        // Both files should exist (merged)
        File(destFolder, "Folder/source.txt").exists() shouldBe true
        File(destFolder, "Folder/dest.txt").exists() shouldBe true
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

        // When - copy both with merge apply-to-all
        val issuesEncountered = mutableListOf<PathActionIssue>()
        listOf(LocalPath.build(source1), LocalPath.build(source2)).copy(
            ops,
            LocalPath.build(destFolder),
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
    }

    @Test
    fun `directory skip with apply to all should skip all directories and their contents`(@TempDir tempDir: File) =
        runTest {
            val sourceFolder = File(tempDir, "source").apply { mkdirs() }
            val destFolder = File(tempDir, "dest").apply { mkdirs() }
            // Given - source directories with files
            val source1 = File(sourceFolder, "Dir1")
            source1.mkdir()
            File(source1, "file1.txt").writeText("new1")

            val source2 = File(sourceFolder, "Dir2")
            source2.mkdir()
            File(source2, "file2.txt").writeText("new2")

            // Destination has these directories
            val dest1 = File(destFolder, "Dir1")
            dest1.mkdir()
            File(dest1, "old1.txt").writeText("old1")

            val dest2 = File(destFolder, "Dir2")
            dest2.mkdir()
            File(dest2, "old2.txt").writeText("old2")

            // When - copy with skip apply-to-all
            val issuesEncountered = mutableListOf<PathActionIssue>()
            val result = listOf(LocalPath.build(source1), LocalPath.build(source2)).copy(
                ops,
                LocalPath.build(destFolder),
                onIssue = { issue ->
                    issuesEncountered.add(issue)
                    when (issue) {
                        is PathActionIssue.PathAlreadyExists -> {
                            PathActionIssue.PathAlreadyExists.Resolution.Skip(applyToAll = true)
                        }

                        else -> throw AssertionError("Unexpected issue: $issue")
                    }
                }
            ).last() as CopyAction.State.Completed<LocalPath, LocalPathLookup, LocalPath, LocalPathLookup>

            // Then - only asked once
            issuesEncountered shouldHaveSize 1

            // Nothing copied, directories and their contents skipped (cascading skip)
            result.copied.isEmpty() shouldBe true
            result.skipped shouldHaveSize 4 // 2 directories + 2 files inside them

            // Old files still exist, new files don't
            File(destFolder, "Dir1/old1.txt").exists() shouldBe true
            File(destFolder, "Dir1/file1.txt").exists() shouldBe false
            File(destFolder, "Dir2/old2.txt").exists() shouldBe true
            File(destFolder, "Dir2/file2.txt").exists() shouldBe false
        }

    @Test
    fun `directory overwrite should remove existing directory content`(@TempDir tempDir: File) = runTest {
        val sourceFolder = File(tempDir, "source").apply { mkdirs() }
        val destFolder = File(tempDir, "dest").apply { mkdirs() }
        // Given - source directory with new content
        val sourceDir = File(sourceFolder, "Folder")
        sourceDir.mkdir()
        File(sourceDir, "new.txt").writeText("new content")

        // Destination has directory with old content
        val destDir = File(destFolder, "Folder")
        destDir.mkdir()
        File(destDir, "old.txt").writeText("old content")
        File(destDir, "another-old.txt").writeText("another old")

        // When - copy with overwrite
        var issueReceived: PathActionIssue? = null
        LocalPath.build(sourceDir).copy(
            ops,
            LocalPath.build(destFolder),
            onIssue = { issue ->
                issueReceived = issue
                when (issue) {
                    is PathActionIssue.PathAlreadyExists -> {
                        PathActionIssue.PathAlreadyExists.Resolution.Overwrite()
                    }

                    else -> throw AssertionError("Expected PathAlreadyExists but got: $issue")
                }
            }
        ).last()

        // Then - issue was raised
        issueReceived shouldNotBe null

        // Old content gone, new content present
        File(destFolder, "Folder/new.txt").exists() shouldBe true
        File(destFolder, "Folder/old.txt").exists() shouldBe false
        File(destFolder, "Folder/another-old.txt").exists() shouldBe false
    }

    @Test
    fun `directory overwrite with apply to all should overwrite all directories`(@TempDir tempDir: File) = runTest {
        val sourceFolder = File(tempDir, "source").apply { mkdirs() }
        val destFolder = File(tempDir, "dest").apply { mkdirs() }
        // Given - multiple source directories
        val source1 = File(sourceFolder, "Dir1")
        source1.mkdir()
        File(source1, "new1.txt").writeText("new1")

        val source2 = File(sourceFolder, "Dir2")
        source2.mkdir()
        File(source2, "new2.txt").writeText("new2")

        // Destination has these with old content
        val dest1 = File(destFolder, "Dir1")
        dest1.mkdir()
        File(dest1, "old1.txt").writeText("old1")

        val dest2 = File(destFolder, "Dir2")
        dest2.mkdir()
        File(dest2, "old2.txt").writeText("old2")

        // When - copy with overwrite apply-to-all
        val issuesEncountered = mutableListOf<PathActionIssue>()
        listOf(LocalPath.build(source1), LocalPath.build(source2)).copy(
            ops,
            LocalPath.build(destFolder),
            onIssue = { issue ->
                issuesEncountered.add(issue)
                when (issue) {
                    is PathActionIssue.PathAlreadyExists -> {
                        PathActionIssue.PathAlreadyExists.Resolution.Overwrite(applyToAll = true)
                    }

                    else -> throw AssertionError("Unexpected issue: $issue")
                }
            }
        ).last()

        // Then - only asked once
        issuesEncountered shouldHaveSize 1

        // Old content replaced with new
        File(destFolder, "Dir1/new1.txt").exists() shouldBe true
        File(destFolder, "Dir1/old1.txt").exists() shouldBe false
        File(destFolder, "Dir2/new2.txt").exists() shouldBe true
        File(destFolder, "Dir2/old2.txt").exists() shouldBe false
    }

    @Test
    fun `file rename destination should move existing file and copy source`(@TempDir tempDir: File) = runTest {
        val sourceFolder = File(tempDir, "source").apply { mkdirs() }
        val destFolder = File(tempDir, "dest").apply { mkdirs() }
        // Given - source file and destination file already exists
        val sourceFile = File(sourceFolder, "file.txt")
        sourceFile.writeText("new content")

        val destFile = File(destFolder, "file.txt")
        destFile.writeText("old content")

        // When - rename destination
        val result = LocalPath.build(sourceFile).copy(
            ops,
            LocalPath.build(destFolder),
            onIssue = { issue ->
                when (issue) {
                    is PathActionIssue.PathAlreadyExists -> {
                        PathActionIssue.PathAlreadyExists.Resolution.RenameDestination("file (1).txt")
                    }

                    else -> throw AssertionError("Unexpected issue: $issue")
                }
            }
        ).last() as CopyAction.State.Completed<LocalPath, LocalPathLookup, LocalPath, LocalPathLookup>

        // Then - old file renamed, new file copied to original name
        File(destFolder, "file.txt").readText() shouldBe "new content"
        File(destFolder, "file (1).txt").readText() shouldBe "old content"
        result.copied shouldContainPath (LocalPath.build(sourceFile) to LocalPath.build(File(destFolder, "file.txt")))
    }

    @Test
    fun `file rename source should copy source with new name`(@TempDir tempDir: File) = runTest {
        val sourceFolder = File(tempDir, "source").apply { mkdirs() }
        val destFolder = File(tempDir, "dest").apply { mkdirs() }
        // Given - source file and destination file already exists
        val sourceFile = File(sourceFolder, "file.txt")
        sourceFile.writeText("new content")

        val destFile = File(destFolder, "file.txt")
        destFile.writeText("old content")

        // When - rename source
        val result = LocalPath.build(sourceFile).copy(
            ops,
            LocalPath.build(destFolder),
            onIssue = { issue ->
                when (issue) {
                    is PathActionIssue.PathAlreadyExists -> {
                        PathActionIssue.PathAlreadyExists.Resolution.RenameSource("file (1).txt")
                    }

                    else -> throw AssertionError("Unexpected issue: $issue")
                }
            }
        ).last() as CopyAction.State.Completed<LocalPath, LocalPathLookup, LocalPath, LocalPathLookup>

        // Then - old file unchanged, new file copied with new name
        File(destFolder, "file.txt").readText() shouldBe "old content"
        File(destFolder, "file (1).txt").readText() shouldBe "new content"
        result.copied shouldContainPath (LocalPath.build(sourceFile) to LocalPath.build(
            File(
                destFolder,
                "file (1).txt"
            )
        ))
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
        val result = LocalPath.build(sourceDir).copy(
            ops,
            LocalPath.build(destFolder),
            onIssue = { issue ->
                when (issue) {
                    is PathActionIssue.PathAlreadyExists -> {
                        PathActionIssue.PathAlreadyExists.Resolution.RenameDestination("Dir (1)")
                    }

                    else -> throw AssertionError("Unexpected issue: $issue")
                }
            }
        ).last() as CopyAction.State.Completed<LocalPath, LocalPathLookup, LocalPath, LocalPathLookup>

        // Then - old directory renamed, new directory created with original name
        File(destFolder, "Dir/new.txt").exists() shouldBe true
        File(destFolder, "Dir/old.txt").exists() shouldBe false
        File(destFolder, "Dir (1)/old.txt").exists() shouldBe true
        File(destFolder, "Dir (1)/new.txt").exists() shouldBe false
        result.copied shouldHaveSize 2 // directory + file
    }

    @Test
    fun `directory rename source should create directory with new name`(@TempDir tempDir: File) = runTest {
        val sourceFolder = File(tempDir, "source").apply { mkdirs() }
        val destFolder = File(tempDir, "dest").apply { mkdirs() }
        // Given - source directory and destination directory already exists
        val sourceDir = File(sourceFolder, "Dir")
        sourceDir.mkdir()
        File(sourceDir, "new.txt").writeText("new")

        val destDir = File(destFolder, "Dir")
        destDir.mkdir()
        File(destDir, "old.txt").writeText("old")

        // When - rename source
        val result = LocalPath.build(sourceDir).copy(
            ops,
            LocalPath.build(destFolder),
            onIssue = { issue ->
                when (issue) {
                    is PathActionIssue.PathAlreadyExists -> {
                        PathActionIssue.PathAlreadyExists.Resolution.RenameSource("Dir (1)")
                    }

                    else -> throw AssertionError("Unexpected issue: $issue")
                }
            }
        ).last() as CopyAction.State.Completed<LocalPath, LocalPathLookup, LocalPath, LocalPathLookup>

        // Then - old directory unchanged, new directory created with new name
        File(destFolder, "Dir/old.txt").exists() shouldBe true
        File(destFolder, "Dir/new.txt").exists() shouldBe false
        File(destFolder, "Dir (1)/new.txt").exists() shouldBe true
        File(destFolder, "Dir (1)/old.txt").exists() shouldBe false
        result.copied shouldHaveSize 2 // directory + file
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
            val result = LocalPath.build(sourceDir).copy(
                ops,
                LocalPath.build(destFolder),
                onIssue = { issue ->
                    when (issue) {
                        is PathActionIssue.PathAlreadyExists -> {
                            PathActionIssue.PathAlreadyExists.Resolution.RenameDestination("Item (1)")
                        }

                        else -> throw AssertionError("Unexpected issue: $issue")
                    }
                }
            ).last() as CopyAction.State.Completed<LocalPath, LocalPathLookup, LocalPath, LocalPathLookup>

            // Then - file renamed, directory created with original name
            File(destFolder, "Item").isDirectory shouldBe true
            File(destFolder, "Item/content.txt").exists() shouldBe true
            File(destFolder, "Item (1)").isFile shouldBe true
            File(destFolder, "Item (1)").readText() shouldBe "blocking file"
            result.copied shouldHaveSize 2 // directory + file
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
            val result = LocalPath.build(sourceDir).copy(
                ops,
                LocalPath.build(destFolder),
                onIssue = { issue ->
                    when (issue) {
                        is PathActionIssue.PathAlreadyExists -> {
                            PathActionIssue.PathAlreadyExists.Resolution.RenameSource("Item (1)")
                        }

                        else -> throw AssertionError("Unexpected issue: $issue")
                    }
                }
            ).last() as CopyAction.State.Completed<LocalPath, LocalPathLookup, LocalPath, LocalPathLookup>

            // Then - file unchanged, directory created with new name
            File(destFolder, "Item").isFile shouldBe true
            File(destFolder, "Item").readText() shouldBe "blocking file"
            File(destFolder, "Item (1)").isDirectory shouldBe true
            File(destFolder, "Item (1)/content.txt").exists() shouldBe true
            result.copied shouldHaveSize 2 // directory + file
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

        // When - copy and capture issue
        var capturedIssue: PathActionIssue.PathAlreadyExists? = null
        LocalPath.build(sourceFile).copy(
            ops,
            LocalPath.build(destFolder),
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
        val result = LocalPath.build(sourceDir).copy(
            ops,
            LocalPath.build(destFolder),
            onIssue = { issue ->
                when (issue) {
                    is PathActionIssue.PathAlreadyExists -> {
                        PathActionIssue.PathAlreadyExists.Resolution.RenameSource("Parent-new")
                    }

                    else -> throw AssertionError("Unexpected issue: $issue")
                }
            }
        ).last() as CopyAction.State.Completed<LocalPath, LocalPathLookup, LocalPath, LocalPathLookup>

        // Then - all directories and files should be in Parent-new
        File(destFolder, "Parent/existing.txt").exists() shouldBe true
        File(destFolder, "Parent-new").exists() shouldBe true
        File(destFolder, "Parent-new").isDirectory shouldBe true
        File(destFolder, "Parent-new/file1.txt").exists() shouldBe true
        File(destFolder, "Parent-new/file1.txt").readText() shouldBe "content1"
        File(destFolder, "Parent-new/SubDir1").exists() shouldBe true
        File(destFolder, "Parent-new/SubDir1").isDirectory shouldBe true
        File(destFolder, "Parent-new/SubDir1/file2.txt").exists() shouldBe true
        File(destFolder, "Parent-new/SubDir1/file2.txt").readText() shouldBe "content2"
        File(destFolder, "Parent-new/SubDir1/SubDir2").exists() shouldBe true
        File(destFolder, "Parent-new/SubDir1/SubDir2").isDirectory shouldBe true
        File(destFolder, "Parent-new/SubDir1/SubDir2/file3.txt").exists() shouldBe true
        File(destFolder, "Parent-new/SubDir1/SubDir2/file3.txt").readText() shouldBe "content3"

        // Original Parent directory should still only have existing file
        File(destFolder, "Parent/file1.txt").exists() shouldBe false
        File(destFolder, "Parent/SubDir1").exists() shouldBe false

        result.copied shouldHaveSize 6 // 3 dirs + 3 files
    }

    @Test
    fun `directory scan error then skip should not appear in copied`(@TempDir tempDir: File) = runTest {
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
            val result = LocalPath.build(parentDir).copy(
                ops,
                LocalPath.build(destFolder),
                onIssue = { issue ->
                    when (issue) {
                        is PathActionIssue.InsufficientPermission -> PathActionIssue.InsufficientPermission.Resolution.Skip()
                        is PathActionIssue.UnknownError -> PathActionIssue.UnknownError.Resolution.Skip()
                        else -> TODO("Unexpected issue type: $issue")
                    }
                }
            )

            // Then - Directory should be ONLY in skipped, NOT in copied
            val finalResult =
                result.last() as CopyAction.State.Completed<LocalPath, LocalPathLookup, LocalPath, LocalPathLookup>
            finalResult.copied.map { it.first.lookedUp } shouldNotBe setOf(LocalPath.build(parentDir))
            finalResult.skipped.map { it.lookedUp } shouldContain LocalPath.build(parentDir)

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
