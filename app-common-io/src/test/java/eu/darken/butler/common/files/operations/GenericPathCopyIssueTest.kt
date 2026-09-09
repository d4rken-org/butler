package eu.darken.butler.common.files.operations

import eu.darken.butler.common.files.LocalPath
import eu.darken.butler.common.files.LookupOptions
import eu.darken.butler.common.files.actions.CopyAction
import eu.darken.butler.common.files.actions.PathActionIssue
import eu.darken.butler.common.files.local.LocalPathLookup
import eu.darken.butler.common.files.local.operations.core.PerformanceHistory
import eu.darken.butler.common.files.local.routing.AccessIntent
import eu.darken.butler.common.files.local.routing.AccessMode
import eu.darken.butler.common.files.local.routing.IntentAwareFileSystemOps
import eu.darken.butler.common.files.metadata.FileType
import eu.darken.butler.common.progress.Progress
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlinx.coroutines.flow.last
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.test.runTest
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration.Companion.seconds
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import testhelpers.BaseTest
import testhelpers.TestClock
import testhelpers.firstPath
import testhelpers.shouldBePaths
import testhelpers.shouldContainPath
import testhelpers.toPathPairs

/**
 * Tests for GenericPathCopy issue resolution - the interactive copy paths.
 *
 * Split out of GenericPathCopyTest. Covers everything that drives the
 * operation through an `onIssue` handler:
 * - Conflict resolutions (Skip, Overwrite, Merge, RenameSource) and apply-to-all
 * - Retry after transient and persistent errors
 * - Scan errors and cancellation
 *
 * Uses MockFileSystemOps to test without real file system access.
 */
class GenericPathCopyIssueTest : BaseTest() {

    private lateinit var mockOps: MockFileSystemOps<LocalPath, LocalPathLookup>
    private lateinit var strategy: GenericCrossTypeCopyStrategy<
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
        strategy = GenericCrossTypeCopyStrategy()
    }

    @AfterEach
    fun cleanup() {
        mockOps.clear()
    }

    // ============ CONFLICT RESOLUTION ============

    @Test
    fun `copy file with RenameSource creates new file with renamed name`() = runTest {
        // Given - source file and conflicting destination
        mockOps.addMockFile("/source/file.txt", "new content".toByteArray())
        mockOps.addMockDir("/dest")
        mockOps.addMockFile("/dest/file.txt", "old content".toByteArray())

        val sourcePath = LocalPath.build("/source/file.txt")
        val destPath = LocalPath.build("/dest")

        // When - copy with RenameSource resolution
        val result = setOf(sourcePath).copyGeneric(
            destination = destPath,
            sourceOps = mockOps,
            destOps = mockOps,
            strategy = strategy,
            onIssue = { issue ->
                when (issue) {
                    is PathActionIssue.PathAlreadyExists -> {
                        PathActionIssue.PathAlreadyExists.Resolution.RenameSource("file (1).txt")
                    }
                    else -> throw AssertionError("Unexpected issue: $issue")
                }
            }
        ).last() as CopyAction.State.Completed<LocalPath, LocalPathLookup, LocalPath, LocalPathLookup>

        // Then - old file unchanged, new file created with renamed name
        mockOps.hasFile("/dest/file.txt") shouldBe true
        mockOps.getFileContent("/dest/file.txt") shouldBe "old content".toByteArray()
        mockOps.hasFile("/dest/file (1).txt") shouldBe true
        mockOps.getFileContent("/dest/file (1).txt") shouldBe "new content".toByteArray()

        result.copied.size shouldBe 1
        result.copied.firstPath() shouldBe (LocalPath.build("/source/file.txt") to LocalPath.build("/dest/file (1).txt"))
    }

    @Test
    fun `copy directory with RenameSource creates new directory with renamed name`() = runTest {
        // Given - source directory and conflicting destination directory
        mockOps.addMockDir("/source/folder")
        mockOps.addMockFile("/source/folder/new.txt", "new".toByteArray())
        mockOps.addMockDir("/dest")
        mockOps.addMockDir("/dest/folder")
        mockOps.addMockFile("/dest/folder/old.txt", "old".toByteArray())

        val sourcePath = LocalPath.build("/source/folder")
        val destPath = LocalPath.build("/dest")

        // When - copy with RenameSource resolution
        val result = setOf(sourcePath).copyGeneric(
            destination = destPath,
            sourceOps = mockOps,
            destOps = mockOps,
            strategy = strategy,
            onIssue = { issue ->
                when (issue) {
                    is PathActionIssue.PathAlreadyExists -> {
                        PathActionIssue.PathAlreadyExists.Resolution.RenameSource("folder (1)")
                    }
                    else -> throw AssertionError("Unexpected issue: $issue")
                }
            }
        ).last() as CopyAction.State.Completed<LocalPath, LocalPathLookup, LocalPath, LocalPathLookup>

        // Then - old directory unchanged, new directory created with renamed name
        mockOps.hasFile("/dest/folder/old.txt") shouldBe true
        mockOps.hasFile("/dest/folder/new.txt") shouldBe false
        mockOps.hasFile("/dest/folder (1)") shouldBe true
        mockOps.hasFile("/dest/folder (1)/new.txt") shouldBe true
        mockOps.getFileContent("/dest/folder (1)/new.txt") shouldBe "new".toByteArray()

        result.copied.size shouldBe 2 // folder + file
    }

    @Test
    fun `copy with Skip leaves existing file unchanged`() = runTest {
        // Given - source file and conflicting destination
        mockOps.addMockFile("/source/file.txt", "new content".toByteArray())
        mockOps.addMockDir("/dest")
        mockOps.addMockFile("/dest/file.txt", "old content".toByteArray())

        val sourcePath = LocalPath.build("/source/file.txt")
        val destPath = LocalPath.build("/dest")

        // When - copy with Skip resolution
        val result = setOf(sourcePath).copyGeneric(
            destination = destPath,
            sourceOps = mockOps,
            destOps = mockOps,
            strategy = strategy,
            onIssue = { issue ->
                when (issue) {
                    is PathActionIssue.PathAlreadyExists -> {
                        PathActionIssue.PathAlreadyExists.Resolution.Skip()
                    }
                    else -> throw AssertionError("Unexpected issue: $issue")
                }
            }
        ).last() as CopyAction.State.Completed<LocalPath, LocalPathLookup, LocalPath, LocalPathLookup>

        // Then - destination file unchanged
        mockOps.hasFile("/dest/file.txt") shouldBe true
        mockOps.getFileContent("/dest/file.txt") shouldBe "old content".toByteArray()

        result.copied.size shouldBe 0
        result.skipped.size shouldBe 1
        result.skipped shouldBePaths setOf(LocalPath.build("/source/file.txt"))
    }

    @Test
    fun `copy with Overwrite replaces existing file`() = runTest {
        // Given - source file and conflicting destination
        mockOps.addMockFile("/source/file.txt", "new content".toByteArray())
        mockOps.addMockDir("/dest")
        mockOps.addMockFile("/dest/file.txt", "old content".toByteArray())

        val sourcePath = LocalPath.build("/source/file.txt")
        val destPath = LocalPath.build("/dest")

        // When - copy with Overwrite resolution
        val result = setOf(sourcePath).copyGeneric(
            destination = destPath,
            sourceOps = mockOps,
            destOps = mockOps,
            strategy = strategy,
            onIssue = { issue ->
                when (issue) {
                    is PathActionIssue.PathAlreadyExists -> {
                        PathActionIssue.PathAlreadyExists.Resolution.Overwrite()
                    }
                    else -> throw AssertionError("Unexpected issue: $issue")
                }
            }
        ).last() as CopyAction.State.Completed<LocalPath, LocalPathLookup, LocalPath, LocalPathLookup>

        // Then - destination file replaced with new content
        mockOps.hasFile("/dest/file.txt") shouldBe true
        mockOps.getFileContent("/dest/file.txt") shouldBe "new content".toByteArray()

        result.copied.size shouldBe 1
        result.copied.firstPath() shouldBe (LocalPath.build("/source/file.txt") to LocalPath.build("/dest/file.txt"))
    }

    @Test
    fun `copy directory with Merge combines both directories`() = runTest {
        // Given - source and destination directories with different files
        mockOps.addMockDir("/source/folder")
        mockOps.addMockFile("/source/folder/new.txt", "new".toByteArray())
        mockOps.addMockDir("/dest")
        mockOps.addMockDir("/dest/folder")
        mockOps.addMockFile("/dest/folder/old.txt", "old".toByteArray())

        val sourcePath = LocalPath.build("/source/folder")
        val destPath = LocalPath.build("/dest")

        // When - copy with Merge resolution
        val result = setOf(sourcePath).copyGeneric(
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
        ).last() as CopyAction.State.Completed<LocalPath, LocalPathLookup, LocalPath, LocalPathLookup>

        // Then - both files exist in merged directory
        mockOps.hasFile("/dest/folder") shouldBe true
        mockOps.hasFile("/dest/folder/old.txt") shouldBe true
        mockOps.hasFile("/dest/folder/new.txt") shouldBe true
        mockOps.getFileContent("/dest/folder/old.txt") shouldBe "old".toByteArray()
        mockOps.getFileContent("/dest/folder/new.txt") shouldBe "new".toByteArray()

        result.copied.size shouldBe 2 // folder + new.txt file
    }

    @Test
    fun `copy directory over existing FILE with apply to all overwrite uses recursive false`() = runTest {
        // Tests bug fix: overwrite should use recursive=false when destination is a file
        // Given - directory at source, FILE at destination (not directory)
        mockOps.addMockDir("/source/item")
        mockOps.addMockFile("/source/item/content.txt", "content".toByteArray())
        mockOps.addMockDir("/dest")
        mockOps.addMockFile("/dest/item", "file content".toByteArray())  // FILE, not directory

        val sourcePath = LocalPath.build("/source/item")
        val destPath = LocalPath.build("/dest")

        // When - copy with Overwrite (apply to all)
        val result = setOf(sourcePath).copyGeneric(
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
        ).last() as CopyAction.State.Completed<LocalPath, LocalPathLookup, LocalPath, LocalPathLookup>

        // Then - file deleted and replaced with directory
        mockOps.hasFile("/dest/item") shouldBe true
        mockOps.getFileType("/dest/item") shouldBe FileType.DIRECTORY
        mockOps.hasFile("/dest/item/content.txt") shouldBe true
        mockOps.getFileContent("/dest/item/content.txt") shouldBe "content".toByteArray()

        result.copied.size shouldBe 2 // directory + file
    }

    @Test
    fun `merge resolution adds directory to copied set`() = runTest {
        // Tests bug fix: merged directories should appear in result set
        // Given - source and destination directories with different files
        mockOps.addMockDir("/source/project")
        mockOps.addMockFile("/source/project/new.txt", "new".toByteArray())
        mockOps.addMockDir("/dest")
        mockOps.addMockDir("/dest/project")
        mockOps.addMockFile("/dest/project/old.txt", "old".toByteArray())

        val sourcePath = LocalPath.build("/source/project")
        val destPath = LocalPath.build("/dest")

        // When - copy with Merge resolution
        val result = setOf(sourcePath).copyGeneric(
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
        ).last() as CopyAction.State.Completed<LocalPath, LocalPathLookup, LocalPath, LocalPathLookup>

        // Then - both files exist in merged directory
        mockOps.hasFile("/dest/project") shouldBe true
        mockOps.hasFile("/dest/project/old.txt") shouldBe true
        mockOps.hasFile("/dest/project/new.txt") shouldBe true

        result.copied.size shouldBe 2 // directory + new.txt
        // Bug fix verification: merged directory explicitly in result
        result.copied shouldContainPath (LocalPath.build("/source/project") to LocalPath.build("/dest/project"))
    }

    @Test
    fun `nested directory RenameSource updates all child paths`() = runTest {
        // Given - nested source structure and conflicting destination
        mockOps.addMockDir("/source/Parent")
        mockOps.addMockDir("/source/Parent/SubDir")
        mockOps.addMockFile("/source/Parent/SubDir/file.txt", "content".toByteArray())
        mockOps.addMockDir("/dest")
        mockOps.addMockDir("/dest/Parent")
        mockOps.addMockFile("/dest/Parent/existing.txt", "existing".toByteArray())

        val sourcePath = LocalPath.build("/source/Parent")
        val destPath = LocalPath.build("/dest")

        // When - copy with RenameSource
        val result = setOf(sourcePath).copyGeneric(
            destination = destPath,
            sourceOps = mockOps,
            destOps = mockOps,
            strategy = strategy,
            onIssue = { issue ->
                when (issue) {
                    is PathActionIssue.PathAlreadyExists -> {
                        PathActionIssue.PathAlreadyExists.Resolution.RenameSource("Parent-new")
                    }
                    else -> throw AssertionError("Unexpected issue: $issue")
                }
            }
        ).last() as CopyAction.State.Completed<LocalPath, LocalPathLookup, LocalPath, LocalPathLookup>

        // Then - all children copied to renamed parent
        mockOps.hasFile("/dest/Parent/existing.txt") shouldBe true
        mockOps.hasFile("/dest/Parent-new") shouldBe true
        mockOps.hasFile("/dest/Parent-new/SubDir") shouldBe true
        mockOps.hasFile("/dest/Parent-new/SubDir/file.txt") shouldBe true
        mockOps.getFileContent("/dest/Parent-new/SubDir/file.txt") shouldBe "content".toByteArray()

        result.copied.size shouldBe 3 // Parent-new + SubDir + file.txt
    }

    // ============ RETRY FUNCTIONALITY ============

    @Test
    fun `copy file with transient error retries and succeeds`() = runTest {
        // Given - source file and destination
        mockOps.addMockFile("/source/file.txt", "content".toByteArray())
        mockOps.addMockDir("/dest")

        // Configure mock to fail once for output stream
        mockOps.setFailOpenOutputStream(1)

        val sourcePath = LocalPath.build("/source/file.txt")
        val destPath = LocalPath.build("/dest")

        var issueCount = 0

        // When - copy with retry on first error
        val result = setOf(sourcePath).copyGeneric(
            destination = destPath,
            sourceOps = mockOps,
            destOps = mockOps,
            strategy = strategy,
            onIssue = { issue ->
                issueCount++
                when (issue) {
                    is PathActionIssue.UnknownError -> {
                        // First failure - retry
                        PathActionIssue.UnknownError.Resolution.Retry
                    }
                    is PathActionIssue.PathAlreadyExists -> {
                        // Failed copy left partial file - overwrite and continue
                        PathActionIssue.PathAlreadyExists.Resolution.Overwrite()
                    }
                    else -> throw AssertionError("Unexpected issue: $issue")
                }
            }
        ).last() as CopyAction.State.Completed<LocalPath, LocalPathLookup, LocalPath, LocalPathLookup>

        // Then - file copied successfully after retry
        mockOps.hasFile("/dest/file.txt") shouldBe true
        mockOps.getFileContent("/dest/file.txt") shouldBe "content".toByteArray()
        (issueCount >= 1) shouldBe true  // At least one issue encountered
        result.copied.size shouldBe 1
        result.skipped.size shouldBe 0
    }

    @Test
    fun `copy file with persistent error retries multiple times then skips`() = runTest {
        // Given - source file that always fails
        mockOps.addMockFile("/source/file.txt", "content".toByteArray())
        mockOps.addMockDir("/dest")

        // Configure mock to fail 4 times
        mockOps.setFailOpenOutputStream(4)

        val sourcePath = LocalPath.build("/source/file.txt")
        val destPath = LocalPath.build("/dest")

        var issueCount = 0
        val maxRetries = 3

        // When - copy with multiple retries then skip
        val result = setOf(sourcePath).copyGeneric(
            destination = destPath,
            sourceOps = mockOps,
            destOps = mockOps,
            strategy = strategy,
            onIssue = { issue ->
                issueCount++
                when (issue) {
                    is PathActionIssue.UnknownError -> {
                        if (issueCount <= maxRetries) {
                            PathActionIssue.UnknownError.Resolution.Retry
                        } else {
                            PathActionIssue.UnknownError.Resolution.Skip()
                        }
                    }
                    is PathActionIssue.PathAlreadyExists -> {
                        // Failed copy left partial file - handle it
                        if (issueCount <= maxRetries) {
                            PathActionIssue.PathAlreadyExists.Resolution.Overwrite()
                        } else {
                            PathActionIssue.PathAlreadyExists.Resolution.Skip()
                        }
                    }
                    else -> throw AssertionError("Unexpected issue: $issue")
                }
            }
        ).last() as CopyAction.State.Completed<LocalPath, LocalPathLookup, LocalPath, LocalPathLookup>

        // Then - file skipped after max retries
        // Note: partial file may or may not exist depending on when failures occurred
        (issueCount >= maxRetries) shouldBe true
        result.copied.size shouldBe 0
        result.skipped.size shouldBe 1
        result.skipped shouldBePaths setOf(LocalPath.build("/source/file.txt"))
    }

    @Test
    fun `copy file retry does not regress progress tracking`() = runTest {
        // Given - source file
        mockOps.addMockFile("/source/file.txt", "content".toByteArray())
        mockOps.addMockDir("/dest")

        // Configure mock to fail once
        mockOps.setFailOpenOutputStream(1)

        val sourcePath = LocalPath.build("/source/file.txt")
        val destPath = LocalPath.build("/dest")

        val progressUpdates =
            mutableListOf<CopyAction.State.Active<LocalPath, LocalPathLookup, LocalPath, LocalPathLookup>>()

        // When - copy with progress tracking
        setOf(sourcePath).copyGeneric(
            destination = destPath,
            sourceOps = mockOps,
            destOps = mockOps,
            strategy = strategy,
            onIssue = { issue ->
                when (issue) {
                    is PathActionIssue.UnknownError -> PathActionIssue.UnknownError.Resolution.Retry
                    is PathActionIssue.PathAlreadyExists -> {
                        // Failed copy left partial file - overwrite
                        PathActionIssue.PathAlreadyExists.Resolution.Overwrite()
                    }
                    else -> throw AssertionError("Unexpected issue: $issue")
                }
            }
        ).onEach { state ->
            if (state is CopyAction.State.Active) progressUpdates.add(state)
        }.last()

        // Then - progress never goes backwards
        if (progressUpdates.size > 1) {
            progressUpdates.zipWithNext().forEach { (prev, next) ->
                (next.copiedBytes >= prev.copiedBytes) shouldBe true
            }
        }
    }

    @Test
    fun `copy directory with child file retry succeeds after transient error`() = runTest {
        // Given - directory with file
        mockOps.addMockDir("/source/folder")
        mockOps.addMockFile("/source/folder/file.txt", "content".toByteArray())
        mockOps.addMockDir("/dest")

        // Configure mock to fail once for output stream
        mockOps.setFailOpenOutputStream(1)

        val sourcePath = LocalPath.build("/source/folder")
        val destPath = LocalPath.build("/dest")

        // When - copy with retry
        val result = setOf(sourcePath).copyGeneric(
            destination = destPath,
            sourceOps = mockOps,
            destOps = mockOps,
            strategy = strategy,
            onIssue = { issue ->
                when (issue) {
                    is PathActionIssue.UnknownError -> PathActionIssue.UnknownError.Resolution.Retry
                    is PathActionIssue.PathAlreadyExists -> {
                        // Failed copy left partial file - overwrite
                        PathActionIssue.PathAlreadyExists.Resolution.Overwrite()
                    }
                    else -> throw AssertionError("Unexpected issue: $issue")
                }
            }
        ).last() as CopyAction.State.Completed<LocalPath, LocalPathLookup, LocalPath, LocalPathLookup>

        // Then - both directory and file copied
        mockOps.hasFile("/dest/folder") shouldBe true
        mockOps.hasFile("/dest/folder/file.txt") shouldBe true
        result.copied.size shouldBe 2 // folder + file
    }

    // ============ SCAN ERROR HANDLING ============

    @Test
    fun `missing top-level copy source throws even with issue handler`() = runTest {
        mockOps.addMockDir("/dest")

        shouldThrow<java.nio.file.NoSuchFileException> {
            setOf(LocalPath.build("/missing.txt")).copyGeneric(
                destination = LocalPath.build("/dest"),
                sourceOps = mockOps,
                destOps = mockOps,
                strategy = strategy,
                onIssue = { PathActionIssue.UnknownError.Resolution.Skip() }
            ).last()
        }
    }

    @Test
    fun `copy scan cancellation propagates`() = runTest {
        mockOps.addMockDir("/source/parent")
        mockOps.addMockFile("/source/parent/child.txt", "content".toByteArray())
        mockOps.addMockDir("/dest")
        mockOps.setFailListFiles(1) { CancellationException("cancel copy scan") }

        shouldThrow<CancellationException> {
            setOf(LocalPath.build("/source/parent")).copyGeneric(
                destination = LocalPath.build("/dest"),
                sourceOps = mockOps,
                destOps = mockOps,
                strategy = strategy,
                onIssue = { PathActionIssue.UnknownError.Resolution.Skip() }
            ).last()
        }
    }

    @Test
    fun `directory scan error during copy then skip should appear only in skipped`() = runTest {
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
        val result = setOf(sourcePath).copyGeneric(
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
        ).last() as CopyAction.State.Completed<LocalPath, LocalPathLookup, LocalPath, LocalPathLookup>

        // Then - directory should be ONLY in skipped, NOT in copied
        result.copied.toPathPairs().map { it.first } shouldNotBe setOf(LocalPath.build("/source/parent"))
        result.skipped shouldBePaths setOf(LocalPath.build("/source/parent"))
        issueReceived shouldBe true

        // Destination should not have the directory or its children
        mockOps.hasFile("/dest/parent") shouldBe false
        mockOps.hasFile("/dest/parent/child.txt") shouldBe false
    }

    @Test
    fun `directory scan error during copy with retry should succeed on second attempt`() = runTest {
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
        val result = setOf(sourcePath).copyGeneric(
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
        ).last() as CopyAction.State.Completed<LocalPath, LocalPathLookup, LocalPath, LocalPathLookup>

        // Then - directory and children successfully copied after retry
        retryInvoked shouldBe true
        mockOps.hasFile("/dest/parent") shouldBe true
        mockOps.hasFile("/dest/parent/child.txt") shouldBe true
        mockOps.getFileContent("/dest/parent/child.txt") shouldBe "content".toByteArray()
        result.copied.size shouldBe 2 // parent + child.txt
        result.skipped.size shouldBe 0
    }

    // ============ PROGRESS ACCOUNTING ============

    /** Every progress throttle window has elapsed, so each report site actually emits. */
    private fun tickingClock() = TestClock(autoAdvance = 1.seconds)

    private fun List<CopyAction.State.Active<LocalPath, LocalPathLookup, LocalPath, LocalPathLookup>>.counters() =
        mapNotNull { it.primaryProgress.count as? Progress.Count.Counter }

    @Test
    fun `a skipped scan error accounts the directory it already counted`() = runTest {
        // Given - a directory whose listing fails, plus a sibling file that copies normally
        mockOps.addMockDir("/source/parent")
        mockOps.addMockFile("/source/parent/child.txt", "content".toByteArray())
        mockOps.addMockFile("/source/after.txt", "after".toByteArray())
        mockOps.addMockDir("/dest")

        mockOps.setFailListFiles(1, { SecurityException("Permission denied") })

        val states = mutableListOf<CopyAction.State.Active<LocalPath, LocalPathLookup, LocalPath, LocalPathLookup>>()

        setOf(LocalPath.build("/source/parent"), LocalPath.build("/source/after.txt")).copyGeneric(
            destination = LocalPath.build("/dest"),
            sourceOps = mockOps,
            destOps = mockOps,
            strategy = strategy,
            progressClock = tickingClock(),
            onIssue = { issue ->
                when (issue) {
                    is PathActionIssue.UnknownError -> PathActionIssue.UnknownError.Resolution.Skip()
                    is PathActionIssue.InsufficientPermission -> PathActionIssue.InsufficientPermission.Resolution.Skip()
                    else -> throw AssertionError("Unexpected issue: $issue")
                }
            }
        ).onEach { if (it is CopyAction.State.Active) states.add(it) }.last()

        // Then - the skipped directory counts towards the items it was already counted in
        val counter = states.counters().last()
        counter.max shouldBe 2L
        counter.current shouldBe 2L
    }

    @Test
    fun `a retried scan does not count its directory twice`() = runTest {
        // Given - a directory whose first listing fails and is retried
        mockOps.addMockDir("/source/parent")
        mockOps.addMockFile("/source/parent/child.txt", "content".toByteArray())
        mockOps.addMockDir("/dest")

        mockOps.setFailListFiles(1, { SecurityException("Permission denied") })

        var retryInvoked = false
        val states = mutableListOf<CopyAction.State.Active<LocalPath, LocalPathLookup, LocalPath, LocalPathLookup>>()

        setOf(LocalPath.build("/source/parent")).copyGeneric(
            destination = LocalPath.build("/dest"),
            sourceOps = mockOps,
            destOps = mockOps,
            strategy = strategy,
            progressClock = tickingClock(),
            onIssue = { issue ->
                when (issue) {
                    is PathActionIssue.UnknownError -> {
                        retryInvoked = true
                        PathActionIssue.UnknownError.Resolution.Retry
                    }
                    else -> throw AssertionError("Unexpected issue: $issue")
                }
            }
        ).onEach { if (it is CopyAction.State.Active) states.add(it) }.last()

        // Then - the directory and its child, not the directory twice
        retryInvoked shouldBe true
        states.counters().maxOf { it.max } shouldBe 2L
    }

    @Test
    fun `a retried transfer counts the file's bytes once`() = runTest {
        // Given - a file whose first attempt dies after part of it was written
        val content = ByteArray(300_000) { (it % 251).toByte() }
        mockOps.addMockFile("/source/file.bin", content)
        mockOps.addMockDir("/dest")

        mockOps.setFailWriteAfter(count = 1, afterBytes = 100_000L)

        val states = mutableListOf<CopyAction.State.Active<LocalPath, LocalPathLookup, LocalPath, LocalPathLookup>>()

        setOf(LocalPath.build("/source/file.bin")).copyGeneric(
            destination = LocalPath.build("/dest"),
            sourceOps = mockOps,
            destOps = mockOps,
            strategy = strategy,
            progressClock = tickingClock(),
            onIssue = { issue ->
                when (issue) {
                    is PathActionIssue.UnknownError -> PathActionIssue.UnknownError.Resolution.Retry
                    // The failed attempt left a partial file behind
                    is PathActionIssue.PathAlreadyExists -> PathActionIssue.PathAlreadyExists.Resolution.Overwrite()
                    else -> throw AssertionError("Unexpected issue: $issue")
                }
            }
        ).onEach { if (it is CopyAction.State.Active) states.add(it) }.last()

        // Then - the abandoned attempt's bytes are not counted a second time
        mockOps.getFileContent("/dest/file.bin") shouldBe content
        states.last().copiedBytes shouldBe content.size.toLong()
    }

    @Test
    fun `a skip-heavy run reports progress as it goes`() = runTest {
        // Given - the destination folder already exists and is merged, and every source file
        // already exists inside it
        mockOps.addMockDir("/source/folder")
        mockOps.addMockDir("/dest")
        mockOps.addMockDir("/dest/folder")
        (1..12).forEach { i ->
            val content = ByteArray(i * 10_000) { 'a'.code.toByte() }
            mockOps.addMockFile("/source/folder/file$i.txt", content)
            mockOps.addMockFile("/dest/folder/file$i.txt", "old".toByteArray())
        }

        val states = mutableListOf<CopyAction.State.Active<LocalPath, LocalPathLookup, LocalPath, LocalPathLookup>>()

        setOf(LocalPath.build("/source/folder")).copyGeneric(
            destination = LocalPath.build("/dest"),
            sourceOps = mockOps,
            destOps = mockOps,
            strategy = strategy,
            progressClock = tickingClock(),
            onIssue = { issue ->
                when (issue) {
                    is PathActionIssue.PathAlreadyExists -> {
                        if (issue.destination.fileType == FileType.DIRECTORY) {
                            PathActionIssue.PathAlreadyExists.Resolution.Merge()
                        } else {
                            PathActionIssue.PathAlreadyExists.Resolution.Skip(applyToAll = true)
                        }
                    }

                    else -> throw AssertionError("Unexpected issue: $issue")
                }
            }
        ).onEach { if (it is CopyAction.State.Active) states.add(it) }.last()

        // Then - the accounted work climbs across the run instead of jumping at the end
        val history = states.mapNotNull { it.primaryProgress.extra as? PerformanceHistory }.last()
        history.samples.map { it.totalBytesAccounted }.distinct().size shouldBeGreaterThan 5
        history.samples.all { it.totalBytesProcessed == 0L } shouldBe true
    }

    @Test
    fun `a source skipped before it was counted does not overshoot the item total`() = runTest {
        // Given - a directory whose only child cannot be planned for writing at the destination
        val ops = PlanAwareMockOps()
        ops.addMockDir("/source/parent")
        ops.addMockFile("/source/parent/blocked.txt", "content".toByteArray())
        ops.addMockDir("/dest")
        ops.failPlanning("/dest/parent/blocked.txt") { SecurityException("Permission denied") }

        val states = mutableListOf<CopyAction.State.Active<LocalPath, LocalPathLookup, LocalPath, LocalPathLookup>>()

        setOf(LocalPath.build("/source/parent")).copyGeneric(
            destination = LocalPath.build("/dest"),
            sourceOps = ops,
            destOps = ops,
            strategy = strategy,
            progressClock = tickingClock(),
            onIssue = { issue ->
                when (issue) {
                    is PathActionIssue.InsufficientPermission -> PathActionIssue.InsufficientPermission.Resolution.Skip()
                    is PathActionIssue.UnknownError -> PathActionIssue.UnknownError.Resolution.Skip()
                    else -> throw AssertionError("Unexpected issue: $issue")
                }
            }
        ).onEach { if (it is CopyAction.State.Active) states.add(it) }.last()

        // Then - no report claims more items done than the scan ever counted
        states.counters()
            .filter { it.current > it.max }
            .map { "${it.current}/${it.max}" } shouldBe emptyList<String>()
    }

    @Test
    fun `the last item resolved through a conflict prompt is reported as done`() = runTest {
        // Given - a folder whose last-processed file already exists at the destination.
        // Children are queued in reverse, so the child added first is the last work item processed.
        mockOps.addMockDir("/source/folder")
        mockOps.addMockFile("/source/folder/conflicting.txt", "new".toByteArray())
        mockOps.addMockFile("/source/folder/plain.txt", "plain".toByteArray())
        mockOps.addMockDir("/dest")
        mockOps.addMockDir("/dest/folder")
        mockOps.addMockFile("/dest/folder/conflicting.txt", "old".toByteArray())

        val states = mutableListOf<CopyAction.State.Active<LocalPath, LocalPathLookup, LocalPath, LocalPathLookup>>()
        val prompted = mutableListOf<String>()

        setOf(LocalPath.build("/source/folder")).copyGeneric(
            destination = LocalPath.build("/dest"),
            sourceOps = mockOps,
            destOps = mockOps,
            strategy = strategy,
            progressClock = tickingClock(),
            onIssue = { issue ->
                when (issue) {
                    is PathActionIssue.PathAlreadyExists -> {
                        prompted.add(issue.destination.lookedUp.path)
                        if (issue.destination.fileType == FileType.DIRECTORY) {
                            PathActionIssue.PathAlreadyExists.Resolution.Merge()
                        } else {
                            // Without applyToAll this runs the prompt path, not the apply-to-all shortcut
                            PathActionIssue.PathAlreadyExists.Resolution.Skip()
                        }
                    }

                    else -> throw AssertionError("Unexpected issue: $issue")
                }
            }
        ).onEach { if (it is CopyAction.State.Active) states.add(it) }.last()

        // The prompt only fires from the conflict-resolution work item, so this pins the path taken
        prompted shouldBe listOf("/dest/folder", "/dest/folder/conflicting.txt")

        // Then - the last reported counter has every item accounted for
        val counter = states.counters().last()
        counter.current shouldBe counter.max
    }

    /**
     * [MockFileSystemOps] plus the intent-aware hooks the scan reaches for. `ensurePlanned` is a
     * no-op on plain ops, so an access-planning failure has no other way in.
     */
    private class PlanAwareMockOps : MockFileSystemOps<LocalPath, LocalPathLookup>(
        { path, type, size, modifiedAt, permissions, ownership, createdAt ->
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
        },
    ), IntentAwareFileSystemOps<LocalPath, LocalPathLookup> {

        private val planFailures = mutableMapOf<String, () -> Exception>()

        fun failPlanning(path: String, exceptionFactory: () -> Exception) {
            planFailures[path] = exceptionFactory
        }

        override suspend fun lookup(path: LocalPath, intent: AccessIntent, options: LookupOptions): LocalPathLookup =
            lookup(path, options)

        override suspend fun lookupFiles(
            path: LocalPath,
            intent: AccessIntent,
            options: LookupOptions,
        ): List<LocalPathLookup> = lookupFiles(path, options)

        override suspend fun ensurePlanned(path: LocalPath, intent: AccessIntent) {
            planFailures[path.path]?.let { throw it() }
        }

        override suspend fun modeOf(path: LocalPath, intent: AccessIntent): AccessMode = AccessMode.DIRECT

        override fun proactiveChildren(parent: LocalPath): Set<LocalPath> = emptySet()

        override suspend fun installLogicalAlias(alias: LocalPath, resolved: LocalPath, intent: AccessIntent) = Unit

        override fun unknownLookup(path: LocalPath, error: Exception): LocalPathLookup? = null
    }
}
