package eu.darken.butler.common.trash

import eu.darken.butler.common.files.APath
import eu.darken.butler.common.files.APathLookup
import eu.darken.butler.common.files.GatewaySwitch
import eu.darken.butler.common.files.LocalPath
import eu.darken.butler.common.files.actions.MoveAction
import eu.darken.butler.common.files.local.LocalPathLookup
import eu.darken.butler.common.files.metadata.FileType
import eu.darken.butler.common.files.metadata.Ownership
import eu.darken.butler.common.files.metadata.Permissions
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import testhelpers.BaseTest
import testhelpers.coroutine.TestDispatcherProvider
import kotlin.uuid.Uuid

/**
 * Tests for [TrashManager]'s restore metadata handling.
 *
 * Two layers:
 * - [TrashManager.reapplyOriginalMetadata] in isolation - forwards the captured ownership then
 *   permissions, skips a setter when its value is null, and never propagates a `false`/no-op
 *   result or an exception (but does propagate cancellation).
 * - [TrashManager.restore] integration - the helper is actually invoked after a successful move
 *   and not otherwise, and cancellation propagates out of the restore loop.
 */
class TrashManagerTest : BaseTest() {

    private val gatewaySwitch: GatewaySwitch = mockk(relaxed = true)
    private val repository: TrashRepo = mockk(relaxed = true)

    private val targetPath = LocalPath.build("/storage/emulated/0/Documents/report.txt")
    private val ownership = Ownership(userId = 1000L, groupId = 1000L)
    private val permissions = Permissions(mode = 0b110_100_100) // 0644

    private fun create() = TrashManager(
        repository = repository,
        storageEnv = mockk(relaxed = true),
        gatewaySwitch = gatewaySwitch,
        settings = mockk(relaxed = true),
        dispatcherProvider = TestDispatcherProvider(),
    )

    private fun lookup(
        ownership: Ownership? = this.ownership,
        permissions: Permissions? = this.permissions,
    ): APathLookup<*> = mockk {
        every { this@mockk.ownership } returns ownership
        every { this@mockk.permissions } returns permissions
    }

    @Test
    fun `reapplies ownership then permissions with the captured values`() = runTest {
        coEvery { gatewaySwitch.setOwnership(any(), any()) } returns true
        coEvery { gatewaySwitch.setPermissions(any(), any()) } returns true

        create().reapplyOriginalMetadata(targetPath, lookup())

        coVerifyOrder {
            gatewaySwitch.setOwnership(targetPath, ownership)
            gatewaySwitch.setPermissions(targetPath, permissions)
        }
    }

    @Test
    fun `skips ownership when not captured`() = runTest {
        create().reapplyOriginalMetadata(targetPath, lookup(ownership = null))

        coVerify(exactly = 0) { gatewaySwitch.setOwnership(any(), any()) }
        coVerify(exactly = 1) { gatewaySwitch.setPermissions(targetPath, permissions) }
    }

    @Test
    fun `skips permissions when not captured`() = runTest {
        create().reapplyOriginalMetadata(targetPath, lookup(permissions = null))

        coVerify(exactly = 1) { gatewaySwitch.setOwnership(targetPath, ownership) }
        coVerify(exactly = 0) { gatewaySwitch.setPermissions(any(), any()) }
    }

    @Test
    fun `does nothing when no metadata was captured`() = runTest {
        create().reapplyOriginalMetadata(targetPath, lookup(ownership = null, permissions = null))

        coVerify(exactly = 0) { gatewaySwitch.setOwnership(any(), any()) }
        coVerify(exactly = 0) { gatewaySwitch.setPermissions(any(), any()) }
    }

    @Test
    fun `swallows a false result from the ownership setter and still applies permissions`() = runTest {
        // chown returns false on non-rooted devices; this must not abort the rest of the reapply.
        coEvery { gatewaySwitch.setOwnership(any(), any()) } returns false
        coEvery { gatewaySwitch.setPermissions(any(), any()) } returns true

        create().reapplyOriginalMetadata(targetPath, lookup())

        coVerify(exactly = 1) { gatewaySwitch.setPermissions(targetPath, permissions) }
    }

    @Test
    fun `swallows an exception from the ownership setter and still applies permissions`() = runTest {
        coEvery { gatewaySwitch.setOwnership(any(), any()) } throws RuntimeException("chown failed")
        coEvery { gatewaySwitch.setPermissions(any(), any()) } returns true

        create().reapplyOriginalMetadata(targetPath, lookup())

        coVerify(exactly = 1) { gatewaySwitch.setPermissions(targetPath, permissions) }
    }

    @Test
    fun `swallows an exception from the permissions setter`() = runTest {
        coEvery { gatewaySwitch.setOwnership(any(), any()) } returns true
        coEvery { gatewaySwitch.setPermissions(any(), any()) } throws RuntimeException("chmod failed")

        // Must complete without propagating the exception.
        create().reapplyOriginalMetadata(targetPath, lookup())

        coVerify(exactly = 1) { gatewaySwitch.setOwnership(targetPath, ownership) }
    }

    @Test
    fun `propagates cancellation instead of swallowing it`() = runTest {
        // CancellationException must escape so coroutine cancellation isn't broken by best-effort.
        coEvery { gatewaySwitch.setOwnership(any(), any()) } throws CancellationException("cancelled")

        shouldThrow<CancellationException> {
            create().reapplyOriginalMetadata(targetPath, lookup())
        }

        coVerify(exactly = 0) { gatewaySwitch.setPermissions(any(), any()) }
    }

    // region restore() integration

    private val unknownLookup: APathLookup<APath<*>> = mockk {
        every { fileType } returns FileType.UNKNOWN
    }

    private fun trashItem(): TrashRepo.TrashItem {
        val originalPath = LocalPath.build("data", "local", "tmp", "probe.txt")
        val trashSource = LocalPath.build(
            "storage", "emulated", "0", "Android", "data", "eu.darken.butler", ".trash", "probe.txt_x",
        )
        val originalLookup: APathLookup<*> = mockk {
            every { lookedUp } returns originalPath
            every { ownership } returns this@TrashManagerTest.ownership
            every { permissions } returns this@TrashManagerTest.permissions
        }
        val trashLookup: APathLookup<*> = mockk {
            every { lookedUp } returns trashSource
        }
        return TrashRepo.TrashItem(
            id = Uuid.random(),
            originalLookup = originalLookup,
            trashPath = trashSource,
            trashLookup = trashLookup,
            size = 34L,
        )
    }

    @Suppress("UNCHECKED_CAST")
    private fun completedMoveFlow(): Flow<MoveAction.State<APath<*>, APathLookup<APath<*>>, APath<*>, APathLookup<APath<*>>>> {
        val state = MoveAction.State.Completed<LocalPath, LocalPathLookup, LocalPath, LocalPathLookup>(
            movedFiles = emptySet(),
            bytesMoved = 0L,
        )
        return flowOf(state) as Flow<MoveAction.State<APath<*>, APathLookup<APath<*>>, APath<*>, APathLookup<APath<*>>>>
    }

    @Test
    fun `restore reapplies metadata and clears the item after a successful move`() = runTest {
        val item = trashItem()
        coEvery { gatewaySwitch.lookup(any(), any()) } returns unknownLookup
        coEvery { gatewaySwitch.move(any(), any(), any(), any()) } returns completedMoveFlow()
        coEvery { gatewaySwitch.setOwnership(any(), any()) } returns true
        coEvery { gatewaySwitch.setPermissions(any(), any()) } returns true

        val report = create().restore(listOf(item))

        // Resolve the mock-derived path outside the verify block: calling the lookup getter inside
        // coVerifyOrder would record it into the ordered sequence and break the order check.
        val restoredPath = item.originalPath
        coVerifyOrder {
            gatewaySwitch.setOwnership(restoredPath, ownership)
            gatewaySwitch.setPermissions(restoredPath, permissions)
        }
        coVerify(exactly = 1) { repository.deleteById(item.id) }
        report.restored shouldContain restoredPath
    }

    @Test
    fun `restore does not reapply or clear the item when the move fails`() = runTest {
        val item = trashItem()
        coEvery { gatewaySwitch.lookup(any(), any()) } returns unknownLookup
        coEvery { gatewaySwitch.move(any(), any(), any(), any()) } throws RuntimeException("move failed")

        val report = create().restore(listOf(item))

        coVerify(exactly = 0) { gatewaySwitch.setOwnership(any(), any()) }
        coVerify(exactly = 0) { gatewaySwitch.setPermissions(any(), any()) }
        coVerify(exactly = 0) { repository.deleteById(any()) }
        report.restored.shouldBeEmpty()
        report.failed shouldContain item.trashPath
    }

    @Test
    fun `restore propagates cancellation instead of marking the item failed`() = runTest {
        val item = trashItem()
        coEvery { gatewaySwitch.lookup(any(), any()) } throws CancellationException("cancelled")

        shouldThrow<CancellationException> {
            create().restore(listOf(item))
        }

        coVerify(exactly = 0) { repository.deleteById(any()) }
    }

    // endregion
}
