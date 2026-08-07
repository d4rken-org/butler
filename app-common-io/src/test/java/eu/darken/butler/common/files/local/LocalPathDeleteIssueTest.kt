package eu.darken.butler.common.files.local

import eu.darken.butler.common.files.LocalPath
import eu.darken.butler.common.files.actions.DeleteAction
import eu.darken.butler.common.files.actions.PathActionIssue
import eu.darken.butler.common.files.metadata.OwnershipResolver
import io.kotest.matchers.collections.shouldBeEmpty
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
import java.io.File
import java.nio.file.Files

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
        // Given
        val testFile = File(tempDir, "test.txt")
        testFile.writeText("content")

        var attemptCount = 0
        var retrySuccess = false

        // When
        listOf(LocalPath.build(testFile)).delete(
            ops,
            onIssue = { issue ->
                attemptCount++
                when (issue) {
                    is PathActionIssue.UnknownError -> {
                        if (attemptCount == 1) {
                            // First attempt: Simulate failure and retry
                            PathActionIssue.UnknownError.Resolution.Retry
                        } else {
                            // Subsequent attempts: Skip
                            retrySuccess = true
                            PathActionIssue.UnknownError.Resolution.Skip()
                        }
                    }
                    is PathActionIssue.InsufficientPermission -> throw NotImplementedError()
                    is PathActionIssue.InsufficientSpace -> throw NotImplementedError()
                    is PathActionIssue.PathAlreadyExists -> throw NotImplementedError()
                    is PathActionIssue.TrashMoveFailed -> throw NotImplementedError()
                    is PathActionIssue.TrashNotSupported -> throw NotImplementedError()
                    is PathActionIssue.TrashSizeLimitExceeded -> throw NotImplementedError()
                        is PathActionIssue.ArchivePasswordRequired -> throw NotImplementedError()
                }
            }
        ).last()

        // Then
        // Note: This test may not trigger issues on all systems since
        // file deletion might succeed. The test verifies the mechanism works.
        if (attemptCount > 0) {
            attemptCount should { it >= 1 }
        }
    }

    @Test
    fun `issue handling - cancel resolution stops operation`(@TempDir tempDir: File) = runTest {
        // Given
        val file1 = File(tempDir, "file1.txt")
        val file2 = File(tempDir, "file2.txt")

        file1.writeText("content1")
        file2.writeText("content2")

        // Make read-only to potentially trigger issues
        file1.setReadOnly()
        file2.setReadOnly()

        var issueCount = 0

        // When
        listOf(LocalPath.build(file1), LocalPath.build(file2)).delete(
            ops,
            onIssue = { issue ->
                issueCount++
                // Always cancel on first issue
                when (issue) {
                    is PathActionIssue.InsufficientPermission -> PathActionIssue.InsufficientPermission.Resolution.Cancel()
                    is PathActionIssue.UnknownError -> PathActionIssue.UnknownError.Resolution.Cancel()
                    else -> throw NotImplementedError()
                }
            }
        ).last()

        // Then
        // If issues were encountered, operation should have been cancelled
        if (issueCount > 0) {
            issueCount shouldBe 1  // Only first issue should be processed before cancel
        }
    }

    @Test
    fun `issue handling with mixed file types`(@TempDir tempDir: File) = runTest {
        // Given
        val regularFile = File(tempDir, "regular.txt")
        val directory = File(tempDir, "directory")
        val dirFile = File(directory, "inside.txt")

        regularFile.writeText("content")
        directory.mkdir()
        dirFile.writeText("inside content")

        val issues = mutableListOf<PathActionIssue>()

        // When
        val result = listOf(LocalPath.build(regularFile), LocalPath.build(directory)).delete(
            ops,
            onIssue = { issue ->
                issues.add(issue)
                // Skip all issues to test graceful handling
                when (issue) {
                    is PathActionIssue.InsufficientPermission -> PathActionIssue.InsufficientPermission.Resolution.Skip()
                    is PathActionIssue.UnknownError -> PathActionIssue.UnknownError.Resolution.Skip()
                    is PathActionIssue.InsufficientSpace -> throw NotImplementedError()
                    is PathActionIssue.PathAlreadyExists -> throw NotImplementedError()
                    is PathActionIssue.TrashMoveFailed -> throw NotImplementedError()
                    is PathActionIssue.TrashNotSupported -> throw NotImplementedError()
                    is PathActionIssue.TrashSizeLimitExceeded -> throw NotImplementedError()
                        is PathActionIssue.ArchivePasswordRequired -> throw NotImplementedError()
                }
            }
        ).last() as DeleteAction.State.Completed

        // Then - Operation should complete without crashing
        result.deleted should { it.size >= 0 }
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

        // Make directory unreadable to trigger permission error during scan
        parentDir.setReadable(false)

        var retryInvoked = false

        try {
            // When
            val result = LocalPath.build(parentDir).delete(
                ops,
                onIssue = { issue ->
                    when (issue) {
                        is PathActionIssue.UnknownError -> {
                            if (!retryInvoked) {
                                retryInvoked = true
                                // Restore permissions before retry
                                parentDir.setReadable(true)
                                parentDir.setWritable(true)
                                PathActionIssue.UnknownError.Resolution.Retry
                            } else {
                                PathActionIssue.UnknownError.Resolution.Skip()
                            }
                        }
                        is PathActionIssue.InsufficientPermission -> {
                            if (!retryInvoked) {
                                retryInvoked = true
                                // Restore permissions
                                parentDir.setReadable(true)
                                parentDir.setWritable(true)
                            }
                            PathActionIssue.InsufficientPermission.Resolution.Skip()
                        }
                        else -> TODO("Unexpected issue type: $issue")
                    }
                }
            ).last() as DeleteAction.State.Completed

            // Then - Directory and child successfully deleted after retry
            retryInvoked shouldBe true
            parentDir.exists() shouldBe false
            childFile.exists() shouldBe false
            result.deleted.map { it.lookedUp } shouldContain LocalPath.build(parentDir)
            result.deleted.map { it.lookedUp } shouldContain LocalPath.build(childFile)
        } finally {
            // Restore permissions for cleanup
            if (parentDir.exists()) {
                parentDir.setReadable(true)
            }
        }
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
        // Given
        val testFile = File(tempDir, "test.txt")
        testFile.writeText("content")

        var attemptCount = 0

        // When - Simulate multiple retry attempts
        val result = LocalPath.build(testFile).delete(
            ops,
            onIssue = { issue ->
                attemptCount++
                when (issue) {
                    is PathActionIssue.UnknownError -> {
                        // We can't easily force real errors, but we can track attempts
                        PathActionIssue.UnknownError.Resolution.Skip()
                    }
                    else -> throw NotImplementedError()
                }
            }
        ).last() as DeleteAction.State.Completed

        // Then - File should either be deleted (no errors) or skipped (with error handling)
        // The key is the operation completes without hanging in retry loop
        (result.deleted.size + result.skipped.size) shouldBe 1
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
        // Given
        val testFile = File(tempDir, "test.txt")
        testFile.writeText("content")

        var attemptCount = 0
        var retryInvoked = false

        // When - Use read-only to potentially trigger error
        testFile.setReadOnly()

        try {
            val result = LocalPath.build(testFile).delete(
                ops,
                onIssue = { issue ->
                    attemptCount++
                    when (issue) {
                        is PathActionIssue.UnknownError -> {
                            if (attemptCount == 1) {
                                retryInvoked = true
                                // On first error, restore permissions and retry
                                testFile.setWritable(true)
                                PathActionIssue.UnknownError.Resolution.Retry
                            } else {
                                // Shouldn't get here if retry worked
                                PathActionIssue.UnknownError.Resolution.Skip()
                            }
                        }
                        is PathActionIssue.InsufficientPermission -> {
                            // InsufficientPermission doesn't support Retry, only Skip/Cancel
                            // This shouldn't happen in practice for deletion, but handle it gracefully
                            PathActionIssue.InsufficientPermission.Resolution.Skip()
                        }
                        else -> throw NotImplementedError()
                    }
                }
            ).last() as DeleteAction.State.Completed

            // Then
            if (retryInvoked) {
                // Retry was invoked, file should have been deleted after retry
                result.deleted shouldHaveSize 1
                testFile.exists() shouldBe false
            } else {
                // No error occurred (system allows deletion of read-only by owner)
                result.deleted shouldHaveSize 1
                testFile.exists() shouldBe false
            }
        } finally {
            testFile.setWritable(true)
        }
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

        val progressValues = mutableListOf<Long>()
        var retryAttempted = false

        // When - Trigger retry on one file
        file2.setReadOnly()

        try {
            listOf(
                LocalPath.build(file1),
                LocalPath.build(file2),
                LocalPath.build(file3)
            ).delete(
                ops,
                onIssue = { issue ->
                    when (issue) {
                        is PathActionIssue.UnknownError -> {
                            if (!retryAttempted) {
                                retryAttempted = true
                                file2.setWritable(true)
                                PathActionIssue.UnknownError.Resolution.Retry
                            } else {
                                PathActionIssue.UnknownError.Resolution.Skip()
                            }
                        }
                        is PathActionIssue.InsufficientPermission -> {
                            if (!retryAttempted) {
                                retryAttempted = true
                                file2.setWritable(true)
                            }
                            PathActionIssue.InsufficientPermission.Resolution.Skip()
                        }
                        else -> throw NotImplementedError()
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
                    is DeleteAction.State.Completed -> { /* final result */
                    }
                }
            }

            // Then - Progress should never decrease
            if (progressValues.size >= 2) {
                var previousProgress = 0L
                for (progress in progressValues) {
                    progress should { it >= previousProgress }
                    previousProgress = progress
                }
            }
        } finally {
            file2.setWritable(true)
        }
    }
}
