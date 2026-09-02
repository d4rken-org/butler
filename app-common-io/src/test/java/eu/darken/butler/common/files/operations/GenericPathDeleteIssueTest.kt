package eu.darken.butler.common.files.operations

import eu.darken.butler.common.files.Existence
import eu.darken.butler.common.files.LocalPath
import eu.darken.butler.common.files.LookupOptions
import eu.darken.butler.common.files.actions.DeleteAction
import eu.darken.butler.common.files.actions.PathActionIssue
import eu.darken.butler.common.files.errors.ReadException
import eu.darken.butler.common.files.local.LocalPathLookup
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlinx.coroutines.flow.last
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import testhelpers.BaseTest
import kotlin.coroutines.cancellation.CancellationException

/**
 * Tests for GenericPathDelete issue resolution - the interactive delete paths.
 *
 * Split out of GenericPathDeleteTest. Covers everything that drives the
 * operation through an `onIssue` handler:
 * - Error handling with apply-to-all
 * - Retry functionality
 * - Skipped-item reporting, scan errors and cancellation
 *
 * Uses MockFileSystemOps to test without real file system access.
 */
class GenericPathDeleteIssueTest : BaseTest() {

    private lateinit var mockOps: MockFileSystemOps<LocalPath, LocalPathLookup>

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
    }

    @AfterEach
    fun cleanup() {
        mockOps.clear()
    }

    // ============ ERROR HANDLING & RETRY ============

    @Test
    fun `delete retry resolution works after transient error`() = runTest {
        // Given - file that fails once then succeeds
        mockOps.addMockFile("/file.txt", "content".toByteArray())

        // Fail once, then succeed
        mockOps.setFailDelete(1)

        var issueCount = 0

        // When
        val result = LocalPath.build("/file.txt").deleteGeneric(
            fileSystemOps = mockOps,
            recursive = true,
            ignoreMissing = false,
            onIssue = { issue ->
                issueCount++
                PathActionIssue.UnknownError.Resolution.Retry
            }
        ).last() as DeleteAction.State.Completed

        // Then - succeeded after retry
        result.deleted.size shouldBe 1
        issueCount shouldBe 1 // One issue encountered and retried
        mockOps.hasFile("/file.txt") shouldBe false
    }

    @Test
    fun `persistent error after max retries skips`() = runTest {
        // Given - file that always fails
        mockOps.addMockFile("/file.txt", "content".toByteArray())

        // Fail 4 times
        mockOps.setFailDelete(4)

        var issueCount = 0
        val maxRetries = 3

        // When
        val result = LocalPath.build("/file.txt").deleteGeneric(
            fileSystemOps = mockOps,
            recursive = true,
            ignoreMissing = false,
            onIssue = { issue ->
                issueCount++
                if (issueCount <= maxRetries) {
                    PathActionIssue.UnknownError.Resolution.Retry
                } else {
                    PathActionIssue.UnknownError.Resolution.Skip()
                }
            }
        ).last() as DeleteAction.State.Completed

        // Then - file skipped after max retries
        (issueCount >= maxRetries) shouldBe true
        result.deleted.size shouldBe 0
        result.skipped.size shouldBe 1
    }

    @Test
    fun `retry does not regress progress tracking`() = runTest {
        // Given - file that fails once
        mockOps.addMockFile("/file.txt", "content".toByteArray())

        mockOps.setFailDelete(1)

        val progressUpdates = mutableListOf<DeleteAction.State.Active<LocalPath, LocalPathLookup>>()

        // When
        LocalPath.build("/file.txt").deleteGeneric(
            fileSystemOps = mockOps,
            recursive = true,
            ignoreMissing = false,
            onIssue = { PathActionIssue.UnknownError.Resolution.Retry }
        ).collect { state ->
            when (state) {
                is DeleteAction.State.Active -> progressUpdates.add(state)
                is DeleteAction.State.Completed -> { /* final result */
                }
            }
        }

        // Then - progress never goes backwards
        if (progressUpdates.size > 1) {
            progressUpdates.zipWithNext().forEach { (prev, next) ->
                (next.deletedBytes >= prev.deletedBytes) shouldBe true
            }
        }
    }

    @Test
    fun `permission error with skip resolution`() = runTest {
        // Given - file that throws permission error
        mockOps.addMockFile("/file.txt", "content".toByteArray())

        mockOps.setFailDelete(1) { java.nio.file.AccessDeniedException("/file.txt") }

        // When - skip on permission error
        val result = LocalPath.build("/file.txt").deleteGeneric(
            fileSystemOps = mockOps,
            recursive = true,
            ignoreMissing = false,
            onIssue = { issue ->
                when (issue) {
                    is PathActionIssue.InsufficientPermission -> {
                        PathActionIssue.InsufficientPermission.Resolution.Skip()
                    }
                    else -> throw AssertionError("Unexpected issue: $issue")
                }
            }
        ).last() as DeleteAction.State.Completed

        // Then - file skipped
        result.deleted.size shouldBe 0
        result.skipped.size shouldBe 1
    }

    @Test
    fun `permission error with apply-to-all skips subsequent permission errors`() = runTest {
        // Given - multiple files with permission errors
        mockOps.addMockFile("/file1.txt", "content1".toByteArray())
        mockOps.addMockFile("/file2.txt", "content2".toByteArray())
        mockOps.addMockFile("/file3.txt", "content3".toByteArray())

        // All files fail with permission error
        mockOps.setFailDelete(3) { java.nio.file.AccessDeniedException("Permission denied") }

        val targets = listOf(
            LocalPath.build("/file1.txt"),
            LocalPath.build("/file2.txt"),
            LocalPath.build("/file3.txt")
        )

        var issueCount = 0

        // When - skip with apply-to-all on first error
        val result = targets.deleteGeneric(
            fileSystemOps = mockOps,
            recursive = true,
            ignoreMissing = false,
            onIssue = { issue ->
                issueCount++
                when (issue) {
                    is PathActionIssue.InsufficientPermission -> {
                        PathActionIssue.InsufficientPermission.Resolution.Skip(applyToAll = true)
                    }
                    else -> throw AssertionError("Unexpected issue: $issue")
                }
            }
        ).last() as DeleteAction.State.Completed

        // Then - all files skipped, but only asked once due to apply-to-all
        result.deleted.size shouldBe 0
        result.skipped.size shouldBe 3
        issueCount shouldBe 1 // Only asked once
    }

    @Test
    fun `unknown error with skip resolution`() = runTest {
        // Given - file that throws unknown error
        mockOps.addMockFile("/file.txt", "content".toByteArray())

        mockOps.setFailDelete(1) { java.io.IOException("Unknown error") }

        // When - skip on unknown error
        val result = LocalPath.build("/file.txt").deleteGeneric(
            fileSystemOps = mockOps,
            recursive = true,
            ignoreMissing = false,
            onIssue = { issue ->
                when (issue) {
                    is PathActionIssue.UnknownError -> {
                        PathActionIssue.UnknownError.Resolution.Skip()
                    }
                    else -> throw AssertionError("Unexpected issue: $issue")
                }
            }
        ).last() as DeleteAction.State.Completed

        // Then - file skipped
        result.deleted.size shouldBe 0
        result.skipped.size shouldBe 1
    }

    @Test
    fun `unknown error with apply-to-all skips subsequent unknown errors`() = runTest {
        // Given - multiple files that fail
        mockOps.addMockFile("/file1.txt", "content1".toByteArray())
        mockOps.addMockFile("/file2.txt", "content2".toByteArray())
        mockOps.addMockFile("/file3.txt", "content3".toByteArray())

        // All files fail
        mockOps.setFailDelete(3) { java.io.IOException("Unknown error") }

        val targets = listOf(
            LocalPath.build("/file1.txt"),
            LocalPath.build("/file2.txt"),
            LocalPath.build("/file3.txt")
        )

        var issueCount = 0

        // When - skip with apply-to-all on first error
        val result = targets.deleteGeneric(
            fileSystemOps = mockOps,
            recursive = true,
            ignoreMissing = false,
            onIssue = { issue ->
                issueCount++
                when (issue) {
                    is PathActionIssue.UnknownError -> {
                        PathActionIssue.UnknownError.Resolution.Skip(applyToAll = true)
                    }
                    else -> throw AssertionError("Unexpected issue: $issue")
                }
            }
        ).last() as DeleteAction.State.Completed

        // Then - all files skipped, but only asked once
        result.deleted.size shouldBe 0
        result.skipped.size shouldBe 3
        issueCount shouldBe 1 // Only asked once due to apply-to-all
    }

    // ============ RESULT VERIFICATION ============

    @Test
    fun `result contains skipped items`() = runTest {
        // Given - files where some will fail
        mockOps.addMockFile("/file1.txt", "content1".toByteArray())
        mockOps.addMockFile("/file2.txt", "content2".toByteArray())

        // Fail on file2
        mockOps.setFailDelete(1)

        val targets = listOf(
            LocalPath.build("/file1.txt"),
            LocalPath.build("/file2.txt")
        )

        // When - skip on error
        val result = targets.deleteGeneric(
            fileSystemOps = mockOps,
            recursive = true,
            ignoreMissing = false,
            onIssue = { PathActionIssue.UnknownError.Resolution.Skip() }
        ).last() as DeleteAction.State.Completed

        // Then - result contains skipped item
        result.deleted.size shouldBe 1
        result.skipped.size shouldBe 1
    }

    @Test
    fun `deleted and skipped sets are mutually exclusive`() = runTest {
        // Given - multiple files, some will fail
        mockOps.addMockFile("/file1.txt", "content1".toByteArray())
        mockOps.addMockFile("/file2.txt", "content2".toByteArray())
        mockOps.addMockFile("/file3.txt", "content3".toByteArray())

        // Fail once for file2
        mockOps.setFailDelete(1)

        val targets = listOf(
            LocalPath.build("/file1.txt"),
            LocalPath.build("/file2.txt"),
            LocalPath.build("/file3.txt")
        )

        // When - skip on error
        val result = targets.deleteGeneric(
            fileSystemOps = mockOps,
            recursive = true,
            ignoreMissing = false,
            onIssue = { PathActionIssue.UnknownError.Resolution.Skip() }
        ).last() as DeleteAction.State.Completed

        // Then - verify no overlap between deleted and skipped
        val deletedPaths = result.deleted.map { it.lookedUp }.toSet()
        val skippedPaths = result.skipped.map { it.lookedUp }.toSet()

        deletedPaths.intersect(skippedPaths).shouldBeEmpty()
        (deletedPaths.size + skippedPaths.size) shouldBe 3
    }

    // ============ EDGE CASES ============

    @Test
    fun `delete multiple targets with error in one`() = runTest {
        // Given - multiple targets, one fails
        mockOps.addMockFile("/file1.txt", "content1".toByteArray())
        mockOps.addMockFile("/file2.txt", "content2".toByteArray())
        mockOps.addMockFile("/file3.txt", "content3".toByteArray())

        // Fail on first delete call
        // Note: Delete operations are queued in reverse order (using addFirst during scan),
        // so file3 will be deleted first, then file2, then file1
        mockOps.setFailDelete(1)

        val targets = listOf(
            LocalPath.build("/file1.txt"),
            LocalPath.build("/file2.txt"),
            LocalPath.build("/file3.txt")
        )

        // When - skip error
        val result = targets.deleteGeneric(
            fileSystemOps = mockOps,
            recursive = true,
            ignoreMissing = false,
            onIssue = { PathActionIssue.UnknownError.Resolution.Skip() }
        ).last() as DeleteAction.State.Completed

        // Then - one file skipped (file3), other files deleted (file1, file2)
        result.deleted.size shouldBe 2
        result.skipped.size shouldBe 1
        mockOps.hasFile("/file1.txt") shouldBe false
        mockOps.hasFile("/file2.txt") shouldBe false
        mockOps.hasFile("/file3.txt") shouldBe true // This one failed and still exists
    }

    /**
     * A mock whose lookup fails with a denial rather than a "not found", so what the operation does
     * with it is decided by the strict probe alone.
     */
    private fun deniedLookupOps(probe: () -> Existence) = object : MockFileSystemOps<LocalPath, LocalPathLookup>(
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
    ) {
        override suspend fun lookup(path: LocalPath, options: LookupOptions): LocalPathLookup =
            throw SecurityException("Permission denied")

        override suspend fun existsStrict(path: LocalPath): Existence {
            existsStrictCalls.add(path.path)
            return probe()
        }
    }

    @Test
    fun `a denied path that cannot be verified is not swallowed by ignoreMissing`() = runTest {
        // ignoreMissing may only skip a definitive "not there". The probe answers UNKNOWN, so this
        // has to surface instead of being counted as a completed delete.
        val ops = deniedLookupOps { Existence.UNKNOWN }

        shouldThrow<ReadException> {
            LocalPath.build("/file.txt").deleteGeneric(
                fileSystemOps = ops,
                recursive = true,
                ignoreMissing = true,
                onIssue = { PathActionIssue.UnknownError.Resolution.Skip() },
            ).last()
        }

        ops.existsStrictCalls shouldContain "/file.txt"
    }

    @Test
    fun `a cancelled existence probe cancels the delete`() = runTest {
        val ops = deniedLookupOps { throw CancellationException("cancel probe") }
        var issueCount = 0

        shouldThrow<CancellationException> {
            LocalPath.build("/file.txt").deleteGeneric(
                fileSystemOps = ops,
                recursive = true,
                ignoreMissing = true,
                onIssue = {
                    issueCount++
                    PathActionIssue.UnknownError.Resolution.Skip()
                },
            ).last()
        }

        issueCount shouldBe 0
    }

    // ============ SCAN ERROR HANDLING ============

    @Test
    fun `delete scan cancellation propagates`() = runTest {
        mockOps.addMockDir("/parent")
        mockOps.addMockFile("/parent/child.txt", "content".toByteArray())
        mockOps.setFailListFiles(1) { CancellationException("cancel delete scan") }

        shouldThrow<CancellationException> {
            LocalPath.build("/parent").deleteGeneric(
                fileSystemOps = mockOps,
                recursive = true,
                ignoreMissing = false,
                onIssue = { PathActionIssue.UnknownError.Resolution.Skip() }
            ).last()
        }
    }

    @Test
    fun `directory scan error during delete then skip should appear only in skipped`() = runTest {
        // Given - directory with children that will fail during listFiles
        mockOps.addMockDir("/parent")
        mockOps.addMockFile("/parent/child.txt", "content".toByteArray())

        // Inject listFiles failure (simulates permission denied during scan)
        mockOps.setFailListFiles(1, { SecurityException("Permission denied") })

        val sourcePath = LocalPath.build("/parent")

        var issueReceived = false

        // When
        val result = sourcePath.deleteGeneric(
            fileSystemOps = mockOps,
            recursive = true,
            ignoreMissing = false,
            onIssue = { issue ->
                issueReceived = true
                when (issue) {
                    is PathActionIssue.InsufficientPermission -> PathActionIssue.InsufficientPermission.Resolution.Skip()
                    is PathActionIssue.UnknownError -> PathActionIssue.UnknownError.Resolution.Skip()
                    else -> TODO("Unexpected issue type: $issue")
                }
            }
        ).last() as DeleteAction.State.Completed

        // Then - directory should be ONLY in skipped, NOT in deleted
        result.deleted.map { it.lookedUp } shouldNotBe setOf(LocalPath.build("/parent"))
        result.skipped.map { it.lookedUp } shouldContain LocalPath.build("/parent")
        issueReceived shouldBe true

        // Directory and child should still exist (delete was skipped)
        mockOps.hasFile("/parent") shouldBe true
        mockOps.hasFile("/parent/child.txt") shouldBe true
    }
}
