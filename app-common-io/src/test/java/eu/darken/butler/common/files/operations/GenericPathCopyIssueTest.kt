package eu.darken.butler.common.files.operations

import eu.darken.butler.common.files.LocalPath
import eu.darken.butler.common.files.actions.CopyAction
import eu.darken.butler.common.files.actions.PathActionIssue
import eu.darken.butler.common.files.local.LocalPathLookup
import eu.darken.butler.common.files.metadata.FileType
import io.kotest.assertions.throwables.shouldThrow
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
}
