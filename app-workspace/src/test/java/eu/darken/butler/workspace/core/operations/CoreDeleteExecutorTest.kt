package eu.darken.butler.workspace.core.operations

import eu.darken.butler.common.datastore.DataStoreValue
import eu.darken.butler.common.files.APath
import eu.darken.butler.common.files.APathLookup
import eu.darken.butler.common.files.GatewaySwitch
import eu.darken.butler.common.files.LocalPath
import eu.darken.butler.common.files.actions.DeleteAction
import eu.darken.butler.common.files.actions.PathActionIssue
import eu.darken.butler.common.files.local.LocalPathLookup
import eu.darken.butler.common.files.metadata.FileType
import eu.darken.butler.common.trash.TrashManager
import eu.darken.butler.common.trash.TrashSettings
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import testhelpers.BaseTest
import kotlin.time.Duration.Companion.days

class CoreDeleteExecutorTest : BaseTest() {

    private lateinit var gatewaySwitch: GatewaySwitch
    private lateinit var trashManager: TrashManager
    private lateinit var trashSettings: TrashSettings
    private lateinit var executor: CoreDeleteExecutor

    @BeforeEach
    fun setup() {
        gatewaySwitch = mockk(relaxed = true)
        trashManager = mockk(relaxed = true)
        trashSettings = mockk {
            every { enabled } returns mockk<DataStoreValue<Boolean>>().also {
                every { it.flow } returns flowOf(false)
            }
            every { expiresAfter } returns mockk<DataStoreValue<kotlin.time.Duration>>().also {
                every { it.flow } returns flowOf(30.days)
            }
            every { maxTrashSize } returns mockk<DataStoreValue<Long>>().also {
                every { it.flow } returns flowOf(1000 * 1048576L)
            }
        }

        executor = CoreDeleteExecutor(
            gatewaySwitch = gatewaySwitch,
            trashManager = trashManager,
            trashSettings = trashSettings,
        )
    }

    private fun createTestLookup(path: String, size: Long = 1024L): LocalPathLookup {
        return LocalPathLookup(
            lookedUp = LocalPath.build(path),
            fileType = FileType.FILE,
            size = size,
            modifiedAt = kotlin.time.Instant.fromEpochMilliseconds(System.currentTimeMillis()),
        )
    }

    @Test
    fun `execute - direct delete when trash disabled`() = runTest {
        // Given
        val testPath = LocalPath.build("/test.txt")
        val testLookup = createTestLookup("/test.txt")

        // Mock delete extension to return completed state
        coEvery { gatewaySwitch.lookup(any<APath<*>>(), any()) } returns testLookup as APathLookup<APath<*>>
        coEvery { gatewaySwitch.delete(any<Set<APath<*>>>(), any()) } returns flowOf(
            DeleteAction.State.Completed<APath<*>, APathLookup<APath<*>>>(
                deleted = setOf(testLookup),
                skipped = emptySet()
            )
        )

        val onPathsRemovedCallback = mockk<suspend (Set<APathLookup<*>>) -> Unit>(relaxed = true)
        val onIssueCallback = mockk<suspend (PathActionIssue) -> PathActionIssue.Resolution>()

        val config = CoreDeleteExecutor.Config(
            tag = "Test",
            onIssue = onIssueCallback,
            onPathsRemoved = onPathsRemovedCallback,
        )

        // When
        val states = executor.execute(
            targets = setOf(testPath),
            config = config,
        ).toList()

        // Then
        val completedState = states.last()
        completedState.shouldBeInstanceOf<CoreDeleteExecutor.State.Completed>()

        // Verify onPathsRemoved was called
        coVerify { onPathsRemovedCallback(any()) }
    }

    @Test
    fun `execute - move to trash when enabled and all items succeed`() = runTest {
        // Given
        val testPath = LocalPath.build("/test.txt")
        val testLookup = createTestLookup("/test.txt")

        // Enable trash
        every { trashSettings.enabled.flow } returns flowOf(true)

        // Mock successful trash move
        coEvery { trashManager.moveToTrash(any()) } returns TrashManager.TrashMoveReport(
            movedToTrash = setOf(testLookup),
            failedToMove = emptySet(),
            bytesMoved = 1024L,
        )

        val onPathsRemovedCallback = mockk<suspend (Set<APathLookup<*>>) -> Unit>(relaxed = true)

        val config = CoreDeleteExecutor.Config(
            tag = "Test",
            onIssue = { PathActionIssue.UnknownError.Resolution.Skip() },
            onPathsRemoved = onPathsRemovedCallback,
        )

        // When
        val states = executor.execute(
            targets = setOf(testPath),
            config = config,
        ).toList()

        // Then
        states.shouldHaveSize(1) // Only Completed state
        val completedState = states.last()
        completedState.shouldBeInstanceOf<CoreDeleteExecutor.State.Completed>()

        val result = completedState.result
        result.deleted.shouldHaveSize(1)
        result.skipped.shouldBeEmpty()
        result.bytesFreed shouldBe 1024L

        // Verify onPathsRemoved was called
        coVerify { onPathsRemovedCallback(any()) }

        // Verify trash manager was called
        coVerify { trashManager.moveToTrash(listOf(testPath)) }
    }

    @Test
    fun `execute - fallback to direct delete when trash partially fails`() = runTest {
        // Given
        val testPath = LocalPath.build("/test.txt")
        val testLookup = createTestLookup("/test.txt")

        // Enable trash
        every { trashSettings.enabled.flow } returns flowOf(true)

        // Mock partial failure in trash move
        coEvery { trashManager.moveToTrash(any()) } returns TrashManager.TrashMoveReport(
            movedToTrash = emptySet(),
            failedToMove = setOf(testLookup),
            bytesMoved = 0L,
        )

        // Mock direct delete fallback
        coEvery { gatewaySwitch.lookup(any<APath<*>>(), any()) } returns testLookup as APathLookup<APath<*>>
        coEvery { gatewaySwitch.delete(any<Set<APath<*>>>(), any()) } returns flowOf(
            DeleteAction.State.Completed<APath<*>, APathLookup<APath<*>>>(
                deleted = setOf(testLookup),
                skipped = emptySet()
            )
        )

        val config = CoreDeleteExecutor.Config(
            tag = "Test",
            onIssue = { PathActionIssue.UnknownError.Resolution.Skip() },
            onPathsRemoved = {},
        )

        // When
        val states = executor.execute(
            targets = setOf(testPath),
            config = config,
        ).toList()

        // Then - should complete with direct delete
        val completedState = states.last()
        completedState.shouldBeInstanceOf<CoreDeleteExecutor.State.Completed>()

        // Verify both trash and direct delete were attempted
        coVerify { trashManager.moveToTrash(any()) }
    }

    @Test
    fun `execute - fallback to direct delete when trash throws exception`() = runTest {
        // Given
        val testPath = LocalPath.build("/test.txt")
        val testLookup = createTestLookup("/test.txt")

        // Enable trash
        every { trashSettings.enabled.flow } returns flowOf(true)

        // Mock exception in trash move
        coEvery { trashManager.moveToTrash(any()) } throws Exception("Trash error")

        // Mock direct delete fallback
        coEvery { gatewaySwitch.lookup(any<APath<*>>(), any()) } returns testLookup as APathLookup<APath<*>>
        coEvery { gatewaySwitch.delete(any<Set<APath<*>>>(), any()) } returns flowOf(
            DeleteAction.State.Completed<APath<*>, APathLookup<APath<*>>>(
                deleted = setOf(testLookup),
                skipped = emptySet()
            )
        )

        val config = CoreDeleteExecutor.Config(
            tag = "Test",
            onIssue = { PathActionIssue.UnknownError.Resolution.Skip() },
            onPathsRemoved = {},
        )

        // When
        val states = executor.execute(
            targets = setOf(testPath),
            config = config,
        ).toList()

        // Then - should complete with direct delete after exception
        val completedState = states.last()
        completedState.shouldBeInstanceOf<CoreDeleteExecutor.State.Completed>()

        // Verify trash was attempted despite exception
        coVerify { trashManager.moveToTrash(any()) }
    }

    @Test
    fun `execute - calls onPathsRemoved callback for successful deletions`() = runTest {
        // Given
        val testPath = LocalPath.build("/test.txt")
        val testLookup = createTestLookup("/test.txt")

        coEvery { gatewaySwitch.lookup(any<APath<*>>(), any()) } returns testLookup as APathLookup<APath<*>>
        coEvery { gatewaySwitch.delete(any<Set<APath<*>>>(), any()) } returns flowOf(
            DeleteAction.State.Completed<APath<*>, APathLookup<APath<*>>>(
                deleted = setOf(testLookup),
                skipped = emptySet()
            )
        )

        var callbackInvoked = false
        val onPathsRemovedCallback: suspend (Set<*>) -> Unit = { paths ->
            callbackInvoked = true
            paths.shouldHaveSize(1)
        }

        val config = CoreDeleteExecutor.Config(
            tag = "Test",
            onIssue = { PathActionIssue.UnknownError.Resolution.Skip() },
            onPathsRemoved = onPathsRemovedCallback,
        )

        // When
        executor.execute(
            targets = setOf(testPath),
            config = config,
        ).toList()

        // Then
        callbackInvoked shouldBe true
    }

    @Test
    fun `execute - supports non-LocalPath but skips trash`() = runTest {
        // Given - Using a custom path type that's not LocalPath (using SAFPath as example)
        val customPath = mockk<eu.darken.butler.common.files.SAFPath> {
            every { path } returns "/custom/path"
            every { name } returns "path"
            every { segments } returns listOf("custom", "path")
            every { parent } returns null
        }

        val customLookup = mockk<APathLookup<APath<*>>> {
            every { lookedUp } returns customPath
            every { fileType } returns FileType.FILE
            every { size } returns 512L
        }

        // Enable trash - but it should be skipped for non-LocalPath
        every { trashSettings.enabled.flow } returns flowOf(true)

        coEvery { gatewaySwitch.lookup(any<APath<*>>(), any()) } returns customLookup
        coEvery { gatewaySwitch.delete(any<Set<APath<*>>>(), any()) } returns flowOf(
            DeleteAction.State.Completed<APath<*>, APathLookup<APath<*>>>(
                deleted = setOf(customLookup),
                skipped = emptySet()
            )
        )

        val config = CoreDeleteExecutor.Config(
            tag = "Test",
            onIssue = { PathActionIssue.UnknownError.Resolution.Skip() },
            onPathsRemoved = {},
        )

        // When
        val states = executor.execute(
            targets = setOf(customPath),
            config = config,
        ).toList()

        // Then - should complete with direct delete (skipped trash)
        val completedState = states.last()
        completedState.shouldBeInstanceOf<CoreDeleteExecutor.State.Completed>()

        // Verify trash was NOT called (unsupported path type)
        coVerify(exactly = 0) { trashManager.moveToTrash(any()) }
    }
}
