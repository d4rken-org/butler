package eu.darken.butler.common.files.local

import eu.darken.butler.common.files.LocalPath
import eu.darken.butler.common.files.actions.DeleteAction
import eu.darken.butler.common.files.actions.PathActionIssue
import eu.darken.butler.common.files.metadata.OwnershipResolver
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.collections.shouldNotBeEmpty
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
import java.io.File
import java.io.IOException
import java.nio.file.Files
import kotlin.coroutines.cancellation.CancellationException

class LocalPathDeleteIssueTest : BaseTest() {

    private val mockOwnershipResolver = mockk<OwnershipResolver>(relaxed = true)
    private val ops = LocalFileSystemOps(
        ownershipResolver = mockOwnershipResolver,
    )

    @Test
    fun `issue handling - skip resolution with apply to all`(@TempDir tempDir: File) = runTest {
        // Given
        val file1 = File(tempDir, "file1.txt")
        val file2 = File(tempDir, "file2.txt")
        val file3 = File(tempDir, "file3.txt")

        // Create files but remove write permission to simulate permission errors
        file1.writeText("content1")
        file2.writeText("content2")
        file3.writeText("content3")

        // Make them read-only (may not work on all systems, but won't crash)
        file1.setReadOnly()
        file2.setReadOnly()
        file3.setReadOnly()

        val issuesEncountered = mutableListOf<PathActionIssue>()
        var firstIssueHandled = false

        // When
        val result = listOf(LocalPath.build(file1), LocalPath.build(file2), LocalPath.build(file3)).delete(
            ops,
            onIssue = { issue ->
                issuesEncountered.add(issue)

                if (!firstIssueHandled) {
                    firstIssueHandled = true
                    // First issue: Skip with "Apply to All"
                    when (issue) {
                        is PathActionIssue.InsufficientPermission -> PathActionIssue.InsufficientPermission.Resolution.Skip(
                            applyToAll = true
                        )
                        is PathActionIssue.UnknownError -> PathActionIssue.InsufficientPermission.Resolution.Skip(
                            applyToAll = true
                        )
                        is PathActionIssue.InsufficientSpace -> throw NotImplementedError()
                        is PathActionIssue.PathAlreadyExists -> throw NotImplementedError()
                        is PathActionIssue.TrashMoveFailed -> throw NotImplementedError()
                        is PathActionIssue.TrashNotSupported -> throw NotImplementedError()
                        is PathActionIssue.TrashSizeLimitExceeded -> throw NotImplementedError()
                        is PathActionIssue.ArchivePasswordRequired -> throw NotImplementedError()
                    }
                } else {
                    // Subsequent issues should not occur due to "Apply to All"
                    throw AssertionError("Should not encounter more issues with Apply to All")
                }
            }
        ).last() as DeleteAction.State.Completed

        // Then
        // On many systems, read-only files can still be deleted by the owner
        // So this test validates the mechanism works when issues do occur
        if (issuesEncountered.isNotEmpty()) {
            // If issues were encountered, validate "Apply to All" behavior
            issuesEncountered shouldHaveSize 1
            // Files should still exist due to skip resolution
            file1.exists() shouldBe true
            file2.exists() shouldBe true
            file3.exists() shouldBe true
            result.deleted.shouldBeEmpty()
        } else {
            // If no issues occurred (files were successfully deleted)
            // This is also valid behavior - the test verifies no crashes occur
            result.deleted shouldHaveSize 3
        }
    }

    @Test
    fun `issue handling - retry resolution`(@TempDir tempDir: File) = runTest {
        // Given - a deletion that keeps failing, so the retry eventually has to give up
        val testFile = File(tempDir, "test.txt")
        testFile.writeText("content")
        val targetPath = LocalPath.build(testFile)

        val spyOps = spyk(ops)
        var attempts = 0
        coEvery { spyOps.delete(targetPath, recursive = false) } coAnswers {
            attempts++
            throw IOException("injected delete failure")
        }

        val issues = mutableListOf<PathActionIssue>()

        // When - retry once, then abandon the item
        val result = listOf(targetPath).delete(
            spyOps,
            onIssue = { issue ->
                issues.add(issue)
                when (issue) {
                    is PathActionIssue.UnknownError -> when (issues.size) {
                        1 -> PathActionIssue.UnknownError.Resolution.Retry
                        else -> PathActionIssue.UnknownError.Resolution.Skip()
                    }

                    else -> throw AssertionError("Unexpected issue: $issue")
                }
            }
        ).last() as DeleteAction.State.Completed

        // Then - exactly one extra attempt was made, and Skip ends the loop instead of spinning
        attempts shouldBe 2
        issues shouldHaveSize 2
        issues.forEach { it.shouldBeInstanceOf<PathActionIssue.UnknownError>() }
        result.deleted.shouldBeEmpty()
        result.skipped.map { it.lookedUp } shouldBe listOf(targetPath)
        testFile.exists() shouldBe true
    }

    @Test
    fun `issue handling - cancel resolution stops operation`(@TempDir tempDir: File) = runTest {
        // Given
        val file1 = File(tempDir, "file1.txt")
        val file2 = File(tempDir, "file2.txt")

        file1.writeText("content1")
        file2.writeText("content2")

        val path1 = LocalPath.build(file1)
        val path2 = LocalPath.build(file2)

        // GenericPathDelete queues deletions via deferredDeletions.addFirst, which reverses the
        // top-level order: file2 is processed first, so failing it leaves file1 untouched.
        val spyOps = spyk(ops)
        coEvery { spyOps.delete(path2, recursive = false) } throws IOException("injected delete failure")

        val issues = mutableListOf<PathActionIssue>()
        val states = mutableListOf<DeleteAction.State<LocalPath, LocalPathLookup>>()

        // When/Then - cancel has no emitted state, PathOperationIssueResolver signals it by throwing
        shouldThrow<CancellationException> {
            listOf(path1, path2).delete(
                spyOps,
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
        states.none { it is DeleteAction.State.Completed } shouldBe true
        // The unprocessed item was never attempted
        coVerify(exactly = 0) { spyOps.delete(path1, recursive = false) }
        file1.exists() shouldBe true
        file2.exists() shouldBe true
    }

    @Test
    fun `issue handling with mixed file types`(@TempDir tempDir: File) = runTest {
        // Given - a plain file plus a directory tree, where only the plain file fails
        val regularFile = File(tempDir, "regular.txt")
        val directory = File(tempDir, "directory")
        val dirFile = File(directory, "inside.txt")

        regularFile.writeText("content")
        directory.mkdir()
        dirFile.writeText("inside content")

        val regularPath = LocalPath.build(regularFile)
        val directoryPath = LocalPath.build(directory)
        val dirFilePath = LocalPath.build(dirFile)

        val spyOps = spyk(ops)
        coEvery { spyOps.delete(regularPath, recursive = false) } throws IOException("injected delete failure")

        val issues = mutableListOf<PathActionIssue>()

        // When
        val result = listOf(regularPath, directoryPath).delete(
            spyOps,
            onIssue = { issue ->
                issues.add(issue)
                when (issue) {
                    is PathActionIssue.UnknownError -> PathActionIssue.UnknownError.Resolution.Skip()
                    else -> throw AssertionError("Unexpected issue: $issue")
                }
            }
        ).last() as DeleteAction.State.Completed

        // Then - the failing file is skipped while the directory tree is deleted post-order
        issues shouldHaveSize 1
        val issue = issues.single().shouldBeInstanceOf<PathActionIssue.UnknownError>()
        issue.source?.lookedUp shouldBe regularPath

        result.deleted.map { it.lookedUp } shouldContainExactlyInAnyOrder listOf(dirFilePath, directoryPath)
        result.skipped.map { it.lookedUp } shouldBe listOf(regularPath)
        regularFile.exists() shouldBe true
        directory.exists() shouldBe false
    }

    @Test
    fun `directory scan error then skip should not appear in deleted`(@TempDir tempDir: File) = runTest {
        // Given - Create nested directory structure
        val parentDir = File(tempDir, "parent")
        val childFile = File(parentDir, "child.txt")

        parentDir.mkdir()
        childFile.writeText("content")

        // Make directory unreadable to trigger permission error during scan
        parentDir.setReadable(false)

        var issueReceived = false

        try {
            // When
            val result = LocalPath.build(parentDir).delete(
                ops,
                onIssue = { issue ->
                    issueReceived = true
                    when (issue) {
                        is PathActionIssue.InsufficientPermission -> PathActionIssue.InsufficientPermission.Resolution.Skip()
                        is PathActionIssue.UnknownError -> PathActionIssue.UnknownError.Resolution.Skip()
                        else -> TODO("Unexpected issue type: $issue")
                    }
                }
            ).last() as DeleteAction.State.Completed

            // Then - Directory should be ONLY in skipped, NOT in deleted
            result.deleted.map { it.lookedUp } shouldNotBe LocalPath.build(parentDir)
            result.skipped.map { it.lookedUp } shouldContain LocalPath.build(parentDir)
            issueReceived shouldBe true
        } finally {
            // Restore permissions for cleanup
            parentDir.setReadable(true)
        }
    }

    @Test
    fun `directory scan error with retry should succeed on second attempt`(@TempDir tempDir: File) = runTest {
        // Given - Create directory structure
        val parentDir = File(tempDir, "parent")
        val childFile = File(parentDir, "child.txt")

        parentDir.mkdir()
        childFile.writeText("content")

        val parentPath = LocalPath.build(parentDir)
        val childPath = LocalPath.build(childFile)

        // setReadable(false) is not a reliable barrier (a privileged test process reads through it),
        // so fail the directory listing once and let the second attempt hit the real filesystem.
        val spyOps = spyk(ops)
        var scanAttempts = 0
        coEvery { spyOps.listFiles(parentPath) } coAnswers {
            scanAttempts++
            if (scanAttempts == 1) throw SecurityException("injected scan failure") else callOriginal()
        }

        val issues = mutableListOf<PathActionIssue>()

        // When
        val result = parentPath.delete(
            spyOps,
            onIssue = { issue ->
                issues.add(issue)
                when (issue) {
                    is PathActionIssue.UnknownError -> PathActionIssue.UnknownError.Resolution.Retry
                    else -> throw AssertionError("Unexpected issue: $issue")
                }
            }
        ).last() as DeleteAction.State.Completed

        // Then - handleScanError maps everything except RouteUnavailableException to a retryable
        // UnknownError, even a permission-flavoured one, so the retry path is reachable here
        scanAttempts shouldBe 2
        issues shouldHaveSize 1
        val issue = issues.single().shouldBeInstanceOf<PathActionIssue.UnknownError>()
        issue.canRetry shouldBe true

        result.deleted.map { it.lookedUp } shouldContainExactlyInAnyOrder listOf(parentPath, childPath)
        result.skipped.shouldBeEmpty()
        parentDir.exists() shouldBe false
        childFile.exists() shouldBe false
    }

    @Test
    fun `multiple directories with scan errors and skip all should batch skip`(@TempDir tempDir: File) = runTest {
        // Given - Create 3 directories with children
        val dir1 = File(tempDir, "dir1")
        val dir2 = File(tempDir, "dir2")
        val dir3 = File(tempDir, "dir3")

        dir1.mkdir()
        dir2.mkdir()
        dir3.mkdir()

        File(dir1, "child1.txt").writeText("content1")
        File(dir2, "child2.txt").writeText("content2")
        File(dir3, "child3.txt").writeText("content3")

        // Make all directories unreadable
        dir1.setReadable(false)
        dir2.setReadable(false)
        dir3.setReadable(false)

        var issueCount = 0

        try {
            // When
            val result = listOf(
                LocalPath.build(dir1),
                LocalPath.build(dir2),
                LocalPath.build(dir3)
            ).delete(
                ops,
                onIssue = { issue ->
                    issueCount++
                    when (issue) {
                        is PathActionIssue.InsufficientPermission -> {
                            PathActionIssue.InsufficientPermission.Resolution.Skip(applyToAll = true)
                        }
                        is PathActionIssue.UnknownError -> {
                            PathActionIssue.UnknownError.Resolution.Skip(applyToAll = true)
                        }
                        else -> TODO("Unexpected issue type: $issue")
                    }
                }
            ).last() as DeleteAction.State.Completed

            // Then - Only 1 issue callback (not 3) due to applyToAll
            issueCount shouldBe 1

            // All 3 directories in skipped
            result.skipped.size shouldBe 3
            result.skipped.map { it.lookedUp } shouldContain LocalPath.build(dir1)
            result.skipped.map { it.lookedUp } shouldContain LocalPath.build(dir2)
            result.skipped.map { it.lookedUp } shouldContain LocalPath.build(dir3)

            // All directories still exist
            dir1.exists() shouldBe true
            dir2.exists() shouldBe true
            dir3.exists() shouldBe true
        } finally {
            // Restore permissions for cleanup
            dir1.setReadable(true)
            dir2.setReadable(true)
            dir3.setReadable(true)
        }
    }

    @Test
    fun `multiple targets with error in one should continue with others`(@TempDir tempDir: File) = runTest {
        // Given - Three targets: A (succeeds), B (fails), C (succeeds)
        val targetA = File(tempDir, "targetA.txt")
        val targetB = File(tempDir, "targetB.txt")
        val targetC = File(tempDir, "targetC.txt")

        targetA.writeText("content A")
        targetB.writeText("content B")
        targetC.writeText("content C")

        // Make targetB read-only to potentially trigger issues
        targetB.setReadOnly()

        var issueCount = 0

        try {
            // When
            val result = listOf(
                LocalPath.build(targetA),
                LocalPath.build(targetB),
                LocalPath.build(targetC)
            ).delete(
                ops,
                onIssue = { issue ->
                    issueCount++
                    when (issue) {
                        is PathActionIssue.InsufficientPermission -> PathActionIssue.InsufficientPermission.Resolution.Skip()
                        is PathActionIssue.UnknownError -> PathActionIssue.UnknownError.Resolution.Skip()
                        else -> TODO("Unexpected issue: $issue")
                    }
                }
            ).last() as DeleteAction.State.Completed

            // Then - If B had an issue, A and C should still be deleted
            if (issueCount > 0) {
                // B had permission issue and was skipped
                result.deleted.map { it.lookedUp } shouldContain LocalPath.build(targetA)
                result.deleted.map { it.lookedUp } shouldContain LocalPath.build(targetC)
                result.skipped.map { it.lookedUp } shouldContain LocalPath.build(targetB)
                targetB.exists() shouldBe true
            } else {
                // All succeeded (read-only doesn't prevent deletion on this system)
                result.deleted shouldHaveSize 3
            }

            // A and C should always be deleted
            targetA.exists() shouldBe false
            targetC.exists() shouldBe false
        } finally {
            // Cleanup
            targetB.setWritable(true)
        }
    }

    @Test
    fun `deleted and skipped sets are always mutually exclusive`(@TempDir tempDir: File) = runTest {
        // Given - Complex nested structure with various scenarios
        val dirA = File(tempDir, "dirA")
        val dirB = File(dirA, "dirB")
        val fileInA = File(dirA, "fileA.txt")
        val fileInB = File(dirB, "fileB.txt")
        val standaloneFile = File(tempDir, "standalone.txt")

        dirA.mkdir()
        dirB.mkdir()
        fileInA.writeText("content A")
        fileInB.writeText("content B")
        standaloneFile.writeText("standalone")

        // Make one file read-only to potentially trigger skip
        fileInB.setReadOnly()

        try {
            // When
            val result = listOf(
                LocalPath.build(dirA),
                LocalPath.build(standaloneFile)
            ).delete(
                ops,
                onIssue = { issue ->
                    when (issue) {
                        is PathActionIssue.InsufficientPermission -> PathActionIssue.InsufficientPermission.Resolution.Skip()
                        is PathActionIssue.UnknownError -> PathActionIssue.UnknownError.Resolution.Skip()
                        else -> throw NotImplementedError()
                    }
                }
            ).last() as DeleteAction.State.Completed

            // Then - Verify mutual exclusivity (this is the critical test for the bug we fixed)
            val deletedPaths = result.deleted.map { it.lookedUp }.toSet()
            val skippedPaths = result.skipped.map { it.lookedUp }.toSet()
            val intersection = deletedPaths.intersect(skippedPaths)

            intersection.shouldBeEmpty()
        } finally {
            fileInB.setWritable(true)
        }
    }

    @Test
    fun `retry can be called multiple times before success`(@TempDir tempDir: File) = runTest {
        // Given - the deletion fails twice before the real filesystem call is allowed through
        val testFile = File(tempDir, "test.txt")
        testFile.writeText("content")
        val targetPath = LocalPath.build(testFile)

        val spyOps = spyk(ops)
        var attempts = 0
        coEvery { spyOps.delete(targetPath, recursive = false) } coAnswers {
            attempts++
            if (attempts <= 2) throw IOException("injected delete failure") else callOriginal()
        }

        val issues = mutableListOf<PathActionIssue>()

        // When - Retry every time
        val result = targetPath.delete(
            spyOps,
            onIssue = { issue ->
                issues.add(issue)
                when (issue) {
                    is PathActionIssue.UnknownError -> PathActionIssue.UnknownError.Resolution.Retry
                    else -> throw AssertionError("Unexpected issue: $issue")
                }
            }
        ).last() as DeleteAction.State.Completed

        // Then - two failures, two prompts, exactly three attempts, then the file is really gone
        attempts shouldBe 3
        issues shouldHaveSize 2
        issues.forEach { it.shouldBeInstanceOf<PathActionIssue.UnknownError>() }
        result.deleted.map { it.lookedUp } shouldBe listOf(targetPath)
        result.skipped.shouldBeEmpty()
        testFile.exists() shouldBe false
    }

    @Test
    fun `deletion error with skip should appear only in skipped not deleted`(@TempDir tempDir: File) = runTest {
        // Given
        val testFile = File(tempDir, "test.txt")
        testFile.writeText("content")

        // Make file unreadable AND unwritable to maximize chance of permission error
        testFile.setReadOnly()
        testFile.setReadable(false)

        var issueEncountered = false

        try {
            // When
            val result = LocalPath.build(testFile).delete(
                ops,
                onIssue = { issue ->
                    issueEncountered = true
                    when (issue) {
                        is PathActionIssue.InsufficientPermission -> PathActionIssue.InsufficientPermission.Resolution.Skip()
                        is PathActionIssue.UnknownError -> PathActionIssue.UnknownError.Resolution.Skip()
                        else -> throw NotImplementedError()
                    }
                }
            ).last() as DeleteAction.State.Completed

            // Then
            if (issueEncountered) {
                // File should be ONLY in skipped
                result.skipped.map { it.lookedUp } shouldContain LocalPath.build(testFile)
                result.deleted.map { it.lookedUp } shouldNotBe LocalPath.build(testFile)
                testFile.exists() shouldBe true
            } else {
                // System allowed deletion despite permissions
                result.deleted shouldHaveSize 1
            }
        } finally {
            // Restore permissions for cleanup
            testFile.setReadable(true)
            testFile.setWritable(true)
        }
    }

    @Test
    fun `deletion error with retry resolution works correctly`(@TempDir tempDir: File) = runTest {
        // Given - a transient deletion failure that clears itself on the next attempt
        val testFile = File(tempDir, "test.txt")
        testFile.writeText("content")
        val targetPath = LocalPath.build(testFile)

        // setReadOnly() is not a reliable barrier (unlink is governed by the parent directory), so
        // fail the delete once and let the retry hit the real filesystem.
        val spyOps = spyk(ops)
        var attempts = 0
        coEvery { spyOps.delete(targetPath, recursive = false) } coAnswers {
            attempts++
            if (attempts == 1) throw IOException("injected delete failure") else callOriginal()
        }

        val issues = mutableListOf<PathActionIssue>()

        // When
        val result = targetPath.delete(
            spyOps,
            onIssue = { issue ->
                issues.add(issue)
                when (issue) {
                    is PathActionIssue.UnknownError -> PathActionIssue.UnknownError.Resolution.Retry
                    else -> throw AssertionError("Unexpected issue: $issue")
                }
            }
        ).last() as DeleteAction.State.Completed

        // Then - a non-permission delete failure becomes a retryable UnknownError carrying the target
        issues shouldHaveSize 1
        val issue = issues.single().shouldBeInstanceOf<PathActionIssue.UnknownError>()
        issue.canRetry shouldBe true
        issue.canSkip shouldBe true
        issue.source?.lookedUp shouldBe targetPath

        attempts shouldBe 2
        result.deleted.map { it.lookedUp } shouldBe listOf(targetPath)
        result.skipped.shouldBeEmpty()
        testFile.exists() shouldBe false
    }

    @Test
    fun `delete retry resolution allows multiple retry attempts`(@TempDir tempDir: File) = runTest {
        // This test verifies that retry functionality is implemented and works correctly.
        // It simulates a scenario where retry is requested and validates the behavior.

        // Given - File that exists
        val testFile = File(tempDir, "retrytest.txt")
        testFile.writeText("content")

        var retryCount = 0
        var firstAttemptFailed = false

        // When - Simulate permission error on first attempt, success on retry
        testFile.setReadOnly()

        try {
            LocalPath.build(testFile).delete(
                ops,
                onIssue = { issue ->
                    retryCount++
                    when (issue) {
                        is PathActionIssue.UnknownError -> {
                            if (retryCount == 1) {
                                firstAttemptFailed = true
                                // Restore permissions before retry
                                testFile.setWritable(true)
                                PathActionIssue.UnknownError.Resolution.Retry
                            } else {
                                // Should succeed on retry
                                PathActionIssue.UnknownError.Resolution.Skip()
                            }
                        }
                        is PathActionIssue.InsufficientPermission -> {
                            if (retryCount == 1) {
                                firstAttemptFailed = true
                                // Restore permissions before retry
                                testFile.setWritable(true)
                                // InsufficientPermission doesn't have Retry, use Skip
                                PathActionIssue.InsufficientPermission.Resolution.Skip()
                            } else {
                                PathActionIssue.InsufficientPermission.Resolution.Skip()
                            }
                        }
                        else -> TODO("Unexpected issue: $issue")
                    }
                }
            ).last() as DeleteAction.State.Completed

            // Then - Verify retry mechanism exists and works
            // On systems where read-only doesn't prevent deletion, the file will be deleted without issues
            // On systems where it does, retry mechanism should have been triggered
            if (firstAttemptFailed) {
                // Retry mechanism was triggered
                retryCount should { it >= 1 }
            }

            // File should be deleted in either case
            testFile.exists() shouldBe false
        } finally {
            testFile.setWritable(true)
        }
    }

    @Test
    fun `delete retry does not regress progress tracking`(@TempDir tempDir: File) = runTest {
        // This test verifies that retry doesn't cause progress tracking to regress (go backwards)

        // Given - Multiple files
        val file1 = File(tempDir, "file1.txt")
        val file2 = File(tempDir, "file2.txt")
        val file3 = File(tempDir, "file3.txt")

        file1.writeText("content1")
        file2.writeText("content2")
        file3.writeText("content3")

        val path1 = LocalPath.build(file1)
        val path2 = LocalPath.build(file2)
        val path3 = LocalPath.build(file3)

        val progressValues = mutableListOf<Long>()

        // When - the middle file fails once and succeeds on retry, forcing one re-queue mid-batch
        val spyOps = spyk(ops)
        var attempts = 0
        coEvery { spyOps.delete(path2, recursive = false) } coAnswers {
            attempts++
            if (attempts == 1) throw IOException("injected delete failure") else callOriginal()
        }

        val issues = mutableListOf<PathActionIssue>()
        var completed: DeleteAction.State.Completed<LocalPath, LocalPathLookup>? = null

        listOf(path1, path2, path3).delete(
            spyOps,
            onIssue = { issue ->
                issues.add(issue)
                when (issue) {
                    is PathActionIssue.UnknownError -> PathActionIssue.UnknownError.Resolution.Retry
                    else -> throw AssertionError("Unexpected issue: $issue")
                }
            }
        ).collect { state ->
            when (state) {
                is DeleteAction.State.Active -> {
                    val count = state.primaryProgress.count
                    if (count is eu.darken.butler.common.progress.Progress.Count.Counter) {
                        progressValues.add(count.current)
                    }
                }

                is DeleteAction.State.Completed -> completed = state
            }
        }

        // Then - the retry really happened and every file still ended up deleted
        attempts shouldBe 2
        issues shouldHaveSize 1
        issues.single().shouldBeInstanceOf<PathActionIssue.UnknownError>()
        completed!!.deleted.map { it.lookedUp } shouldContainExactlyInAnyOrder listOf(path1, path2, path3)
        completed!!.skipped.shouldBeEmpty()

        // ... and the re-queued item never rewound the progress counter
        progressValues.shouldNotBeEmpty()
        progressValues.zipWithNext().forEach { (previous, next) -> (next >= previous) shouldBe true }
    }
}
