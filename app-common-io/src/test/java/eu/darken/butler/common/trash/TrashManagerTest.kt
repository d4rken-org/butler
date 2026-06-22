package eu.darken.butler.common.trash

import eu.darken.butler.common.files.APathLookup
import eu.darken.butler.common.files.GatewaySwitch
import eu.darken.butler.common.files.LocalPath
import eu.darken.butler.common.files.metadata.Ownership
import eu.darken.butler.common.files.metadata.Permissions
import io.kotest.assertions.throwables.shouldThrow
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import testhelpers.BaseTest

/**
 * Tests for [TrashManager.reapplyOriginalMetadata] - the best-effort reapply of the original
 * Unix ownership/permissions captured at trash time when an item is restored.
 *
 * Verifies that the helper:
 * - Forwards the captured ownership and permissions to the gateway, ownership first
 * - Skips a setter when its captured value is null
 * - Never propagates a `false`/no-op result or an exception from the gateway
 */
class TrashManagerTest : BaseTest() {

    private val gatewaySwitch: GatewaySwitch = mockk(relaxed = true)

    private val targetPath = LocalPath.build("/storage/emulated/0/Documents/report.txt")
    private val ownership = Ownership(userId = 1000L, groupId = 1000L)
    private val permissions = Permissions(mode = 0b110_100_100) // 0644

    private fun create() = TrashManager(
        repository = mockk(relaxed = true),
        storageEnv = mockk(relaxed = true),
        gatewaySwitch = gatewaySwitch,
        settings = mockk(relaxed = true),
        dispatcherProvider = mockk(relaxed = true),
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
}
