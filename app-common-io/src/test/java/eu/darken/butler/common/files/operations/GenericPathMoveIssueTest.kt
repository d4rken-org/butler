package eu.darken.butler.common.files.operations

import eu.darken.butler.common.files.LocalPath
import eu.darken.butler.common.files.actions.MoveAction
import eu.darken.butler.common.files.actions.PathActionIssue
import eu.darken.butler.common.files.local.LocalPathLookup
import eu.darken.butler.common.files.metadata.FileType
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.should
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlinx.coroutines.flow.last
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.test.runTest
import kotlin.coroutines.cancellation.CancellationException
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import testhelpers.BaseTest
import testhelpers.shouldBePaths
import testhelpers.shouldContainPath
import testhelpers.toPathPairs

/**
 * Tests for GenericPathMove issue resolution - the interactive move paths.
 *
 * Split out of GenericPathMoveTest. Covers everything that drives the
 * operation through an `onIssue` handler:
 * - Conflict resolutions (Skip, Overwrite, Merge, RenameSource) and apply-to-all
 * - Retry after transient and persistent errors
 * - Scan errors and cancellation
 *
 * Uses MockFileSystemOps to test without real file system access.
 */
class GenericPathMoveIssueTest : BaseTest() {

    private lateinit var mockOps: MockFileSystemOps<LocalPath, LocalPathLookup>
    private lateinit var strategy: GenericCrossTypeMoveStrategy<
        LocalPath, LocalPathLookup,
        LocalPath, LocalPathLookup
        >

    @BeforeEach
    fun setup() {
        mockOps = MockFileSystemOps { path, type, size, modifiedAt, permissions, ownership, createdAt ->
            LocalPathLookup(
                lookedUp = path,
                fileType = type,
                size = size,
                modifiedAt = modifiedAt ?: kotlin.time.Instant.fromEpochMilliseconds(0),
                target = null,
                ownership = ownership,
                permissions = permissions,
                createdAt = createdAt,
            )
        }
        strategy = GenericCrossTypeMoveStrategy()
    }

    @AfterEach
    fun cleanup() {
        mockOps.clear()
    }

    // ============ CONFLICT RESOLUTION - APPLY TO ALL ============

    @Test
    fun `move file with apply to all - skip`() = runTest {
        // Given - multiple files with conflicts
        mockOps.addMockFile("/source/file1.txt", "new1".toByteArray())
        mockOps.addMockFile("/source/file2.txt", "new2".toByteArray())
        mockOps.addMockDir("/dest")
        mockOps.addMockFile("/dest/file1.txt", "old1".toByteArray())
        mockOps.addMockFile("/dest/file2.txt", "old2".toByteArray())

        var issueCount = 0

        // When
        val result = setOf(
            LocalPath.build("/source/file1.txt"),
            LocalPath.build("/source/file2.txt")
        ).moveGeneric(
            options = TransferStrategy.Options(attemptAtomicMove = false),
            destination = LocalPath.build("/dest"),
            sourceOps = mockOps,
            destOps = mockOps,
            strategy = strategy,
            onIssue = { issue ->
                issueCount++
                PathActionIssue.PathAlreadyExists.Resolution.Skip(applyToAll = true)
            }
        ).last() as MoveAction.State.Completed<LocalPath, LocalPathLookup, LocalPath, LocalPathLookup>

        // Then - only asked once due to apply-to-all
        issueCount shouldBe 1
        result.skippedFiles.size shouldBe 2

        // Destination files unchanged
        mockOps.getFileContent("/dest/file1.txt") shouldBe "old1".toByteArray()
        mockOps.getFileContent("/dest/file2.txt") shouldBe "old2".toByteArray()

        // Source files still exist
        mockOps.hasFile("/source/file1.txt") shouldBe true
        mockOps.hasFile("/source/file2.txt") shouldBe true
    }

    @Test
    fun `move file with apply to all - overwrite`() = runTest {
        // Given - multiple files with conflicts
        mockOps.addMockFile("/source/file1.txt", "new1".toByteArray())
        mockOps.addMockFile("/source/file2.txt", "new2".toByteArray())
        mockOps.addMockDir("/dest")
        mockOps.addMockFile("/dest/file1.txt", "old1".toByteArray())
        mockOps.addMockFile("/dest/file2.txt", "old2".toByteArray())

        var issueCount = 0

        // When
        val result = setOf(
            LocalPath.build("/source/file1.txt"),
            LocalPath.build("/source/file2.txt")
        ).moveGeneric(
            options = TransferStrategy.Options(attemptAtomicMove = false),
            destination = LocalPath.build("/dest"),
            sourceOps = mockOps,
            destOps = mockOps,
            strategy = strategy,
            onIssue = { issue ->
                issueCount++
                PathActionIssue.PathAlreadyExists.Resolution.Overwrite(applyToAll = true)
            }
        ).last() as MoveAction.State.Completed<LocalPath, LocalPathLookup, LocalPath, LocalPathLookup>

        // Then - only asked once due to apply-to-all
        issueCount shouldBe 1
        result.movedFiles.size shouldBe 2

        // Destination files overwritten
        mockOps.getFileContent("/dest/file1.txt") shouldBe "new1".toByteArray()
        mockOps.getFileContent("/dest/file2.txt") shouldBe "new2".toByteArray()

        // Source files deleted
        mockOps.hasFile("/source/file1.txt") shouldBe false
        mockOps.hasFile("/source/file2.txt") shouldBe false
    }

    @Test
    fun `move file with apply to all - rename source`() = runTest {
        // Given - multiple files with conflicts
        mockOps.addMockFile("/source/file.txt", "new1".toByteArray())
        mockOps.addMockFile("/source/document.txt", "new2".toByteArray())
        mockOps.addMockDir("/dest")
        mockOps.addMockFile("/dest/file.txt", "old1".toByteArray())
        mockOps.addMockFile("/dest/document.txt", "old2".toByteArray())

        var issueCount = 0

        // When
        val result = setOf(
            LocalPath.build("/source/file.txt"),
            LocalPath.build("/source/document.txt")
        ).moveGeneric(
            options = TransferStrategy.Options(attemptAtomicMove = false),
            destination = LocalPath.build("/dest"),
            sourceOps = mockOps,
            destOps = mockOps,
            strategy = strategy,
            onIssue = { issue ->
                issueCount++
                PathActionIssue.PathAlreadyExists.Resolution.RenameSource(
                    newName = when (issue) {
                        is PathActionIssue.PathAlreadyExists -> issue.suggestedName ?: error("No suggested name")
                        else -> error("Unexpected issue")
                    },
                    applyToAll = true
                )
            }
        ).last() as MoveAction.State.Completed<LocalPath, LocalPathLookup, LocalPath, LocalPathLookup>

        // Then - only asked once due to apply-to-all
        issueCount shouldBe 1
        result.movedFiles.size shouldBe 2

        // Original files unchanged
        mockOps.getFileContent("/dest/file.txt") shouldBe "old1".toByteArray()
        mockOps.getFileContent("/dest/document.txt") shouldBe "old2".toByteArray()

        // New files with renamed names
        mockOps.hasFile("/dest/file (1).txt") shouldBe true
        mockOps.getFileContent("/dest/file (1).txt") shouldBe "new1".toByteArray()
        mockOps.hasFile("/dest/document (1).txt") shouldBe true
        mockOps.getFileContent("/dest/document (1).txt") shouldBe "new2".toByteArray()

        // Source files deleted
        mockOps.hasFile("/source/file.txt") shouldBe false
        mockOps.hasFile("/source/document.txt") shouldBe false
    }

    @Test
    fun `move directory with apply to all - merge`() = runTest {
        // Given - multiple directories with conflicts
        mockOps.addMockDir("/source/dir1")
        mockOps.addMockFile("/source/dir1/new1.txt", "content1".toByteArray())
        mockOps.addMockDir("/source/dir2")
        mockOps.addMockFile("/source/dir2/new2.txt", "content2".toByteArray())

        mockOps.addMockDir("/dest")
        mockOps.addMockDir("/dest/dir1")
        mockOps.addMockFile("/dest/dir1/old1.txt", "old content1".toByteArray())
        mockOps.addMockDir("/dest/dir2")
        mockOps.addMockFile("/dest/dir2/old2.txt", "old content2".toByteArray())

        var issueCount = 0

        // When
        setOf(
            LocalPath.build("/source/dir1"),
            LocalPath.build("/source/dir2")
        ).moveGeneric(
            options = TransferStrategy.Options(attemptAtomicMove = false),
            destination = LocalPath.build("/dest"),
            sourceOps = mockOps,
            destOps = mockOps,
            strategy = strategy,
            onIssue = { issue ->
                issueCount++
                PathActionIssue.PathAlreadyExists.Resolution.Merge(applyToAll = true)
            }
        ).last() as MoveAction.State.Completed<LocalPath, LocalPathLookup, LocalPath, LocalPathLookup>

        // Then - only asked once due to apply-to-all
        issueCount shouldBe 1

        // All files exist in merged directories
        mockOps.hasFile("/dest/dir1/new1.txt") shouldBe true
        mockOps.hasFile("/dest/dir1/old1.txt") shouldBe true
        mockOps.hasFile("/dest/dir2/new2.txt") shouldBe true
        mockOps.hasFile("/dest/dir2/old2.txt") shouldBe true

        // Source directories deleted
        mockOps.hasFile("/source/dir1") shouldBe false
        mockOps.hasFile("/source/dir2") shouldBe false
    }

    @Test
    fun `move directory with apply to all - overwrite`() = runTest {
        // Given - multiple directories with conflicts
        mockOps.addMockDir("/source/dir1")
        mockOps.addMockFile("/source/dir1/new1.txt", "content1".toByteArray())
        mockOps.addMockDir("/source/dir2")
        mockOps.addMockFile("/source/dir2/new2.txt", "content2".toByteArray())

        mockOps.addMockDir("/dest")
        mockOps.addMockDir("/dest/dir1")
        mockOps.addMockFile("/dest/dir1/old1.txt", "old content1".toByteArray())
        mockOps.addMockDir("/dest/dir2")
        mockOps.addMockFile("/dest/dir2/old2.txt", "old content2".toByteArray())

        var issueCount = 0

        // When
        setOf(
            LocalPath.build("/source/dir1"),
            LocalPath.build("/source/dir2")
        ).moveGeneric(
            options = TransferStrategy.Options(attemptAtomicMove = false),
            destination = LocalPath.build("/dest"),
            sourceOps = mockOps,
            destOps = mockOps,
            strategy = strategy,
            onIssue = { issue ->
                issueCount++
                PathActionIssue.PathAlreadyExists.Resolution.Overwrite(applyToAll = true)
            }
        ).last() as MoveAction.State.Completed<LocalPath, LocalPathLookup, LocalPath, LocalPathLookup>

        // Then - only asked once due to apply-to-all
        issueCount shouldBe 1

        // Old files deleted, new files exist
        mockOps.hasFile("/dest/dir1/new1.txt") shouldBe true
        mockOps.hasFile("/dest/dir1/old1.txt") shouldBe false
        mockOps.hasFile("/dest/dir2/new2.txt") shouldBe true
        mockOps.hasFile("/dest/dir2/old2.txt") shouldBe false

        // Source directories deleted
        mockOps.hasFile("/source/dir1") shouldBe false
        mockOps.hasFile("/source/dir2") shouldBe false
    }

    @Test
    fun `move directory with apply to all - skip`() = runTest {
        // Given - multiple directories with conflicts
        mockOps.addMockDir("/source/dir1")
        mockOps.addMockFile("/source/dir1/new1.txt", "content1".toByteArray())
        mockOps.addMockDir("/source/dir2")
        mockOps.addMockFile("/source/dir2/new2.txt", "content2".toByteArray())

        mockOps.addMockDir("/dest")
        mockOps.addMockDir("/dest/dir1")
        mockOps.addMockFile("/dest/dir1/old1.txt", "old content1".toByteArray())
        mockOps.addMockDir("/dest/dir2")
        mockOps.addMockFile("/dest/dir2/old2.txt", "old content2".toByteArray())

        var issueCount = 0

        // When
        val result = setOf(
            LocalPath.build("/source/dir1"),
            LocalPath.build("/source/dir2")
        ).moveGeneric(
            options = TransferStrategy.Options(attemptAtomicMove = false),
            destination = LocalPath.build("/dest"),
            sourceOps = mockOps,
            destOps = mockOps,
            strategy = strategy,
            onIssue = { issue ->
                issueCount++
                PathActionIssue.PathAlreadyExists.Resolution.Skip(applyToAll = true)
            }
        ).last() as MoveAction.State.Completed<LocalPath, LocalPathLookup, LocalPath, LocalPathLookup>

        // Then - only asked once due to apply-to-all
        issueCount shouldBe 1

        // Skipped items include directories and their children
        result.skippedFiles.size shouldBe 4 // 2 directories + 2 files

        // Original files unchanged
        mockOps.hasFile("/dest/dir1/old1.txt") shouldBe true
        mockOps.hasFile("/dest/dir1/new1.txt") shouldBe false
        mockOps.hasFile("/dest/dir2/old2.txt") shouldBe true
        mockOps.hasFile("/dest/dir2/new2.txt") shouldBe false

        // Source directories still exist
        mockOps.hasFile("/source/dir1") shouldBe true
        mockOps.hasFile("/source/dir2") shouldBe true
    }

    @Test
    fun `move directory over existing FILE with apply to all overwrite uses recursive false`() = runTest {
        // Tests bug fix: overwrite should use recursive=false when destination is a file
        // Given - directory at source, FILE at destination (not directory)
        mockOps.addMockDir("/source/item")
        mockOps.addMockFile("/source/item/content.txt", "content".toByteArray())
        mockOps.addMockDir("/dest")
        mockOps.addMockFile("/dest/item", "file content".toByteArray())  // FILE, not directory

        val sourcePath = LocalPath.build("/source/item")
        val destPath = LocalPath.build("/dest")

        // When - move with Overwrite (apply to all)
        val result = setOf(sourcePath).moveGeneric(
            options = TransferStrategy.Options(attemptAtomicMove = false),
            destination = destPath,
            sourceOps = mockOps,
            destOps = mockOps,
            strategy = strategy,
            onIssue = { issue ->
                when (issue) {
                    is PathActionIssue.PathAlreadyExists -> {
                        PathActionIssue.PathAlreadyExists.Resolution.Overwrite(applyToAll = true)
                    }
                    else -> throw AssertionError("Unexpected issue: $issue")
                }
            }
        ).last() as MoveAction.State.Completed<LocalPath, LocalPathLookup, LocalPath, LocalPathLookup>

        // Then - file deleted and replaced with directory
        mockOps.hasFile("/dest/item") shouldBe true
        mockOps.getFileType("/dest/item") shouldBe FileType.DIRECTORY
        mockOps.hasFile("/dest/item/content.txt") shouldBe true
        mockOps.getFileContent("/dest/item/content.txt") shouldBe "content".toByteArray()

        // Source cleaned up
        mockOps.hasFile("/source/item") shouldBe false

        result.movedFiles.size shouldBe 2 // directory + file
    }

    @Test
    fun `merge resolution adds directory to moved set`() = runTest {
        // Tests bug fix: merged directories should appear in result set
        // Given - source and destination directories with different files
        mockOps.addMockDir("/source/project")
        mockOps.addMockFile("/source/project/new.txt", "new".toByteArray())
        mockOps.addMockDir("/dest")
        mockOps.addMockDir("/dest/project")
        mockOps.addMockFile("/dest/project/old.txt", "old".toByteArray())

        val sourcePath = LocalPath.build("/source/project")
        val destPath = LocalPath.build("/dest")

        // When - move with Merge resolution
        val result = setOf(sourcePath).moveGeneric(
            options = TransferStrategy.Options(attemptAtomicMove = false),
            destination = destPath,
            sourceOps = mockOps,
            destOps = mockOps,
            strategy = strategy,
            onIssue = { issue ->
                when (issue) {
                    is PathActionIssue.PathAlreadyExists -> {
                        PathActionIssue.PathAlreadyExists.Resolution.Merge()
                    }
                    else -> throw AssertionError("Unexpected issue: $issue")
                }
            }
        ).last() as MoveAction.State.Completed<LocalPath, LocalPathLookup, LocalPath, LocalPathLookup>

        // Then - both files exist in merged directory
        mockOps.hasFile("/dest/project") shouldBe true
        mockOps.hasFile("/dest/project/old.txt") shouldBe true
        mockOps.hasFile("/dest/project/new.txt") shouldBe true

        // Source cleaned up
        mockOps.hasFile("/source/project") shouldBe false

        result.movedFiles.size shouldBe 2 // directory + new.txt
        // Bug fix verification: merged directory explicitly in result
        result.movedFiles shouldContainPath (LocalPath.build("/source/project") to LocalPath.build("/dest/project"))
    }

    @Test
    fun `nested directory rename source updates all child paths`() = runTest {
        // Given - nested structure with conflict
        mockOps.addMockDir("/source/Parent")
        mockOps.addMockDir("/source/Parent/SubDir")
        mockOps.addMockFile("/source/Parent/SubDir/file.txt", "content".toByteArray())

        mockOps.addMockDir("/dest")
        mockOps.addMockDir("/dest/Parent")
        mockOps.addMockFile("/dest/Parent/existing.txt", "existing".toByteArray())

        // When - rename source to Parent-new
        val result = setOf(LocalPath.build("/source/Parent")).moveGeneric(
            options = TransferStrategy.Options(attemptAtomicMove = false),
            destination = LocalPath.build("/dest"),
            sourceOps = mockOps,
            destOps = mockOps,
            strategy = strategy,
            onIssue = { issue ->
                PathActionIssue.PathAlreadyExists.Resolution.RenameSource("Parent-new")
            }
        ).last() as MoveAction.State.Completed<LocalPath, LocalPathLookup, LocalPath, LocalPathLookup>

        // Then - all paths updated to use Parent-new
        mockOps.hasFile("/dest/Parent-new") shouldBe true
        mockOps.hasFile("/dest/Parent-new/SubDir") shouldBe true
        mockOps.hasFile("/dest/Parent-new/SubDir/file.txt") shouldBe true
        mockOps.getFileContent("/dest/Parent-new/SubDir/file.txt") shouldBe "content".toByteArray()

        // Original unchanged
        mockOps.hasFile("/dest/Parent/existing.txt") shouldBe true

        // Source deleted
        mockOps.hasFile("/source/Parent") shouldBe false

        result.movedFiles.size shouldBe 3 // Parent-new + SubDir + file.txt
    }

    // ============ RETRY TESTS ============

    @Test
    fun `move file with transient error retries and succeeds`() = runTest {
        // Given - file with injected failure on first write attempt
        mockOps.addMockFile("/source/file.txt", "content".toByteArray())
        mockOps.addMockDir("/dest")

        // Fail once on openOutputStream, then succeed
        mockOps.setFailOpenOutputStream(1)

        // When - move with retry on UnknownError
        val result = setOf(LocalPath.build("/source/file.txt")).moveGeneric(
            options = TransferStrategy.Options(attemptAtomicMove = false),
            destination = LocalPath.build("/dest"),
            sourceOps = mockOps,
            destOps = mockOps,
            strategy = strategy,
            onIssue = { issue ->
                when (issue) {
                    is PathActionIssue.UnknownError -> PathActionIssue.UnknownError.Resolution.Retry
                    is PathActionIssue.PathAlreadyExists -> {
                        // Failed move left partial file - overwrite
                        PathActionIssue.PathAlreadyExists.Resolution.Overwrite()
                    }
                    else -> throw AssertionError("Unexpected issue: $issue")
                }
            }
        ).last() as MoveAction.State.Completed<LocalPath, LocalPathLookup, LocalPath, LocalPathLookup>

        // Then - file successfully moved after retry
        mockOps.hasFile("/dest/file.txt") shouldBe true
        mockOps.getFileContent("/dest/file.txt") shouldBe "content".toByteArray()
        mockOps.hasFile("/source/file.txt") shouldBe false

        result.movedFiles.size shouldBe 1
    }

    @Test
    fun `move file with persistent error retries multiple times then skips`() = runTest {
        // Given - file with persistent failure (fails 4 times)
        mockOps.addMockFile("/source/file.txt", "content".toByteArray())
        mockOps.addMockDir("/dest")

        // Fail 4 times on openOutputStream
        mockOps.setFailOpenOutputStream(4)

        var retryCount = 0

        // When - retry 3 times, then skip
        val result = setOf(LocalPath.build("/source/file.txt")).moveGeneric(
            options = TransferStrategy.Options(attemptAtomicMove = false),
            destination = LocalPath.build("/dest"),
            sourceOps = mockOps,
            destOps = mockOps,
            strategy = strategy,
            onIssue = { issue ->
                when (issue) {
                    is PathActionIssue.UnknownError -> {
                        retryCount++
                        if (retryCount < 4) {
                            PathActionIssue.UnknownError.Resolution.Retry
                        } else {
                            PathActionIssue.UnknownError.Resolution.Skip()
                        }
                    }
                    is PathActionIssue.PathAlreadyExists -> {
                        // Failed move may leave partial file - overwrite it on retry
                        retryCount++
                        if (retryCount < 4) {
                            PathActionIssue.PathAlreadyExists.Resolution.Overwrite()
                        } else {
                            PathActionIssue.PathAlreadyExists.Resolution.Skip()
                        }
                    }
                    else -> throw AssertionError("Unexpected issue: $issue")
                }
            }
        ).last() as MoveAction.State.Completed<LocalPath, LocalPathLookup, LocalPath, LocalPathLookup>

        // Then - file skipped after retries
        // Note: partial file may or may not exist depending on when failures occurred
        mockOps.hasFile("/source/file.txt") shouldBe true  // Source still exists

        retryCount shouldBe 4
        result.skippedFiles.size shouldBe 1
        result.movedFiles.size shouldBe 0
    }

    @Test
    fun `move file retry does not regress progress tracking`() = runTest {
        // Given - file with injected failure
        mockOps.addMockFile("/source/file.txt", "content".toByteArray())
        mockOps.addMockDir("/dest")

        mockOps.setFailOpenOutputStream(1)

        val progressUpdates =
            mutableListOf<MoveAction.State.Active<LocalPath, LocalPathLookup, LocalPath, LocalPathLookup>>()

        // When - move with progress tracking
        setOf(LocalPath.build("/source/file.txt")).moveGeneric(
            options = TransferStrategy.Options(attemptAtomicMove = false),
            destination = LocalPath.build("/dest"),
            sourceOps = mockOps,
            destOps = mockOps,
            strategy = strategy,
            onIssue = { issue ->
                when (issue) {
                    is PathActionIssue.UnknownError -> PathActionIssue.UnknownError.Resolution.Retry
                    is PathActionIssue.PathAlreadyExists -> {
                        // Failed move may leave partial file - overwrite it on retry
                        PathActionIssue.PathAlreadyExists.Resolution.Overwrite()
                    }
                    else -> throw AssertionError("Unexpected issue: $issue")
                }
            }
        ).onEach { state ->
            if (state is MoveAction.State.Active) progressUpdates.add(state)
        }.last()

        // Then - progress should never decrease (no regression)
        var previousProgress = 0L
        for (progress in progressUpdates) {
            progress.movedBytes should { it >= previousProgress }
            previousProgress = progress.movedBytes
        }

        // File successfully moved
        mockOps.hasFile("/dest/file.txt") shouldBe true
    }

    // ============ SCAN ERROR HANDLING ============

    @Test
    fun `missing top-level move source throws even with issue handler`() = runTest {
        mockOps.addMockDir("/dest")

        shouldThrow<java.nio.file.NoSuchFileException> {
            setOf(LocalPath.build("/missing.txt")).moveGeneric(
                options = TransferStrategy.Options(attemptAtomicMove = false),
                destination = LocalPath.build("/dest"),
                sourceOps = mockOps,
                destOps = mockOps,
                strategy = strategy,
                onIssue = { PathActionIssue.UnknownError.Resolution.Skip() }
            ).last()
        }
    }

    @Test
    fun `move scan cancellation propagates`() = runTest {
        mockOps.addMockDir("/source/parent")
        mockOps.addMockFile("/source/parent/child.txt", "content".toByteArray())
        mockOps.addMockDir("/dest")
        mockOps.setFailListFiles(1) { CancellationException("cancel move scan") }

        shouldThrow<CancellationException> {
            setOf(LocalPath.build("/source/parent")).moveGeneric(
                options = TransferStrategy.Options(attemptAtomicMove = false),
                destination = LocalPath.build("/dest"),
                sourceOps = mockOps,
                destOps = mockOps,
                strategy = strategy,
                onIssue = { PathActionIssue.UnknownError.Resolution.Skip() }
            ).last()
        }
    }

    @Test
    fun `directory scan error during move then skip should appear only in skipped`() = runTest {
        // Given - directory with children that will fail during listFiles
        mockOps.addMockDir("/source/parent")
        mockOps.addMockFile("/source/parent/child.txt", "content".toByteArray())
        mockOps.addMockDir("/dest")

        // Inject listFiles failure (simulates permission denied during scan)
        mockOps.setFailListFiles(1, { SecurityException("Permission denied") })

        val sourcePath = LocalPath.build("/source/parent")
        val destPath = LocalPath.build("/dest")

        var issueReceived = false

        // When
        val result = setOf(sourcePath).moveGeneric(
            options = TransferStrategy.Options(attemptAtomicMove = false),
            destination = destPath,
            sourceOps = mockOps,
            destOps = mockOps,
            strategy = strategy,
            onIssue = { issue ->
                issueReceived = true
                when (issue) {
                    is PathActionIssue.InsufficientPermission -> PathActionIssue.InsufficientPermission.Resolution.Skip()
                    is PathActionIssue.UnknownError -> PathActionIssue.UnknownError.Resolution.Skip()
                    else -> TODO("Unexpected issue type: $issue")
                }
            }
        ).last() as MoveAction.State.Completed<LocalPath, LocalPathLookup, LocalPath, LocalPathLookup>

        // Then - directory should be ONLY in skipped, NOT in moved
        result.movedFiles.toPathPairs().map { it.first } shouldNotBe setOf(LocalPath.build("/source/parent"))
        result.skippedFiles shouldBePaths setOf(LocalPath.build("/source/parent"))
        issueReceived shouldBe true

        // Destination should not have the directory or its children
        mockOps.hasFile("/dest/parent") shouldBe false
        mockOps.hasFile("/dest/parent/child.txt") shouldBe false

        // Source should still exist (move was skipped)
        mockOps.hasFile("/source/parent") shouldBe true
        mockOps.hasFile("/source/parent/child.txt") shouldBe true
    }

    @Test
    fun `directory scan error during move with retry should succeed on second attempt`() = runTest {
        // Given - directory with children
        mockOps.addMockDir("/source/parent")
        mockOps.addMockFile("/source/parent/child.txt", "content".toByteArray())
        mockOps.addMockDir("/dest")

        // Inject listFiles failure for first attempt only
        mockOps.setFailListFiles(1, { SecurityException("Permission denied") })

        val sourcePath = LocalPath.build("/source/parent")
        val destPath = LocalPath.build("/dest")

        var retryInvoked = false

        // When
        val result = setOf(sourcePath).moveGeneric(
            options = TransferStrategy.Options(attemptAtomicMove = false),
            destination = destPath,
            sourceOps = mockOps,
            destOps = mockOps,
            strategy = strategy,
            onIssue = { issue ->
                when (issue) {
                    is PathActionIssue.UnknownError -> {
                        if (!retryInvoked) {
                            retryInvoked = true
                            PathActionIssue.UnknownError.Resolution.Retry
                        } else {
                            PathActionIssue.UnknownError.Resolution.Skip()
                        }
                    }
                    is PathActionIssue.InsufficientPermission -> PathActionIssue.InsufficientPermission.Resolution.Skip()
                    else -> TODO("Unexpected issue type: $issue")
                }
            }
        ).last() as MoveAction.State.Completed<LocalPath, LocalPathLookup, LocalPath, LocalPathLookup>

        // Then - directory and children successfully moved after retry
        retryInvoked shouldBe true
        mockOps.hasFile("/dest/parent") shouldBe true
        mockOps.hasFile("/dest/parent/child.txt") shouldBe true
        mockOps.getFileContent("/dest/parent/child.txt") shouldBe "content".toByteArray()

        // Source should be deleted (successful move)
        mockOps.hasFile("/source/parent") shouldBe false
        mockOps.hasFile("/source/parent/child.txt") shouldBe false

        result.movedFiles.size shouldBe 2 // parent + child.txt
        result.skippedFiles.size shouldBe 0
    }
}
