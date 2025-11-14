package eu.darken.butler.common.files.operations

import eu.darken.butler.common.files.LocalPath
import eu.darken.butler.common.files.actions.CreateAction
import eu.darken.butler.common.files.actions.PathActionIssue
import eu.darken.butler.common.files.local.LocalPathLookup
import eu.darken.butler.common.files.metadata.FileType
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.last
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import testhelpers.BaseTest
import java.util.concurrent.atomic.AtomicInteger

/**
 * Tests for GenericPathCreate - the high-level create orchestrator.
 *
 * Tests the complete create operation including:
 * - File and directory creation
 * - Conflict detection via lookup
 * - Rename resolution loop
 * - Overwrite via delete
 * - Error handling with retry support
 * - Cancellation scenarios
 *
 * Uses MockFileSystemOps to test without real file system access.
 */
class GenericPathCreateTest : BaseTest() {

    private lateinit var mockOps: MockFileSystemOps<LocalPath, LocalPathLookup>
    private var issueCallCount = 0
    private var issueResolution: PathActionIssue.Resolution? = null

    private val onIssue: suspend (PathActionIssue) -> PathActionIssue.Resolution = { issue ->
        issueCallCount++
        issueResolution ?: throw AssertionError("No resolution provided for issue: $issue")
    }

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
        issueCallCount = 0
        issueResolution = null
    }

    @AfterEach
    fun cleanup() {
        mockOps.clear()
    }

    // ============ BASIC CREATE OPERATIONS ============

    @Test
    fun `create file when path doesn't exist`() = runTest {
        // Given - parent directory exists
        mockOps.addMockDir("/parent")

        val targetPath = LocalPath.build("/parent/newfile.txt")

        // When
        val result = targetPath.createGeneric(
            fileSystemOps = mockOps,
            type = CreateAction.CreateType.FILE,
            onIssue = null
        ).last() as CreateAction.State.Completed

        // Then - file created
        mockOps.hasFile("/parent/newfile.txt") shouldBe true
        mockOps.getFileType("/parent/newfile.txt") shouldBe FileType.FILE
        result.created.fileType shouldBe FileType.FILE
        result.created.lookedUp.path shouldBe "/parent/newfile.txt"
        mockOps.createFileCalls shouldContain "/parent/newfile.txt"
    }

    @Test
    fun `create directory when path doesn't exist`() = runTest {
        // Given - parent directory exists
        mockOps.addMockDir("/parent")

        val targetPath = LocalPath.build("/parent/newdir")

        // When
        val result = targetPath.createGeneric(
            fileSystemOps = mockOps,
            type = CreateAction.CreateType.DIRECTORY,
            onIssue = null
        ).last() as CreateAction.State.Completed

        // Then - directory created
        mockOps.hasFile("/parent/newdir") shouldBe true
        mockOps.getFileType("/parent/newdir") shouldBe FileType.DIRECTORY
        result.created.fileType shouldBe FileType.DIRECTORY
        result.created.lookedUp.path shouldBe "/parent/newdir"
        mockOps.createDirCalls shouldContain "/parent/newdir"
    }

    @Test
    fun `emit active state before creation`() = runTest {
        // Given
        mockOps.addMockDir("/parent")
        val targetPath = LocalPath.build("/parent/newfile.txt")

        // When
        val states = targetPath.createGeneric(
            fileSystemOps = mockOps,
            type = CreateAction.CreateType.FILE,
            onIssue = null
        ).toList()

        // Then - should have Active and Completed states
        states.size shouldBe 2

        val activeState = states[0] as CreateAction.State.Active
        activeState.target.path shouldBe "/parent/newfile.txt"
        activeState.type shouldBe CreateAction.CreateType.FILE

        val completedState = states[1] as CreateAction.State.Completed
        completedState.created.lookedUp.path shouldBe "/parent/newfile.txt"
    }

    // ============ CONFLICT HANDLING ============

    @Test
    fun `handle conflict with rename resolution`() = runTest {
        // Given - file already exists
        mockOps.addMockDir("/parent")
        mockOps.addMockFile("/parent/file.txt", "existing".toByteArray())

        val targetPath = LocalPath.build("/parent/file.txt")

        issueResolution = PathActionIssue.PathAlreadyExists.Resolution.RenameSource(
            newName = "file (1).txt"
        )

        // When
        val result = targetPath.createGeneric(
            fileSystemOps = mockOps,
            type = CreateAction.CreateType.FILE,
            onIssue = onIssue
        ).last() as CreateAction.State.Completed

        // Then - created with new name
        issueCallCount shouldBe 1
        result.created.lookedUp.path shouldBe "/parent/file (1).txt"
        mockOps.hasFile("/parent/file (1).txt") shouldBe true
        mockOps.hasFile("/parent/file.txt") shouldBe true // Original still exists
    }

    @Test
    fun `handle multiple rename conflicts`() = runTest {
        // Given - multiple files exist
        mockOps.addMockDir("/parent")
        mockOps.addMockFile("/parent/file.txt", "v1".toByteArray())
        mockOps.addMockFile("/parent/file (1).txt", "v2".toByteArray())

        val targetPath = LocalPath.build("/parent/file.txt")

        val issueCounter = AtomicInteger(0)
        val issueHandler: suspend (PathActionIssue) -> PathActionIssue.Resolution = { issue ->
            val count = issueCounter.incrementAndGet()
            when (count) {
                1 -> PathActionIssue.PathAlreadyExists.Resolution.RenameSource(newName = "file (1).txt")
                2 -> PathActionIssue.PathAlreadyExists.Resolution.RenameSource(newName = "file (2).txt")
                else -> throw AssertionError("Too many conflicts")
            }
        }

        // When
        val result = targetPath.createGeneric(
            fileSystemOps = mockOps,
            type = CreateAction.CreateType.FILE,
            onIssue = issueHandler
        ).last() as CreateAction.State.Completed

        // Then - created with file (2).txt
        issueCounter.get() shouldBe 2
        result.created.lookedUp.path shouldBe "/parent/file (2).txt"
        mockOps.hasFile("/parent/file (2).txt") shouldBe true
    }

    @Test
    fun `handle conflict with overwrite resolution`() = runTest {
        // Given - file already exists
        mockOps.addMockDir("/parent")
        mockOps.addMockFile("/parent/file.txt", "old content".toByteArray())

        val targetPath = LocalPath.build("/parent/file.txt")

        issueResolution = PathActionIssue.PathAlreadyExists.Resolution.Overwrite()

        // When
        val result = targetPath.createGeneric(
            fileSystemOps = mockOps,
            type = CreateAction.CreateType.FILE,
            onIssue = onIssue
        ).last() as CreateAction.State.Completed

        // Then - existing file deleted and new file created
        issueCallCount shouldBe 1
        result.created.lookedUp.path shouldBe "/parent/file.txt"
        mockOps.hasFile("/parent/file.txt") shouldBe true
        // Content should be empty (newly created)
        mockOps.getFileContent("/parent/file.txt")?.size shouldBe 0
    }

    @Test
    fun `handle conflict with overwrite resolution for directory`() = runTest {
        // Given - directory with content exists
        mockOps.addMockDir("/parent")
        mockOps.addMockDir("/parent/existingdir")
        mockOps.addMockFile("/parent/existingdir/file.txt", "content".toByteArray())

        val targetPath = LocalPath.build("/parent/existingdir")

        issueResolution = PathActionIssue.PathAlreadyExists.Resolution.Overwrite()

        // When
        val result = targetPath.createGeneric(
            fileSystemOps = mockOps,
            type = CreateAction.CreateType.DIRECTORY,
            onIssue = onIssue
        ).last() as CreateAction.State.Completed

        // Then - old directory deleted recursively, new directory created
        issueCallCount shouldBe 1
        result.created.lookedUp.path shouldBe "/parent/existingdir"
        mockOps.hasFile("/parent/existingdir") shouldBe true
        mockOps.hasFile("/parent/existingdir/file.txt") shouldBe false // Child deleted
    }

    @Test
    fun `handle conflict with cancel resolution`() = runTest {
        // Given - file already exists
        mockOps.addMockDir("/parent")
        mockOps.addMockFile("/parent/file.txt", "existing".toByteArray())

        val targetPath = LocalPath.build("/parent/file.txt")

        issueResolution = PathActionIssue.PathAlreadyExists.Resolution.Cancel()

        // When/Then - should throw CancellationException
        assertThrows<CancellationException> {
            targetPath.createGeneric(
                fileSystemOps = mockOps,
                type = CreateAction.CreateType.FILE,
                onIssue = onIssue
            ).last()
        }

        issueCallCount shouldBe 1
        mockOps.hasFile("/parent/file.txt") shouldBe true // Original still exists
    }

    // ============ ERROR HANDLING ============

    @Test
    fun `retry on creation error`() = runTest {
        // Given - parent directory exists
        mockOps.addMockDir("/parent")

        // Make first create fail, second succeed
        mockOps.failCreateFileOnce { RuntimeException("Disk full") }

        val targetPath = LocalPath.build("/parent/file.txt")

        val retryCounter = AtomicInteger(0)
        val issueHandler: suspend (PathActionIssue) -> PathActionIssue.Resolution = { issue ->
            retryCounter.incrementAndGet()
            PathActionIssue.UnknownError.Resolution.Retry
        }

        // When
        val result = targetPath.createGeneric(
            fileSystemOps = mockOps,
            type = CreateAction.CreateType.FILE,
            onIssue = issueHandler
        ).last() as CreateAction.State.Completed

        // Then - retried and succeeded
        retryCounter.get() shouldBe 1
        result.created.lookedUp.path shouldBe "/parent/file.txt"
        mockOps.hasFile("/parent/file.txt") shouldBe true
    }

    @Test
    fun `cancel on creation error`() = runTest {
        // Given - parent directory exists, create will fail
        mockOps.addMockDir("/parent")
        mockOps.failCreateFileOnce { RuntimeException("Permission denied") }

        val targetPath = LocalPath.build("/parent/file.txt")

        issueResolution = PathActionIssue.UnknownError.Resolution.Cancel()

        // When/Then - should throw CancellationException
        assertThrows<CancellationException> {
            targetPath.createGeneric(
                fileSystemOps = mockOps,
                type = CreateAction.CreateType.FILE,
                onIssue = onIssue
            ).last()
        }

        issueCallCount shouldBe 1
        mockOps.hasFile("/parent/file.txt") shouldBe false
    }

    @Test
    fun `throw exception if no issue handler provided and creation fails`() = runTest {
        // Given - parent directory exists, create will fail
        mockOps.addMockDir("/parent")
        mockOps.failCreateFileOnce { RuntimeException("Permission denied") }

        val targetPath = LocalPath.build("/parent/file.txt")

        // When/Then - should re-throw original exception
        assertThrows<RuntimeException> {
            targetPath.createGeneric(
                fileSystemOps = mockOps,
                type = CreateAction.CreateType.FILE,
                onIssue = null
            ).last()
        }

        mockOps.hasFile("/parent/file.txt") shouldBe false
    }

    @Test
    fun `throw exception if no issue handler provided and conflict exists`() = runTest {
        // Given - file already exists
        mockOps.addMockDir("/parent")
        mockOps.addMockFile("/parent/file.txt", "existing".toByteArray())

        val targetPath = LocalPath.build("/parent/file.txt")

        // When/Then - should throw IllegalStateException from issueResolver
        assertThrows<IllegalStateException> {
            targetPath.createGeneric(
                fileSystemOps = mockOps,
                type = CreateAction.CreateType.FILE,
                onIssue = null
            ).last()
        }

        // Original file should still exist
        mockOps.hasFile("/parent/file.txt") shouldBe true
        mockOps.getFileContent("/parent/file.txt") shouldBe "existing".toByteArray()
    }
}
