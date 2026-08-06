package eu.darken.butler.workspace.core.operations

import eu.darken.butler.common.datastore.DataStoreValue
import eu.darken.butler.common.files.APath
import eu.darken.butler.common.files.APathLookup
import eu.darken.butler.common.files.GatewaySwitch
import eu.darken.butler.common.files.LocalPath
import eu.darken.butler.common.files.SAFPath
import eu.darken.butler.common.files.actions.DeleteAction
import eu.darken.butler.common.files.actions.PathActionIssue
import eu.darken.butler.common.files.local.LocalPathLookup
import eu.darken.butler.common.files.metadata.FileType
import eu.darken.butler.common.trash.TrashManager
import eu.darken.butler.common.trash.TrashSettings
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import testhelpers.BaseTest
import kotlin.coroutines.cancellation.CancellationException
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

        // Mock du() for size calculation
        coEvery { gatewaySwitch.du(any<APath<*>>(), any()) } returns 1024L

        // Mock successful trash move
        every { trashManager.moveToTrash(any()) } returns flowOf(
            TrashManager.TrashMoveState.Completed(
                report = TrashManager.TrashMoveReport(
                    movedToTrash = setOf(testLookup),
                    failedToMove = emptySet(),
                    bytesMoved = 1024L,
                )
            )
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
        val completedState = states.last()
        completedState.shouldBeInstanceOf<CoreDeleteExecutor.State.Completed>()

        val result = completedState.result
        result.trashed.shouldHaveSize(1)
        result.skipped.shouldBeEmpty()
        result.bytesFreed shouldBe 1024L

        // Verify onPathsRemoved was called
        coVerify { onPathsRemovedCallback(any()) }

        // Verify trash manager was called
        coVerify { trashManager.moveToTrash(listOf(testPath)) }
    }

    @Test
    fun `execute - prompts user when trash partially fails and user chooses delete permanently`() = runTest {
        // Given
        val testPath = LocalPath.build("/test.txt")
        val testLookup = createTestLookup("/test.txt")

        // Enable trash
        every { trashSettings.enabled.flow } returns flowOf(true)

        // Mock du() for size calculation
        coEvery { gatewaySwitch.du(any<APath<*>>(), any()) } returns 1024L

        // Mock partial failure in trash move
        every { trashManager.moveToTrash(any()) } returns flowOf(
            TrashManager.TrashMoveState.Completed(
                report = TrashManager.TrashMoveReport(
                    movedToTrash = emptySet(),
                    failedToMove = setOf(testLookup),
                    bytesMoved = 0L,
                )
            )
        )

        // Mock direct delete fallback
        @Suppress("UNCHECKED_CAST")
        coEvery { gatewaySwitch.delete(any<Set<APath<*>>>(), any()) } returns flowOf(
            DeleteAction.State.Completed(
                deleted = setOf(testLookup as APathLookup<APath<*>>),
                skipped = emptySet()
            )
        )

        var issueReceived: PathActionIssue? = null
        val config = CoreDeleteExecutor.Config(
            tag = "Test",
            onIssue = { issue ->
                issueReceived = issue
                when (issue) {
                    is PathActionIssue.TrashMoveFailed -> PathActionIssue.TrashMoveFailed.Resolution.DeletePermanently
                    else -> PathActionIssue.UnknownError.Resolution.Skip()
                }
            },
            onPathsRemoved = {},
        )

        // When
        val states = executor.execute(
            targets = setOf(testPath),
            config = config,
        ).toList()

        // Then - should prompt user with TrashMoveFailed issue
        issueReceived.shouldBeInstanceOf<PathActionIssue.TrashMoveFailed>()

        // Should complete with direct delete after user chooses DeletePermanently
        val completedState = states.last()
        completedState.shouldBeInstanceOf<CoreDeleteExecutor.State.Completed>()

        // Verify both trash and direct delete were attempted
        coVerify { trashManager.moveToTrash(any()) }
    }

    @Test
    fun `execute - prompts user when trash partially fails and user chooses skip`() = runTest {
        // Given
        val testPath = LocalPath.build("/test.txt")
        val testLookup = createTestLookup("/test.txt")

        // Enable trash
        every { trashSettings.enabled.flow } returns flowOf(true)

        // Mock du() for size calculation
        coEvery { gatewaySwitch.du(any<APath<*>>(), any()) } returns 1024L

        // Mock partial failure in trash move
        every { trashManager.moveToTrash(any()) } returns flowOf(
            TrashManager.TrashMoveState.Completed(
                report = TrashManager.TrashMoveReport(
                    movedToTrash = emptySet(),
                    failedToMove = setOf(testLookup),
                    bytesMoved = 0L,
                )
            )
        )

        var issueReceived: PathActionIssue? = null
        val config = CoreDeleteExecutor.Config(
            tag = "Test",
            onIssue = { issue ->
                issueReceived = issue
                when (issue) {
                    is PathActionIssue.TrashMoveFailed -> PathActionIssue.TrashMoveFailed.Resolution.Skip
                    else -> PathActionIssue.UnknownError.Resolution.Skip()
                }
            },
            onPathsRemoved = {},
        )

        // When
        val states = executor.execute(
            targets = setOf(testPath),
            config = config,
        ).toList()

        // Then - should prompt user with TrashMoveFailed issue
        issueReceived.shouldBeInstanceOf<PathActionIssue.TrashMoveFailed>()

        // Should complete with skipped items (no direct delete)
        val completedState = states.last()
        completedState.shouldBeInstanceOf<CoreDeleteExecutor.State.Completed>()

        val result = completedState.result
        result.deleted.shouldBeEmpty()
        result.skipped.shouldHaveSize(1)

        // Verify trash was attempted but direct delete was NOT called
        coVerify { trashManager.moveToTrash(any()) }
        coVerify(exactly = 0) { gatewaySwitch.delete(any<Set<APath<*>>>(), any()) }
    }

    @Test
    fun `execute - prompts user when trash throws exception and user chooses delete permanently`() = runTest {
        // Given
        val testPath = LocalPath.build("/test.txt")
        val testLookup = createTestLookup("/test.txt")

        // Enable trash
        every { trashSettings.enabled.flow } returns flowOf(true)

        // Mock du() for size calculation
        coEvery { gatewaySwitch.du(any<APath<*>>(), any()) } returns 1024L

        // Mock lookup for exception handling (when building TrashMoveFailed issue)
        coEvery { gatewaySwitch.lookup(any<APath<*>>(), any()) } returns testLookup as APathLookup<APath<*>>

        // Mock exception in trash move
        every { trashManager.moveToTrash(any()) } returns kotlinx.coroutines.flow.flow<TrashManager.TrashMoveState> {
            throw Exception("Trash error")
        }

        // Mock direct delete fallback
        coEvery { gatewaySwitch.delete(any<Set<APath<*>>>(), any()) } returns flowOf(
            DeleteAction.State.Completed<APath<*>, APathLookup<APath<*>>>(
                deleted = setOf(testLookup),
                skipped = emptySet()
            )
        )

        var issueReceived: PathActionIssue? = null
        val config = CoreDeleteExecutor.Config(
            tag = "Test",
            onIssue = { issue ->
                issueReceived = issue
                when (issue) {
                    is PathActionIssue.TrashMoveFailed -> PathActionIssue.TrashMoveFailed.Resolution.DeletePermanently
                    else -> PathActionIssue.UnknownError.Resolution.Skip()
                }
            },
            onPathsRemoved = {},
        )

        // When
        val states = executor.execute(
            targets = setOf(testPath),
            config = config,
        ).toList()

        // Then - should prompt user with TrashMoveFailed issue
        issueReceived.shouldBeInstanceOf<PathActionIssue.TrashMoveFailed>()
        (issueReceived as PathActionIssue.TrashMoveFailed).exception.shouldBeInstanceOf<Exception>()

        // Should complete with direct delete after exception
        val completedState = states.last()
        completedState.shouldBeInstanceOf<CoreDeleteExecutor.State.Completed>()

        // Verify trash was attempted despite exception
        coVerify { trashManager.moveToTrash(any()) }
    }

    @Test
    fun `execute - prompts user when total size exceeds trash limit`() = runTest {
        // Given
        val testPath1 = LocalPath.build("/test1.txt")
        val testPath2 = LocalPath.build("/test2.txt")
        val testLookup1 = createTestLookup("/test1.txt", size = 600 * 1048576L) // 600 MB
        val testLookup2 = createTestLookup("/test2.txt", size = 600 * 1048576L) // 600 MB
        // Total: 1200 MB, exceeds default max of 1000 MB

        // Enable trash
        every { trashSettings.enabled.flow } returns flowOf(true)

        // Mock du() to return sizes for each path
        coEvery { gatewaySwitch.du(testPath1, any()) } returns 600 * 1048576L
        coEvery { gatewaySwitch.du(testPath2, any()) } returns 600 * 1048576L

        // Mock direct delete
        @Suppress("UNCHECKED_CAST")
        coEvery { gatewaySwitch.delete(any<Set<APath<*>>>(), any()) } returns flowOf(
            DeleteAction.State.Completed(
                deleted = setOf(testLookup1 as APathLookup<APath<*>>, testLookup2 as APathLookup<APath<*>>),
                skipped = emptySet()
            )
        )

        var issueReceived: PathActionIssue? = null
        val config = CoreDeleteExecutor.Config(
            tag = "Test",
            onIssue = { issue ->
                issueReceived = issue
                when (issue) {
                    is PathActionIssue.TrashSizeLimitExceeded -> PathActionIssue.TrashSizeLimitExceeded.Resolution.DeletePermanently
                    else -> PathActionIssue.UnknownError.Resolution.Skip()
                }
            },
            onPathsRemoved = {},
        )

        // When
        val states = executor.execute(
            targets = setOf(testPath1, testPath2),
            config = config,
        ).toList()

        // Then - should prompt user with TrashSizeLimitExceeded issue
        issueReceived.shouldBeInstanceOf<PathActionIssue.TrashSizeLimitExceeded>()
        val sizeIssue = issueReceived as PathActionIssue.TrashSizeLimitExceeded
        sizeIssue.itemCount shouldBe 2
        sizeIssue.totalSize shouldBe 1200 * 1048576L

        // Should complete with direct delete after user chooses DeletePermanently
        val completedState = states.last()
        completedState.shouldBeInstanceOf<CoreDeleteExecutor.State.Completed>()

        // Verify trash manager was NOT called (user chose delete permanently)
        coVerify(exactly = 0) { trashManager.moveToTrash(any()) }
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
        // Given - Using a path type the trash can't hold
        val customPath = safPath("saf.txt")
        val customLookup = safLookup(customPath)

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
            onIssue = { PathActionIssue.TrashNotSupported.Resolution.DeletePermanently },
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

    // ==================== Trash capability partitioning ====================

    private fun safPath(name: String) = SAFPath.build(
        "content://com.android.externalstorage.documents/tree/primary%3ADownload",
        name,
    )

    private fun safLookup(path: SAFPath): APathLookup<APath<*>> = mockk {
        every { lookedUp } returns path
        every { fileType } returns FileType.FILE
        every { size } returns 512L
    }

    @Test
    fun `execute - local only selection never raises TrashNotSupported`() = runTest {
        val localPath = LocalPath.build("/test.txt")
        val localLookup = createTestLookup("/test.txt")

        every { trashSettings.enabled.flow } returns flowOf(true)
        coEvery { gatewaySwitch.du(any<APath<*>>(), any()) } returns 1024L
        every { trashManager.moveToTrash(any()) } returns flowOf(
            TrashManager.TrashMoveState.Completed(
                report = TrashManager.TrashMoveReport(
                    movedToTrash = setOf(localLookup),
                    failedToMove = emptySet(),
                    bytesMoved = 1024L,
                )
            )
        )

        val issues = mutableListOf<PathActionIssue>()
        val config = CoreDeleteExecutor.Config(
            tag = "Test",
            onIssue = { issue ->
                issues.add(issue)
                PathActionIssue.UnknownError.Resolution.Skip()
            },
            onPathsRemoved = {},
        )

        executor.execute(targets = setOf(localPath), config = config).toList()

        issues.filterIsInstance<PathActionIssue.TrashNotSupported>().shouldBeEmpty()
    }

    @Test
    fun `execute - mixed selection raises TrashNotSupported for the untrashable part only`() = runTest {
        val localPath = LocalPath.build("/local.txt")
        val localLookup = createTestLookup("/local.txt")
        val safFile = safPath("saf.txt")
        val safFileLookup = safLookup(safFile)

        every { trashSettings.enabled.flow } returns flowOf(true)
        coEvery { gatewaySwitch.du(localPath, any()) } returns 1024L
        coEvery { gatewaySwitch.lookup(safFile, any()) } returns safFileLookup
        every { trashManager.moveToTrash(any()) } returns flowOf(
            TrashManager.TrashMoveState.Completed(
                report = TrashManager.TrashMoveReport(
                    movedToTrash = setOf(localLookup),
                    failedToMove = emptySet(),
                    bytesMoved = 1024L,
                )
            )
        )
        val deletedTargets = slot<Set<APath<*>>>()
        coEvery { gatewaySwitch.delete(capture(deletedTargets), any()) } returns flowOf(
            DeleteAction.State.Completed<APath<*>, APathLookup<APath<*>>>(
                deleted = setOf(safFileLookup),
                skipped = emptySet(),
            )
        )

        val removedPaths = mutableListOf<Set<APathLookup<*>>>()
        var issueReceived: PathActionIssue? = null
        val config = CoreDeleteExecutor.Config(
            tag = "Test",
            onIssue = { issue ->
                issueReceived = issue
                PathActionIssue.TrashNotSupported.Resolution.DeletePermanently
            },
            onPathsRemoved = { removedPaths.add(it) },
        )

        val states = executor.execute(targets = setOf(localPath, safFile), config = config).toList()

        issueReceived.shouldBeInstanceOf<PathActionIssue.TrashNotSupported>()
        (issueReceived as PathActionIssue.TrashNotSupported).untrashableItems shouldBe listOf(safFileLookup)

        // Only the trashable subset is sized and trashed, only the untrashable one is deleted
        coVerify(exactly = 0) { gatewaySwitch.du(safFile, any()) }
        coVerify { trashManager.moveToTrash(listOf(localPath)) }
        deletedTargets.captured shouldBe setOf(safFile)

        removedPaths shouldBe listOf(setOf(localLookup), setOf(safFileLookup))

        val result = (states.last() as CoreDeleteExecutor.State.Completed).result
        result.trashed shouldBe setOf(localLookup)
        result.deleted shouldBe setOf(safFileLookup)
    }

    @Test
    fun `execute - skipping the untrashable part still trashes the local items`() = runTest {
        val localPath = LocalPath.build("/local.txt")
        val localLookup = createTestLookup("/local.txt")
        val safFile = safPath("saf.txt")
        val safFileLookup = safLookup(safFile)

        every { trashSettings.enabled.flow } returns flowOf(true)
        coEvery { gatewaySwitch.du(localPath, any()) } returns 1024L
        coEvery { gatewaySwitch.lookup(safFile, any()) } returns safFileLookup
        every { trashManager.moveToTrash(any()) } returns flowOf(
            TrashManager.TrashMoveState.Completed(
                report = TrashManager.TrashMoveReport(
                    movedToTrash = setOf(localLookup),
                    failedToMove = emptySet(),
                    bytesMoved = 1024L,
                )
            )
        )

        val removedPaths = mutableListOf<Set<APathLookup<*>>>()
        val config = CoreDeleteExecutor.Config(
            tag = "Test",
            onIssue = { PathActionIssue.TrashNotSupported.Resolution.Skip },
            onPathsRemoved = { removedPaths.add(it) },
        )

        val states = executor.execute(targets = setOf(localPath, safFile), config = config).toList()

        coVerify { trashManager.moveToTrash(listOf(localPath)) }
        coVerify(exactly = 0) { gatewaySwitch.delete(any<Set<APath<*>>>(), any()) }
        removedPaths shouldBe listOf(setOf(localLookup))

        val result = (states.last() as CoreDeleteExecutor.State.Completed).result
        result.trashed shouldBe setOf(localLookup)
        result.skipped shouldBe setOf(safFileLookup)
        result.deleted.shouldBeEmpty()
    }

    @Test
    fun `execute - cancelling the untrashable prompt leaves everything untouched`() = runTest {
        val localPath = LocalPath.build("/local.txt")
        val safFile = safPath("saf.txt")
        val safFileLookup = safLookup(safFile)

        every { trashSettings.enabled.flow } returns flowOf(true)
        coEvery { gatewaySwitch.du(any<APath<*>>(), any()) } returns 1024L
        coEvery { gatewaySwitch.lookup(safFile, any()) } returns safFileLookup

        val removedPaths = mutableListOf<Set<APathLookup<*>>>()
        val config = CoreDeleteExecutor.Config(
            tag = "Test",
            onIssue = { PathActionIssue.TrashNotSupported.Resolution.Cancel() },
            onPathsRemoved = { removedPaths.add(it) },
        )

        shouldThrow<CancellationException> {
            executor.execute(targets = setOf(localPath, safFile), config = config).toList()
        }

        coVerify(exactly = 0) { trashManager.moveToTrash(any()) }
        coVerify(exactly = 0) { gatewaySwitch.delete(any<Set<APath<*>>>(), any()) }
        removedPaths.shouldBeEmpty()
    }

    @Test
    fun `execute - all untrashable selection does not size or touch the trash`() = runTest {
        val safFile = safPath("saf.txt")
        val safFileLookup = safLookup(safFile)

        every { trashSettings.enabled.flow } returns flowOf(true)
        coEvery { gatewaySwitch.lookup(safFile, any()) } returns safFileLookup
        val deletedTargets = slot<Set<APath<*>>>()
        coEvery { gatewaySwitch.delete(capture(deletedTargets), any()) } returns flowOf(
            DeleteAction.State.Completed<APath<*>, APathLookup<APath<*>>>(
                deleted = setOf(safFileLookup),
                skipped = emptySet(),
            )
        )

        val config = CoreDeleteExecutor.Config(
            tag = "Test",
            onIssue = { PathActionIssue.TrashNotSupported.Resolution.DeletePermanently },
            onPathsRemoved = {},
        )

        executor.execute(targets = setOf(safFile), config = config).toList()

        coVerify(exactly = 0) { gatewaySwitch.du(any<APath<*>>(), any()) }
        coVerify(exactly = 0) { trashManager.moveToTrash(any()) }
        deletedTargets.captured shouldBe setOf(safFile)
    }

    @Test
    fun `execute - a size-limit escalation cannot resurrect skipped untrashable items`() = runTest {
        val localPath1 = LocalPath.build("/big1.txt")
        val localPath2 = LocalPath.build("/big2.txt")
        val localLookup1 = createTestLookup("/big1.txt", size = 600 * 1048576L)
        val localLookup2 = createTestLookup("/big2.txt", size = 600 * 1048576L)
        val safFile = safPath("saf.txt")
        val safFileLookup = safLookup(safFile)

        every { trashSettings.enabled.flow } returns flowOf(true)
        coEvery { gatewaySwitch.du(localPath1, any()) } returns 600 * 1048576L
        coEvery { gatewaySwitch.du(localPath2, any()) } returns 600 * 1048576L
        coEvery { gatewaySwitch.lookup(safFile, any()) } returns safFileLookup
        val deletedTargets = slot<Set<APath<*>>>()

        @Suppress("UNCHECKED_CAST")
        coEvery { gatewaySwitch.delete(capture(deletedTargets), any()) } returns flowOf(
            DeleteAction.State.Completed(
                deleted = setOf(localLookup1 as APathLookup<APath<*>>, localLookup2 as APathLookup<APath<*>>),
                skipped = emptySet(),
            )
        )

        var sizeIssue: PathActionIssue.TrashSizeLimitExceeded? = null
        val config = CoreDeleteExecutor.Config(
            tag = "Test",
            onIssue = { issue ->
                when (issue) {
                    is PathActionIssue.TrashNotSupported -> PathActionIssue.TrashNotSupported.Resolution.Skip
                    is PathActionIssue.TrashSizeLimitExceeded -> {
                        sizeIssue = issue
                        PathActionIssue.TrashSizeLimitExceeded.Resolution.DeletePermanently
                    }
                    else -> PathActionIssue.UnknownError.Resolution.Skip()
                }
            },
            onPathsRemoved = {},
        )

        executor.execute(targets = setOf(localPath1, localPath2, safFile), config = config).toList()

        // The size prompt only ever covers the trashable subset
        sizeIssue!!.itemCount shouldBe 2
        sizeIssue!!.totalSize shouldBe 1200 * 1048576L
        deletedTargets.captured shouldBe setOf(localPath1, localPath2)
    }

    @Test
    fun `execute - a trash exception cannot resurrect skipped untrashable items`() = runTest {
        val localPath = LocalPath.build("/local.txt")
        val localLookup = createTestLookup("/local.txt")
        val safFile = safPath("saf.txt")
        val safFileLookup = safLookup(safFile)

        every { trashSettings.enabled.flow } returns flowOf(true)
        coEvery { gatewaySwitch.du(localPath, any()) } returns 1024L
        coEvery { gatewaySwitch.lookup(safFile, any()) } returns safFileLookup

        @Suppress("UNCHECKED_CAST")
        coEvery { gatewaySwitch.lookup(localPath, any()) } returns localLookup as APathLookup<APath<*>>
        every { trashManager.moveToTrash(any()) } returns kotlinx.coroutines.flow.flow {
            throw Exception("Trash error")
        }
        val deletedTargets = slot<Set<APath<*>>>()
        coEvery { gatewaySwitch.delete(capture(deletedTargets), any()) } returns flowOf(
            DeleteAction.State.Completed<APath<*>, APathLookup<APath<*>>>(
                deleted = setOf(localLookup),
                skipped = emptySet(),
            )
        )

        val config = CoreDeleteExecutor.Config(
            tag = "Test",
            onIssue = { issue ->
                when (issue) {
                    is PathActionIssue.TrashNotSupported -> PathActionIssue.TrashNotSupported.Resolution.Skip
                    is PathActionIssue.TrashMoveFailed -> PathActionIssue.TrashMoveFailed.Resolution.DeletePermanently
                    else -> PathActionIssue.UnknownError.Resolution.Skip()
                }
            },
            onPathsRemoved = {},
        )

        executor.execute(targets = setOf(localPath, safFile), config = config).toList()

        deletedTargets.captured shouldBe setOf(localPath)
    }

    @Test
    fun `execute - cancelling a failed trash move propagates instead of re-prompting`() = runTest {
        val localPath = LocalPath.build("/local.txt")
        val localLookup = createTestLookup("/local.txt")

        every { trashSettings.enabled.flow } returns flowOf(true)
        coEvery { gatewaySwitch.du(any<APath<*>>(), any()) } returns 1024L
        every { trashManager.moveToTrash(any()) } returns flowOf(
            TrashManager.TrashMoveState.Completed(
                report = TrashManager.TrashMoveReport(
                    movedToTrash = emptySet(),
                    failedToMove = setOf(localLookup),
                    bytesMoved = 0L,
                )
            )
        )

        var issueCount = 0
        val config = CoreDeleteExecutor.Config(
            tag = "Test",
            onIssue = {
                issueCount++
                PathActionIssue.TrashMoveFailed.Resolution.Cancel()
            },
            onPathsRemoved = {},
        )

        shouldThrow<CancellationException> {
            executor.execute(targets = setOf(localPath), config = config).toList()
        }

        issueCount shouldBe 1
        coVerify(exactly = 0) { gatewaySwitch.delete(any<Set<APath<*>>>(), any()) }
    }
}
