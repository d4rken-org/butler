package eu.darken.butler.common.files.operations

import eu.darken.butler.common.files.LocalPath
import eu.darken.butler.common.files.actions.DeleteAction
import eu.darken.butler.common.files.actions.PathActionIssue
import eu.darken.butler.common.files.local.LocalPathLookup
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import eu.darken.butler.common.files.metadata.FileType
import io.kotest.matchers.longs.shouldBeGreaterThanOrEqual
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlinx.coroutines.flow.last
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import testhelpers.BaseTest
import java.util.concurrent.atomic.AtomicInteger

/**
 * Tests for GenericPathDelete - the high-level delete orchestrator.
 *
 * Tests the complete delete operation including:
 * - Scanning source trees
 * - Post-order deletion (children before parents)
 * - Two-phase workflow (scan then delete)
 * - Progress reporting with throttling
 * - Error handling with apply-to-all
 * - Retry functionality
 *
 * Uses MockFileSystemOps to test without real file system access.
 */
class GenericPathDeleteTest : BaseTest() {

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

    // ============ BASIC DELETION OPERATIONS ============

    @Test
    fun `delete single file`() = runTest {
        // Given - single file
        mockOps.addMockFile("/file.txt", "content".toByteArray())

        val sourcePath = LocalPath.build("/file.txt")

        // When
        val result = sourcePath.deleteGeneric(
            fileSystemOps = mockOps,
            recursive = true,
            ignoreMissing = false
        ).last() as DeleteAction.State.Completed

        // Then - file deleted
        mockOps.hasFile("/file.txt") shouldBe false
        result.deleted.size shouldBe 1
        result.skipped.size shouldBe 0
    }

    @Test
    fun `delete empty directory`() = runTest {
        // Given - empty directory
        mockOps.addMockDir("/emptydir")

        val sourcePath = LocalPath.build("/emptydir")

        // When
        val result = sourcePath.deleteGeneric(
            fileSystemOps = mockOps,
            recursive = true,
            ignoreMissing = false
        ).last() as DeleteAction.State.Completed

        // Then - directory deleted
        mockOps.hasFile("/emptydir") shouldBe false
        result.deleted.size shouldBe 1
        result.skipped.size shouldBe 0
    }

    @Test
    fun `delete nested directory structure`() = runTest {
        // Given - nested structure
        mockOps.addMockDir("/parent")
        mockOps.addMockDir("/parent/child")
        mockOps.addMockFile("/parent/child/file.txt", "content".toByteArray())

        val sourcePath = LocalPath.build("/parent")

        // When
        val result = sourcePath.deleteGeneric(
            fileSystemOps = mockOps,
            recursive = true,
            ignoreMissing = false
        ).last() as DeleteAction.State.Completed

        // Then - all deleted
        mockOps.hasFile("/parent") shouldBe false
        mockOps.hasFile("/parent/child") shouldBe false
        mockOps.hasFile("/parent/child/file.txt") shouldBe false
        result.deleted.size shouldBe 3 // parent + child + file
        result.skipped.size shouldBe 0
    }

    @Test
    fun `delete mixed files and directories`() = runTest {
        // Given - mixed structure
        mockOps.addMockDir("/root")
        mockOps.addMockFile("/root/file1.txt", "content1".toByteArray())
        mockOps.addMockDir("/root/subdir")
        mockOps.addMockFile("/root/subdir/file2.txt", "content2".toByteArray())
        mockOps.addMockFile("/root/file3.txt", "content3".toByteArray())

        val sourcePath = LocalPath.build("/root")

        // When
        val result = sourcePath.deleteGeneric(
            fileSystemOps = mockOps,
            recursive = true,
            ignoreMissing = false
        ).last() as DeleteAction.State.Completed

        // Then - all deleted
        mockOps.hasFile("/root") shouldBe false
        result.deleted.size shouldBe 5 // root + subdir + 3 files
        result.skipped.size shouldBe 0
    }

    @Test
    fun `delete root-level file (no subdirectories)`() = runTest {
        // Given - single file at root
        mockOps.addMockFile("/rootfile.txt", "root content".toByteArray())

        val sourcePath = LocalPath.build("/rootfile.txt")

        // When
        val result = sourcePath.deleteGeneric(
            fileSystemOps = mockOps,
            recursive = true,
            ignoreMissing = false
        ).last() as DeleteAction.State.Completed

        // Then - file deleted
        mockOps.hasFile("/rootfile.txt") shouldBe false
        result.deleted.size shouldBe 1
        result.skipped.size shouldBe 0
    }

    @Test
    fun `delete deeply nested structure (10 levels)`() = runTest {
        // Given - deeply nested structure
        var currentPath = "/level0"
        mockOps.addMockDir(currentPath)

        repeat(9) { level ->
            val nextPath = "$currentPath/level${level + 1}"
            mockOps.addMockDir(nextPath)
            currentPath = nextPath
        }
        mockOps.addMockFile("$currentPath/deep.txt", "deep content".toByteArray())

        val sourcePath = LocalPath.build("/level0")

        // When
        val result = sourcePath.deleteGeneric(
            fileSystemOps = mockOps,
            recursive = true,
            ignoreMissing = false
        ).last() as DeleteAction.State.Completed

        // Then - all deleted
        mockOps.hasFile("/level0") shouldBe false
        result.deleted.size shouldBe 11 // 10 directories + 1 file
        result.skipped.size shouldBe 0
    }

    // ============ POST-ORDER DELETION VALIDATION ============

    @Test
    fun `verify children deleted before parents in nested structure`() = runTest {
        // Given - nested structure
        mockOps.addMockDir("/parent")
        mockOps.addMockDir("/parent/child")
        mockOps.addMockDir("/parent/child/grandchild")
        mockOps.addMockFile("/parent/child/grandchild/file.txt", "content".toByteArray())

        val sourcePath = LocalPath.build("/parent")

        // When
        val result = sourcePath.deleteGeneric(
            fileSystemOps = mockOps,
            recursive = true,
            ignoreMissing = false
        ).last() as DeleteAction.State.Completed

        // Then - verify post-order: file, grandchild, child, parent
        val deletionOrder = mockOps.deleteCalls
        val fileIndex = deletionOrder.indexOf("/parent/child/grandchild/file.txt")
        val grandchildIndex = deletionOrder.indexOf("/parent/child/grandchild")
        val childIndex = deletionOrder.indexOf("/parent/child")
        val parentIndex = deletionOrder.indexOf("/parent")

        (fileIndex >= 0) shouldBe true
        (grandchildIndex >= 0) shouldBe true
        (childIndex >= 0) shouldBe true
        (parentIndex >= 0) shouldBe true

        // Verify order: file before grandchild before child before parent
        (fileIndex < grandchildIndex) shouldBe true
        (grandchildIndex < childIndex) shouldBe true
        (childIndex < parentIndex) shouldBe true

        result.deleted.size shouldBe 4
    }

    @Test
    fun `deletion order tracked correctly for complex tree`() = runTest {
        // Given - complex tree
        mockOps.addMockDir("/root")
        mockOps.addMockFile("/root/file1.txt", "content1".toByteArray())
        mockOps.addMockDir("/root/dir1")
        mockOps.addMockFile("/root/dir1/file2.txt", "content2".toByteArray())
        mockOps.addMockDir("/root/dir2")
        mockOps.addMockFile("/root/dir2/file3.txt", "content3".toByteArray())

        val sourcePath = LocalPath.build("/root")

        // When
        val result = sourcePath.deleteGeneric(
            fileSystemOps = mockOps,
            recursive = true,
            ignoreMissing = false
        ).last() as DeleteAction.State.Completed

        // Then - verify post-order
        val deletionOrder = mockOps.deleteCalls

        // Files deleted before their parent directories
        val file1Index = deletionOrder.indexOf("/root/file1.txt")
        val file2Index = deletionOrder.indexOf("/root/dir1/file2.txt")
        val file3Index = deletionOrder.indexOf("/root/dir2/file3.txt")
        val dir1Index = deletionOrder.indexOf("/root/dir1")
        val dir2Index = deletionOrder.indexOf("/root/dir2")
        val rootIndex = deletionOrder.indexOf("/root")

        // file2 before dir1
        (file2Index < dir1Index) shouldBe true
        // file3 before dir2
        (file3Index < dir2Index) shouldBe true
        // all children before root
        (file1Index < rootIndex) shouldBe true
        (dir1Index < rootIndex) shouldBe true
        (dir2Index < rootIndex) shouldBe true

        result.deleted.size shouldBe 6
    }

    @Test
    fun `empty directories deleted after scanning children`() = runTest {
        // Given - directory with subdirectories only (no files)
        mockOps.addMockDir("/root")
        mockOps.addMockDir("/root/empty1")
        mockOps.addMockDir("/root/empty2")
        mockOps.addMockDir("/root/empty3")

        val sourcePath = LocalPath.build("/root")

        // When
        val result = sourcePath.deleteGeneric(
            fileSystemOps = mockOps,
            recursive = true,
            ignoreMissing = false
        ).last() as DeleteAction.State.Completed

        // Then - children deleted before parent
        val deletionOrder = mockOps.deleteCalls
        val rootIndex = deletionOrder.indexOf("/root")
        val empty1Index = deletionOrder.indexOf("/root/empty1")
        val empty2Index = deletionOrder.indexOf("/root/empty2")
        val empty3Index = deletionOrder.indexOf("/root/empty3")

        (empty1Index < rootIndex) shouldBe true
        (empty2Index < rootIndex) shouldBe true
        (empty3Index < rootIndex) shouldBe true

        result.deleted.size shouldBe 4
    }

    @Test
    fun `deletion order for very deep hierarchy`() = runTest {
        // Given - 5 levels deep
        mockOps.addMockDir("/a")
        mockOps.addMockDir("/a/b")
        mockOps.addMockDir("/a/b/c")
        mockOps.addMockDir("/a/b/c/d")
        mockOps.addMockDir("/a/b/c/d/e")
        mockOps.addMockFile("/a/b/c/d/e/file.txt", "content".toByteArray())

        val sourcePath = LocalPath.build("/a")

        // When
        val result = sourcePath.deleteGeneric(
            fileSystemOps = mockOps,
            recursive = true,
            ignoreMissing = false
        ).last() as DeleteAction.State.Completed

        // Then - deepest items deleted first
        val deletionOrder = mockOps.deleteCalls
        val indices = mapOf(
            "/a/b/c/d/e/file.txt" to deletionOrder.indexOf("/a/b/c/d/e/file.txt"),
            "/a/b/c/d/e" to deletionOrder.indexOf("/a/b/c/d/e"),
            "/a/b/c/d" to deletionOrder.indexOf("/a/b/c/d"),
            "/a/b/c" to deletionOrder.indexOf("/a/b/c"),
            "/a/b" to deletionOrder.indexOf("/a/b"),
            "/a" to deletionOrder.indexOf("/a")
        )

        // Verify strict post-order
        (indices["/a/b/c/d/e/file.txt"]!! < indices["/a/b/c/d/e"]!!) shouldBe true
        (indices["/a/b/c/d/e"]!! < indices["/a/b/c/d"]!!) shouldBe true
        (indices["/a/b/c/d"]!! < indices["/a/b/c"]!!) shouldBe true
        (indices["/a/b/c"]!! < indices["/a/b"]!!) shouldBe true
        (indices["/a/b"]!! < indices["/a"]!!) shouldBe true

        result.deleted.size shouldBe 6
    }

    // ============ RECURSIVE FLAG BEHAVIOR ============

    @Test
    fun `recursive=true deletes entire tree`() = runTest {
        // Given - directory tree
        mockOps.addMockDir("/root")
        mockOps.addMockDir("/root/subdir")
        mockOps.addMockFile("/root/subdir/file.txt", "content".toByteArray())

        val sourcePath = LocalPath.build("/root")

        // When - recursive=true
        val result = sourcePath.deleteGeneric(
            fileSystemOps = mockOps,
            recursive = true,
            ignoreMissing = false
        ).last() as DeleteAction.State.Completed

        // Then - all deleted
        mockOps.hasFile("/root") shouldBe false
        mockOps.hasFile("/root/subdir") shouldBe false
        mockOps.hasFile("/root/subdir/file.txt") shouldBe false
        result.deleted.size shouldBe 3
    }

    @Test
    fun `recursive=false deletes only empty directories`() = runTest {
        // Given - empty directory
        mockOps.addMockDir("/emptydir")

        val sourcePath = LocalPath.build("/emptydir")

        // When - recursive=false
        val result = sourcePath.deleteGeneric(
            fileSystemOps = mockOps,
            recursive = false,
            ignoreMissing = false
        ).last() as DeleteAction.State.Completed

        // Then - empty directory deleted
        mockOps.hasFile("/emptydir") shouldBe false
        result.deleted.size shouldBe 1
    }

    @Test
    fun `recursive=false with non-empty directory fails`() = runTest {
        // Given - non-empty directory
        mockOps.addMockDir("/dir")
        mockOps.addMockFile("/dir/file.txt", "content".toByteArray())

        val sourcePath = LocalPath.build("/dir")

        // When/Then - recursive=false with non-empty directory throws
        var errorThrown = false
        try {
            sourcePath.deleteGeneric(
                fileSystemOps = mockOps,
                recursive = false,
                ignoreMissing = false
            ).collect { }  // Actually execute the Flow
        } catch (e: Exception) {
            errorThrown = true
            // GenericPathDelete wraps the IllegalStateException in a WriteException
            val errorMessage = e.cause?.message ?: e.message ?: ""
            (errorMessage.contains("not empty") || errorMessage.contains("Directory not empty")) shouldBe true
        }

        errorThrown shouldBe true
        // Directory should still exist (deletion failed)
        mockOps.hasFile("/dir") shouldBe true
        mockOps.hasFile("/dir/file.txt") shouldBe true
    }

    @Test
    fun `recursive=false deletes single file`() = runTest {
        // Given - single file
        mockOps.addMockFile("/file.txt", "content".toByteArray())

        val sourcePath = LocalPath.build("/file.txt")

        // When - recursive=false
        val result = sourcePath.deleteGeneric(
            fileSystemOps = mockOps,
            recursive = false,
            ignoreMissing = false
        ).last() as DeleteAction.State.Completed

        // Then - file deleted
        mockOps.hasFile("/file.txt") shouldBe false
        result.deleted.size shouldBe 1
    }

    @Test
    fun `recursive flag with mixed content`() = runTest {
        // Given - multiple targets: file and directories
        mockOps.addMockFile("/file.txt", "content".toByteArray())
        mockOps.addMockDir("/emptydir")
        mockOps.addMockDir("/fulldir")
        mockOps.addMockFile("/fulldir/file.txt", "content".toByteArray())

        val targets = listOf(
            LocalPath.build("/file.txt"),
            LocalPath.build("/emptydir"),
            LocalPath.build("/fulldir")
        )

        // When - recursive=true
        val result = targets.deleteGeneric(
            fileSystemOps = mockOps,
            recursive = true,
            ignoreMissing = false
        ).last() as DeleteAction.State.Completed

        // Then - all deleted
        mockOps.hasFile("/file.txt") shouldBe false
        mockOps.hasFile("/emptydir") shouldBe false
        mockOps.hasFile("/fulldir") shouldBe false
        result.deleted.size shouldBe 4 // file.txt + emptydir + fulldir + fulldir/file.txt
    }

    @Test
    fun `recursive=false handles multiple empty directories`() = runTest {
        // Given - multiple empty directories
        mockOps.addMockDir("/empty1")
        mockOps.addMockDir("/empty2")
        mockOps.addMockDir("/empty3")

        val targets = listOf(
            LocalPath.build("/empty1"),
            LocalPath.build("/empty2"),
            LocalPath.build("/empty3")
        )

        // When - recursive=false
        val result = targets.deleteGeneric(
            fileSystemOps = mockOps,
            recursive = false,
            ignoreMissing = false
        ).last() as DeleteAction.State.Completed

        // Then - all deleted
        mockOps.hasFile("/empty1") shouldBe false
        mockOps.hasFile("/empty2") shouldBe false
        mockOps.hasFile("/empty3") shouldBe false
        result.deleted.size shouldBe 3
    }

    // ============ IGNORE MISSING FLAG BEHAVIOR ============

    @Test
    fun `ignoreMissing=true skips non-existent files`() = runTest {
        // Given - non-existent file
        val sourcePath = LocalPath.build("/nonexistent.txt")

        // When - ignoreMissing=true
        val result = sourcePath.deleteGeneric(
            fileSystemOps = mockOps,
            recursive = true,
            ignoreMissing = true
        ).last() as DeleteAction.State.Completed

        // Then - no error, empty result
        result.deleted.size shouldBe 0
        result.skipped.size shouldBe 0
    }

    @Test
    fun `ignoreMissing=false throws on non-existent files`() = runTest {
        // Given - non-existent file
        val sourcePath = LocalPath.build("/nonexistent.txt")

        // When/Then - ignoreMissing=false throws
        var errorThrown = false
        try {
            sourcePath.deleteGeneric(
                fileSystemOps = mockOps,
                recursive = true,
                ignoreMissing = false
            ).collect { }  // Actually execute the Flow
        } catch (e: Exception) {
            errorThrown = true
        }

        errorThrown shouldBe true
    }

    @Test
    fun `mixed existing and non-existing files with ignoreMissing=true`() = runTest {
        // Given - mix of existing and non-existing files
        mockOps.addMockFile("/exists1.txt", "content1".toByteArray())
        mockOps.addMockFile("/exists2.txt", "content2".toByteArray())

        val targets = listOf(
            LocalPath.build("/exists1.txt"),
            LocalPath.build("/nonexistent.txt"),
            LocalPath.build("/exists2.txt")
        )

        // When - ignoreMissing=true
        val result = targets.deleteGeneric(
            fileSystemOps = mockOps,
            recursive = true,
            ignoreMissing = true
        ).last() as DeleteAction.State.Completed

        // Then - existing files deleted, non-existing skipped
        mockOps.hasFile("/exists1.txt") shouldBe false
        mockOps.hasFile("/exists2.txt") shouldBe false
        result.deleted.size shouldBe 2
    }

    @Test
    fun `file deleted between scan and delete phases with ignoreMissing=true`() = runTest {
        // Given - file that will be "deleted" during operation
        mockOps.addMockFile("/file.txt", "content".toByteArray())

        val sourcePath = LocalPath.build("/file.txt")

        // Configure mock to delete file after first lookup (during delete phase)
        var lookupCount = 0
        mockOps.setFailDelete(1) {
            lookupCount++
            if (lookupCount == 1) {
                // Simulate file being deleted externally
                java.io.FileNotFoundException("File deleted externally")
            } else {
                java.io.IOException("Unexpected")
            }
        }

        // When - ignoreMissing=true
        val result = sourcePath.deleteGeneric(
            fileSystemOps = mockOps,
            recursive = true,
            ignoreMissing = true
        ).last() as DeleteAction.State.Completed

        // Then - no error thrown, operation completes
        // The file scan found it, but delete phase handled the missing file
        result.deleted.size shouldBe 0 // Not counted as deleted since it failed
    }

    // ============ PROGRESS REPORTING ============

    @Test
    fun `progress callback receives scan and delete updates`() = runTest {
        // Given - directory structure
        mockOps.addMockDir("/root")
        mockOps.addMockFile("/root/file1.txt", "content1".toByteArray())
        mockOps.addMockFile("/root/file2.txt", "content2".toByteArray())

        val sourcePath = LocalPath.build("/root")
        val progressUpdates = mutableListOf<DeleteAction.State.Active<LocalPath, LocalPathLookup>>()

        // When
        sourcePath.deleteGeneric(
            fileSystemOps = mockOps,
            recursive = true,
            ignoreMissing = false
        ).collect { state ->
            when (state) {
                is DeleteAction.State.Active -> progressUpdates.add(state)
                is DeleteAction.State.Completed -> { /* final result */
                }
            }
        }

        // Then - should receive progress updates
        (progressUpdates.size > 0) shouldBe true
    }

    @Test
    fun `delete progress reports items processed`() = runTest {
        // Given - multiple files
        mockOps.addMockFile("/file1.txt", "content1".toByteArray())
        mockOps.addMockFile("/file2.txt", "content2".toByteArray())
        mockOps.addMockFile("/file3.txt", "content3".toByteArray())

        val targets = listOf(
            LocalPath.build("/file1.txt"),
            LocalPath.build("/file2.txt"),
            LocalPath.build("/file3.txt")
        )

        val progressUpdates = mutableListOf<DeleteAction.State.Active<LocalPath, LocalPathLookup>>()

        // When
        targets.deleteGeneric(
            fileSystemOps = mockOps,
            recursive = true,
            ignoreMissing = false
        ).collect { state ->
            when (state) {
                is DeleteAction.State.Active -> progressUpdates.add(state)
                is DeleteAction.State.Completed -> { /* final result */
                }
            }
        }

        // Then - should see progress towards 3 items
        (progressUpdates.size > 0) shouldBe true
        // Final progress should show all items processed
        if (progressUpdates.isNotEmpty()) {
            val lastProgress = progressUpdates.last()
            (lastProgress.deletedBytes > 0) shouldBe true
        }
    }

    @Test
    fun `progress callbacks throttled to reduce overhead`() = runTest {
        // Given - 100 files that would generate 200+ callbacks without throttling
        repeat(100) { i ->
            mockOps.addMockFile("/file$i.txt", "content".toByteArray())
        }

        val targets = (0 until 100).map { LocalPath.build("/file$it.txt") }
        val progressCallCount = AtomicInteger(0)

        // When
        targets.deleteGeneric(
            fileSystemOps = mockOps,
            recursive = true,
            ignoreMissing = false
        ).collect { state ->
            when (state) {
                is DeleteAction.State.Active -> progressCallCount.incrementAndGet()
                is DeleteAction.State.Completed -> { /* final result */
                }
            }
        }

        // Then - fewer calls than without throttling (200+ for scan+delete phases)
        // In fast test environment, some throttling should still occur
        (progressCallCount.get() < 150) shouldBe true
        // But should have received some progress updates
        (progressCallCount.get() > 0) shouldBe true
    }

    @Test
    fun `final progress shows completion`() = runTest {
        // Given - files to delete
        mockOps.addMockFile("/file1.txt", ByteArray(1000))
        mockOps.addMockFile("/file2.txt", ByteArray(2000))

        val targets = listOf(
            LocalPath.build("/file1.txt"),
            LocalPath.build("/file2.txt")
        )

        val progressUpdates = mutableListOf<DeleteAction.State.Active<LocalPath, LocalPathLookup>>()

        // When
        targets.deleteGeneric(
            fileSystemOps = mockOps,
            recursive = true,
            ignoreMissing = false
        ).collect { state ->
            when (state) {
                is DeleteAction.State.Active -> progressUpdates.add(state)
                is DeleteAction.State.Completed -> { /* final result */
                }
            }
        }

        // Then - final progress shows completion
        (progressUpdates.isNotEmpty()) shouldBe true
        val finalProgress = progressUpdates.last()
        (finalProgress.deletedBytes == finalProgress.totalBytes) shouldBe true
    }

    @Test
    fun `progress never regresses during operation`() = runTest {
        // Given - multiple files
        repeat(20) { i ->
            mockOps.addMockFile("/file$i.txt", "content$i".toByteArray())
        }

        val targets = (0 until 20).map { LocalPath.build("/file$it.txt") }
        val progressUpdates = mutableListOf<DeleteAction.State.Active<LocalPath, LocalPathLookup>>()

        // When
        targets.deleteGeneric(
            fileSystemOps = mockOps,
            recursive = true,
            ignoreMissing = false
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
                when (issue){
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
                when (issue){
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
                when (issue){
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
                when (issue){
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
    fun `result contains deleted items`() = runTest {
        // Given - files to delete
        mockOps.addMockFile("/file1.txt", "content1".toByteArray())
        mockOps.addMockFile("/file2.txt", "content2".toByteArray())

        val targets = listOf(
            LocalPath.build("/file1.txt"),
            LocalPath.build("/file2.txt")
        )

        // When
        val result = targets.deleteGeneric(
            fileSystemOps = mockOps,
            recursive = true,
            ignoreMissing = false
        ).last() as DeleteAction.State.Completed

        // Then - result contains all deleted items
        result.deleted.size shouldBe 2
        result.deleted.map { it.lookedUp }.toSet() shouldBe setOf(
            LocalPath.build("/file1.txt"),
            LocalPath.build("/file2.txt")
        )
    }

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

    @Test
    fun `bytes deleted tracked correctly`() = runTest {
        // Given - files with known sizes
        mockOps.addMockFile("/file1.txt", ByteArray(1000))
        mockOps.addMockFile("/file2.txt", ByteArray(2000))

        val targets = listOf(
            LocalPath.build("/file1.txt"),
            LocalPath.build("/file2.txt")
        )

        val progressUpdates = mutableListOf<DeleteAction.State.Active<LocalPath, LocalPathLookup>>()

        // When
        targets.deleteGeneric(
            fileSystemOps = mockOps,
            recursive = true,
            ignoreMissing = false
        ).collect { state ->
            when (state) {
                is DeleteAction.State.Active -> progressUpdates.add(state)
                is DeleteAction.State.Completed -> { /* final result */
                }
            }
        }

        // Then - total bytes should be 3000
        (progressUpdates.isNotEmpty()) shouldBe true
        val finalProgress = progressUpdates.last()
        finalProgress.totalBytes shouldBe 3000L
        finalProgress.deletedBytes shouldBe 3000L
    }

    @Test
    fun `empty collection returns empty result`() = runTest {
        // Given - empty collection
        val targets = emptyList<LocalPath>()

        // When
        val result = targets.deleteGeneric(
            fileSystemOps = mockOps,
            recursive = true,
            ignoreMissing = false
        ).last() as DeleteAction.State.Completed

        // Then - empty result
        result.deleted.size shouldBe 0
        result.skipped.size shouldBe 0
    }

    // ============ EDGE CASES ============

    @Test
    fun `delete collection with duplicates`() = runTest {
        // Given - same file listed twice
        mockOps.addMockFile("/file.txt", "content".toByteArray())

        val targets = listOf(
            LocalPath.build("/file.txt"),
            LocalPath.build("/file.txt") // Duplicate
        )

        // When
        val result = targets.deleteGeneric(
            fileSystemOps = mockOps,
            recursive = true,
            ignoreMissing = true // Ignore missing to handle second attempt
        ).last() as DeleteAction.State.Completed

        // Then - file deleted once
        mockOps.hasFile("/file.txt") shouldBe false
        // Result may contain both (1 deleted, 1 skipped due to missing) or just 1 deleted
        (result.deleted.size + result.skipped.size >= 1) shouldBe true
    }

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

    @Test
    fun `very deep directory structure (50 levels)`() = runTest {
        // Given - 50 levels deep
        var currentPath = "/level0"
        mockOps.addMockDir(currentPath)

        repeat(49) { level ->
            val nextPath = "$currentPath/level${level + 1}"
            mockOps.addMockDir(nextPath)
            currentPath = nextPath
        }
        mockOps.addMockFile("$currentPath/deep.txt", "deep content".toByteArray())

        val sourcePath = LocalPath.build("/level0")

        // When
        val result = sourcePath.deleteGeneric(
            fileSystemOps = mockOps,
            recursive = true,
            ignoreMissing = false
        ).last() as DeleteAction.State.Completed

        // Then - all deleted
        mockOps.hasFile("/level0") shouldBe false
        result.deleted.size shouldBe 51 // 50 directories + 1 file
    }

    @Test
    fun `large number of files (1000+)`() = runTest {
        // Given - 1000 files in a directory
        mockOps.addMockDir("/root")
        repeat(1000) { i ->
            mockOps.addMockFile("/root/file$i.txt", "content$i".toByteArray())
        }

        val sourcePath = LocalPath.build("/root")

        // When
        val result = sourcePath.deleteGeneric(
            fileSystemOps = mockOps,
            recursive = true,
            ignoreMissing = false
        ).last() as DeleteAction.State.Completed

        // Then - all deleted
        mockOps.hasFile("/root") shouldBe false
        result.deleted.size shouldBe 1001 // directory + 1000 files
    }

    // ============ SCAN ERROR HANDLING ============

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

    // ============ NULLABLE FIELDS TESTS ============

    @Test
    fun `generic delete handles null sizes in progress aggregation with 0L fallback`() = runTest {
        // Given - directory tree with items having null sizes (simulates "/" on Android scenario)
        mockOps.addMockDir("/root")
        mockOps.addMockFile("/root/accessible.txt", "content1".toByteArray())
        mockOps.addMockFile("/root/restricted.txt", "content2".toByteArray())

        // Make one file return null size in its lookup (simulates permission error on stat())
        mockOps.setNullSize("/root/restricted.txt")

        val sourcePath = LocalPath.build("/root")

        // When - delete directory tree with mixed null/non-null sizes
        val result = sourcePath.deleteGeneric(
            fileSystemOps = mockOps,
            recursive = true,
            ignoreMissing = false
        ).last() as DeleteAction.State.Completed

        // Then - all files deleted successfully despite null sizes
        mockOps.hasFile("/root") shouldBe false
        mockOps.hasFile("/root/accessible.txt") shouldBe false
        mockOps.hasFile("/root/restricted.txt") shouldBe false

        result.deleted.size shouldBe 3 // directory + 2 files

        // Verify bytesTotal handles null sizes gracefully
        // The `?: 0L` fallback ensures nulls don't crash aggregation
        result.bytesTotal.shouldBeGreaterThanOrEqual(0L)

        // Note: Files with null sizes contribute 0L to totalBytes calculation
        // This prevents NullPointerException in progress tracking
    }
}
