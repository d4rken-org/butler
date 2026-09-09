package eu.darken.butler.common.files.operations

import eu.darken.butler.common.files.FileSystemOps
import eu.darken.butler.common.files.LocalPath
import eu.darken.butler.common.files.MoveOutcome
import eu.darken.butler.common.files.actions.PathActionIssue
import eu.darken.butler.common.files.errors.WriteException
import eu.darken.butler.common.files.local.LocalPathLookup
import eu.darken.butler.common.files.local.operations.core.PathOperationIssueResolver
import eu.darken.butler.common.files.local.operations.core.PathOperationProgressTracker
import eu.darken.butler.common.files.metadata.FileType
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import org.junit.Before
import org.junit.Test
import testhelpers.BaseTest
import testhelpers.coroutine.runTest2

class TransferConflictResolverTest : BaseTest() {

    private val destOps = mockk<FileSystemOps<LocalPath, LocalPathLookup>>()
    private val progressTracker = mockk<PathOperationProgressTracker>(relaxed = true)

    private val sourcePath = LocalPath.build("/src/file.txt")
    private val destination = LocalPath.build("/dest/file.txt")

    private fun lookupOf(path: LocalPath, isDir: Boolean = false) = LocalPathLookup(
        lookedUp = path,
        fileType = if (isDir) FileType.DIRECTORY else FileType.FILE,
        size = 1L,
        modifiedAt = null,
    )

    @Before
    fun setup() {
        coEvery { destOps.exists(any()) } returns false
        coEvery { destOps.delete(any(), any()) } returns true
        coEvery { destOps.move(any(), any()) } returns MoveOutcome.Moved
    }

    private fun resolver(
        resolution: PathActionIssue.PathAlreadyExists.Resolution,
    ): Pair<TransferConflictResolver<LocalPath, LocalPathLookup, LocalPath, LocalPathLookup>, PathOperationIssueResolver> {
        val issueResolver = PathOperationIssueResolver(onIssue = { resolution })
        val conflictResolver = TransferConflictResolver<LocalPath, LocalPathLookup, LocalPath, LocalPathLookup>(
            destOps = destOps,
            issueResolver = issueResolver,
            progressTracker = progressTracker,
            tag = "test",
        )
        return conflictResolver to issueResolver
    }

    private suspend fun TransferConflictResolver<LocalPath, LocalPathLookup, LocalPath, LocalPathLookup>.resolve(
        onOverwrite: (Boolean) -> Unit = {},
        onRenameDestination: () -> Unit = {},
    ) = processResolveConflict(
        sourceLookup = lookupOf(sourcePath),
        destination = destination,
        destLookup = lookupOf(destination),
        canMerge = false,
        onSkip = { _, _ -> },
        onOverwrite = onOverwrite,
        onMerge = {},
        onRenameSource = {},
        onRenameDestination = onRenameDestination,
    )

    private fun trackingResolver(
        tracker: PathOperationProgressTracker,
        issueResolver: PathOperationIssueResolver,
    ) = TransferConflictResolver<LocalPath, LocalPathLookup, LocalPath, LocalPathLookup>(
        destOps = destOps,
        issueResolver = issueResolver,
        progressTracker = tracker,
        tag = "test",
    )

    @Test
    fun `merge-all directory conflict advances progress exactly once`() = runTest2 {
        val tracker = PathOperationProgressTracker()
        tracker.totalItems = 2
        val issueResolver = PathOperationIssueResolver(
            onIssue = { PathActionIssue.PathAlreadyExists.Resolution.Merge(applyToAll = true) },
        )
        val conflictResolver = trackingResolver(tracker, issueResolver)
        val sourceLookup = lookupOf(sourcePath, isDir = true)
        val destLookup = lookupOf(destination, isDir = true)

        // The first conflict goes through the user and arms merge-all
        conflictResolver.processResolveConflict(
            sourceLookup = sourceLookup,
            destination = destination,
            destLookup = destLookup,
            canMerge = true,
            onSkip = { _, _ -> },
            onOverwrite = {},
            onMerge = {},
            onRenameSource = {},
            onRenameDestination = {},
        )

        tracker.itemsProcessed shouldBe 1

        // The second is resolved by the flag alone, without reaching the user
        conflictResolver.handleDirectoryConflict(
            sourceLookup = sourceLookup,
            destination = destination,
            destLookup = destLookup,
            onSkip = { _, _ -> },
            onRename = {},
            onMerge = {},
            onOverwrite = {},
            onResolveConflict = { throw AssertionError("merge-all should have resolved this") },
            onIssue = issueResolver.onIssue,
        )

        tracker.itemsProcessed shouldBe 2
    }

    @Test
    fun `auto-merged directory conflict advances progress`() = runTest2 {
        val tracker = PathOperationProgressTracker()
        tracker.totalItems = 1
        val conflictResolver = trackingResolver(tracker, PathOperationIssueResolver(onIssue = null))

        conflictResolver.handleDirectoryConflict(
            sourceLookup = lookupOf(sourcePath, isDir = true),
            destination = destination,
            destLookup = lookupOf(destination, isDir = true),
            onSkip = { _, _ -> },
            onRename = {},
            onMerge = {},
            onOverwrite = {},
            onResolveConflict = { throw AssertionError("auto-merge should have resolved this") },
            onIssue = null,
        )

        tracker.itemsProcessed shouldBe 1
    }

    @Test
    fun `overwrite with successful delete continues`() = runTest2 {
        val (conflictResolver, _) = resolver(PathActionIssue.PathAlreadyExists.Resolution.Overwrite())
        var overwritten = false

        conflictResolver.resolve(onOverwrite = { overwritten = true })

        overwritten shouldBe true
        coVerify { destOps.delete(destination, false) }
    }

    @Test
    fun `overwrite with failed delete throws instead of continuing`() = runTest2 {
        val (conflictResolver, _) = resolver(PathActionIssue.PathAlreadyExists.Resolution.Overwrite())
        coEvery { destOps.delete(any(), any()) } returns false
        var overwritten = false

        shouldThrow<WriteException> {
            conflictResolver.resolve(onOverwrite = { overwritten = true })
        }

        overwritten shouldBe false
    }

    @Test
    fun `rename destination with successful move re-queues`() = runTest2 {
        val (conflictResolver, _) = resolver(
            PathActionIssue.PathAlreadyExists.Resolution.RenameDestination(newName = "file (1).txt"),
        )
        var requeued = false

        conflictResolver.resolve(onRenameDestination = { requeued = true })

        requeued shouldBe true
        coVerify { destOps.move(destination, LocalPath.build("/dest/file (1).txt")) }
    }

    @Test
    fun `rename destination that does not move throws instead of re-queueing`() = runTest2 {
        val (conflictResolver, _) = resolver(
            PathActionIssue.PathAlreadyExists.Resolution.RenameDestination(newName = "file (1).txt"),
        )
        coEvery { destOps.move(any(), any()) } returns MoveOutcome.NotSupported("test")
        var requeued = false

        shouldThrow<WriteException> {
            conflictResolver.resolve(onRenameDestination = { requeued = true })
        }

        requeued shouldBe false
    }

    @Test
    fun `overwrite-all with failed delete throws`() = runTest2 {
        val (conflictResolver, _) = resolver(
            PathActionIssue.PathAlreadyExists.Resolution.Overwrite(applyToAll = true),
        )
        // First interactive resolution succeeds and arms the overwrite-all flag
        conflictResolver.resolve()

        coEvery { destOps.delete(any(), any()) } returns false
        var overwritten = false

        shouldThrow<WriteException> {
            conflictResolver.handleFileConflict(
                sourceLookup = lookupOf(sourcePath),
                destination = destination,
                destLookup = lookupOf(destination),
                onSkip = {},
                onRename = {},
                onOverwrite = { overwritten = true },
                onResolveConflict = {},
                onIssue = { PathActionIssue.PathAlreadyExists.Resolution.Overwrite() },
            )
        }

        overwritten shouldBe false
    }
}
