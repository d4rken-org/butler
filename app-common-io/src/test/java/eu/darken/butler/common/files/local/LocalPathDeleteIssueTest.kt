package eu.darken.butler.common.files.local

import eu.darken.butler.common.files.LocalPath
import eu.darken.butler.common.files.actions.DeleteAction
import eu.darken.butler.common.files.actions.PathActionIssue
import eu.darken.butler.common.files.errors.PathPermissionDeniedException
import eu.darken.butler.common.files.metadata.OwnershipResolver
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
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
import testhelpers.TestClock
import java.io.File
import java.io.IOException
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration.Companion.seconds

class LocalPathDeleteIssueTest : BaseTest() {

    private val mockOwnershipResolver = mockk<OwnershipResolver>(relaxed = true)
    private val ops = LocalFileSystemOps(
        ownershipResolver = mockOwnershipResolver,
    )

    @Test
    fun `issue handling - skip resolution with apply to all`(@TempDir tempDir: File) = runTest {
        // Given - three targets whose deletion fails with a permission-classified error
        val file1 = File(tempDir, "file1.txt").apply { writeText("content1") }
        val file2 = File(tempDir, "file2.txt").apply { writeText("content2") }
        val file3 = File(tempDir, "file3.txt").apply { writeText("content3") }
        val paths = listOf(LocalPath.build(file1), LocalPath.build(file2), LocalPath.build(file3))

        // setReadOnly() is not a reliable barrier (unlink is governed by the parent directory), so
        // inject exactly the failure a real EACCES produces inside LocalFileSystemOps.
        val spyOps = spyk(ops)
        paths.forEach { path ->
            coEvery { spyOps.delete(path, recursive = false) } throws PathPermissionDeniedException(
                path = path,
                operation = "delete",
                reason = PathPermissionDeniedException.Reason.ACCESS_DENIED,
            )
        }

        val issuesEncountered = mutableListOf<PathActionIssue>()

        // When
        val result = paths.delete(
            spyOps,
            onIssue = { issue ->
                issuesEncountered.add(issue)
                when (issue) {
                    is PathActionIssue.InsufficientPermission ->
                        PathActionIssue.InsufficientPermission.Resolution.Skip(applyToAll = true)

                    else -> throw AssertionError("Unexpected issue: $issue")
                }
            }
        ).last() as DeleteAction.State.Completed

        // Then - only the first processed target reaches the handler. GenericPathDelete queues via
        // deferredDeletions.addFirst, which reverses the top-level order, so file3 is that target.
        issuesEncountered shouldHaveSize 1
        val issue = issuesEncountered.single().shouldBeInstanceOf<PathActionIssue.InsufficientPermission>()
        issue.source?.lookedUp shouldBe paths[2]
        issue.destinationPath shouldBe paths[2]

        result.deleted.shouldBeEmpty()
        result.skipped.map { it.lookedUp } shouldContainExactlyInAnyOrder paths
        // Every target was still attempted exactly once; apply-to-all suppresses the prompt, not the work
        paths.forEach { path -> coVerify(exactly = 1) { spyOps.delete(path, recursive = false) } }
        file1.exists() shouldBe true
        file2.exists() shouldBe true
        file3.exists() shouldBe true
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
        issues.forEach {
            val unknown = it.shouldBeInstanceOf<PathActionIssue.UnknownError>()
            unknown.source?.lookedUp shouldBe targetPath
            unknown.destinationPath shouldBe targetPath
        }
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
        issues.forEach {
            val unknown = it.shouldBeInstanceOf<PathActionIssue.UnknownError>()
            unknown.source?.lookedUp shouldBe path2
            unknown.destinationPath shouldBe path2
        }
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
        issues.forEach {
            val unknown = it.shouldBeInstanceOf<PathActionIssue.UnknownError>()
            unknown.source?.lookedUp shouldBe regularPath
            unknown.destinationPath shouldBe regularPath
        }

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

        val parentPath = LocalPath.build(parentDir)

        // setReadable(false) is not a reliable barrier (a privileged test process reads through it),
        // so fail the directory listing outright.
        val spyOps = spyk(ops)
        coEvery { spyOps.listFiles(parentPath) } throws SecurityException("injected scan failure")

        val issues = mutableListOf<PathActionIssue>()

        // When
        val result = parentPath.delete(
            spyOps,
            onIssue = { issue ->
                issues.add(issue)
                when (issue) {
                    is PathActionIssue.UnknownError -> PathActionIssue.UnknownError.Resolution.Skip()
                    else -> throw AssertionError("Unexpected issue: $issue")
                }
            }
        ).last() as DeleteAction.State.Completed

        // Then - handleScanError() carries no source and reports the scanned path as destination
        issues shouldHaveSize 1
        issues.forEach {
            val unknown = it.shouldBeInstanceOf<PathActionIssue.UnknownError>()
            unknown.source shouldBe null
            unknown.destinationPath shouldBe parentPath
        }

        // The directory is ONLY in skipped, never in deleted
        result.deleted.shouldBeEmpty()
        result.skipped.map { it.lookedUp } shouldBe listOf(parentPath)
        parentDir.exists() shouldBe true
        childFile.exists() shouldBe true
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
        issues.forEach {
            val unknown = it.shouldBeInstanceOf<PathActionIssue.UnknownError>()
            unknown.canRetry shouldBe true
            unknown.source shouldBe null
            unknown.destinationPath shouldBe parentPath
        }

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

        val child1 = File(dir1, "child1.txt").apply { writeText("content1") }
        val child2 = File(dir2, "child2.txt").apply { writeText("content2") }
        val child3 = File(dir3, "child3.txt").apply { writeText("content3") }

        val dirPaths = listOf(dir1, dir2, dir3).map { LocalPath.build(it) }

        // setReadable(false) is not a reliable barrier (a privileged test process reads through it),
        // so fail each directory listing outright.
        val spyOps = spyk(ops)
        dirPaths.forEach { path ->
            coEvery { spyOps.listFiles(path) } throws SecurityException("injected scan failure")
        }

        val issues = mutableListOf<PathActionIssue>()

        // When
        val result = dirPaths.delete(
            spyOps,
            onIssue = { issue ->
                issues.add(issue)
                when (issue) {
                    // handleScanError() maps everything except RouteUnavailableException to a
                    // retryable UnknownError, even a permission-flavoured SecurityException
                    is PathActionIssue.UnknownError -> PathActionIssue.UnknownError.Resolution.Skip(applyToAll = true)
                    else -> throw AssertionError("Unexpected issue: $issue")
                }
            }
        ).last() as DeleteAction.State.Completed

        // Then - Only 1 issue callback (not 3) due to applyToAll, raised by the first scanned dir
        issues shouldHaveSize 1
        issues.forEach {
            val unknown = it.shouldBeInstanceOf<PathActionIssue.UnknownError>()
            unknown.source shouldBe null
            unknown.destinationPath shouldBe dirPaths[0]
        }

        // All 3 directories in skipped, nothing deleted
        result.skipped.map { it.lookedUp } shouldContainExactlyInAnyOrder dirPaths
        result.deleted.shouldBeEmpty()

        // Apply-to-all suppresses the prompt, not the work
        dirPaths.forEach { path -> coVerify(exactly = 1) { spyOps.listFiles(path) } }

        // Disk state is unchanged
        dir1.exists() shouldBe true
        dir2.exists() shouldBe true
        dir3.exists() shouldBe true
        child1.exists() shouldBe true
        child2.exists() shouldBe true
        child3.exists() shouldBe true
    }

    @Test
    fun `multiple targets with error in one should continue with others`(@TempDir tempDir: File) = runTest {
        // Given - Three targets: A (succeeds), B (fails), C (succeeds)
        val targetA = File(tempDir, "targetA.txt").apply { writeText("content A") }
        val targetB = File(tempDir, "targetB.txt").apply { writeText("content B") }
        val targetC = File(tempDir, "targetC.txt").apply { writeText("content C") }

        val targetAPath = LocalPath.build(targetA)
        val targetBPath = LocalPath.build(targetB)
        val targetCPath = LocalPath.build(targetC)

        // setReadOnly() is not a reliable barrier (unlink is governed by the parent directory), so
        // fail only B's deletion.
        val spyOps = spyk(ops)
        coEvery { spyOps.delete(targetBPath, recursive = false) } throws IOException("injected delete failure")

        val issues = mutableListOf<PathActionIssue>()

        // When
        val result = listOf(targetAPath, targetBPath, targetCPath).delete(
            spyOps,
            onIssue = { issue ->
                issues.add(issue)
                when (issue) {
                    is PathActionIssue.UnknownError -> PathActionIssue.UnknownError.Resolution.Skip()
                    else -> throw AssertionError("Unexpected issue: $issue")
                }
            }
        ).last() as DeleteAction.State.Completed

        // Then - B is skipped and the rest of the batch still goes through
        issues shouldHaveSize 1
        issues.forEach {
            val unknown = it.shouldBeInstanceOf<PathActionIssue.UnknownError>()
            unknown.source?.lookedUp shouldBe targetBPath
            unknown.destinationPath shouldBe targetBPath
        }

        result.deleted.map { it.lookedUp } shouldContainExactlyInAnyOrder listOf(targetAPath, targetCPath)
        result.skipped.map { it.lookedUp } shouldBe listOf(targetBPath)
        targetA.exists() shouldBe false
        targetB.exists() shouldBe true
        targetC.exists() shouldBe false
    }

    @Test
    fun `deleted and skipped sets are always mutually exclusive`(@TempDir tempDir: File) = runTest {
        // Given - one target that deletes cleanly and one whose deletion always fails
        val healthyFile = File(tempDir, "healthy.txt").apply { writeText("healthy") }
        val failingFile = File(tempDir, "failing.txt").apply { writeText("failing") }

        val healthyPath = LocalPath.build(healthyFile)
        val failingPath = LocalPath.build(failingFile)

        // setReadOnly() is not a reliable barrier (unlink is governed by the parent directory), so
        // fail only the one deletion that has to end up in skipped.
        val spyOps = spyk(ops)
        coEvery { spyOps.delete(failingPath, recursive = false) } throws IOException("injected delete failure")

        val issues = mutableListOf<PathActionIssue>()

        // When
        val result = listOf(healthyPath, failingPath).delete(
            spyOps,
            onIssue = { issue ->
                issues.add(issue)
                when (issue) {
                    is PathActionIssue.UnknownError -> PathActionIssue.UnknownError.Resolution.Skip()
                    else -> throw AssertionError("Unexpected issue: $issue")
                }
            }
        ).last() as DeleteAction.State.Completed

        // Then - both sets are populated and disjoint (the bug this test pins)
        issues shouldHaveSize 1
        issues.forEach {
            val unknown = it.shouldBeInstanceOf<PathActionIssue.UnknownError>()
            unknown.source?.lookedUp shouldBe failingPath
            unknown.destinationPath shouldBe failingPath
        }

        val deletedPaths = result.deleted.map { it.lookedUp }.toSet()
        val skippedPaths = result.skipped.map { it.lookedUp }.toSet()
        deletedPaths shouldBe setOf(healthyPath)
        skippedPaths shouldBe setOf(failingPath)
        deletedPaths.intersect(skippedPaths).shouldBeEmpty()

        healthyFile.exists() shouldBe false
        failingFile.exists() shouldBe true
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
        issues.forEach {
            val unknown = it.shouldBeInstanceOf<PathActionIssue.UnknownError>()
            unknown.source?.lookedUp shouldBe targetPath
            unknown.destinationPath shouldBe targetPath
        }
        result.deleted.map { it.lookedUp } shouldBe listOf(targetPath)
        result.skipped.shouldBeEmpty()
        testFile.exists() shouldBe false
    }

    @Test
    fun `deletion error with skip should appear only in skipped not deleted`(@TempDir tempDir: File) = runTest {
        // Given
        val testFile = File(tempDir, "test.txt")
        testFile.writeText("content")
        val targetPath = LocalPath.build(testFile)

        // setReadOnly() is not a reliable barrier (unlink is governed by the parent directory), so
        // fail the deletion outright.
        val spyOps = spyk(ops)
        coEvery { spyOps.delete(targetPath, recursive = false) } throws IOException("injected delete failure")

        val issues = mutableListOf<PathActionIssue>()

        // When
        val result = targetPath.delete(
            spyOps,
            onIssue = { issue ->
                issues.add(issue)
                when (issue) {
                    is PathActionIssue.UnknownError -> PathActionIssue.UnknownError.Resolution.Skip()
                    else -> throw AssertionError("Unexpected issue: $issue")
                }
            }
        ).last() as DeleteAction.State.Completed

        // Then - Skip retires the item after a single attempt, and it lands only in skipped
        issues shouldHaveSize 1
        issues.forEach {
            val unknown = it.shouldBeInstanceOf<PathActionIssue.UnknownError>()
            unknown.source?.lookedUp shouldBe targetPath
            unknown.destinationPath shouldBe targetPath
        }
        coVerify(exactly = 1) { spyOps.delete(targetPath, recursive = false) }

        result.skipped.map { it.lookedUp } shouldBe listOf(targetPath)
        result.deleted.shouldBeEmpty()
        testFile.exists() shouldBe true
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
        issues.forEach {
            val unknown = it.shouldBeInstanceOf<PathActionIssue.UnknownError>()
            unknown.canRetry shouldBe true
            unknown.canSkip shouldBe true
            unknown.source?.lookedUp shouldBe targetPath
            unknown.destinationPath shouldBe targetPath
        }

        attempts shouldBe 2
        result.deleted.map { it.lookedUp } shouldBe listOf(targetPath)
        result.skipped.shouldBeEmpty()
        testFile.exists() shouldBe false
    }

    @Test
    fun `delete retry resolution allows multiple retry attempts`(@TempDir tempDir: File) = runTest {
        // Given - the deletion fails twice before the real filesystem call is allowed through
        val testFile = File(tempDir, "retrytest.txt")
        testFile.writeText("content")
        val targetPath = LocalPath.build(testFile)

        // setReadOnly() is not a reliable barrier (unlink is governed by the parent directory), so
        // fail the delete twice and let the third attempt hit the real filesystem.
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

        // Then - the handler really drove two retries and the third attempt deleted the file
        attempts shouldBe 3
        issues shouldHaveSize 2
        issues.forEach {
            val unknown = it.shouldBeInstanceOf<PathActionIssue.UnknownError>()
            unknown.source?.lookedUp shouldBe targetPath
            unknown.destinationPath shouldBe targetPath
        }
        result.deleted.map { it.lookedUp } shouldBe listOf(targetPath)
        result.skipped.shouldBeEmpty()
        testFile.exists() shouldBe false
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
            // A frozen clock would throttle everything after the first report down to a single
            // sample, leaving zipWithNext() empty and the monotonicity check vacuous
            progressClock = TestClock(autoAdvance = 1.seconds),
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
        issues.forEach {
            val unknown = it.shouldBeInstanceOf<PathActionIssue.UnknownError>()
            unknown.source?.lookedUp shouldBe path2
            unknown.destinationPath shouldBe path2
        }
        completed!!.deleted.map { it.lookedUp } shouldContainExactlyInAnyOrder listOf(path1, path2, path3)
        completed!!.skipped.shouldBeEmpty()

        // ... and the re-queued item never rewound the progress counter
        (progressValues.size > 1) shouldBe true
        progressValues.zipWithNext().forEach { (previous, next) -> (next >= previous) shouldBe true }
    }
}
