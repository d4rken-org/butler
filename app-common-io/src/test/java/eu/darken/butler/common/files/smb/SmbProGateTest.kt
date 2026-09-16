package eu.darken.butler.common.files.smb

import eu.darken.butler.common.files.APathGateway
import eu.darken.butler.common.files.LookupOptions
import eu.darken.butler.common.files.SmbPath
import eu.darken.butler.common.files.errors.ReadException
import eu.darken.butler.common.files.smb.credentials.SmbCredentialStore
import eu.darken.butler.common.files.smb.location.SmbLocationManager
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import testhelpers.BaseTest
import testhelpers.coroutine.TestDispatcherProvider
import kotlin.uuid.Uuid

/**
 * Network shares are a Pro feature, gated at the connection pool rather than per gateway method:
 * [SmbGateway] delegates most of [eu.darken.butler.common.files.FileSystemOps] to
 * [SmbFileSystemOps], so a per-override gate would leave browsing, reading and writing open.
 */
class SmbProGateTest : BaseTest() {

    private val locationId = Uuid.parse("11111111-2222-3333-4444-555555555555")
    private val path = SmbPath(locationId, listOf("media"))

    private val locationManager = mockk<SmbLocationManager>()
    private val credentialStore = mockk<SmbCredentialStore>(relaxed = true).apply {
        every { evictions } returns MutableSharedFlow()
    }

    /** Asked for a client means the gate let something through. */
    private val clientFactory = mockk<SmbClientFactory>()

    private fun pool(pro: Boolean) = SmbConnectionPool(
        appScope = TestScope(),
        locationManager = locationManager,
        credentialStore = credentialStore,
        clientFactory = clientFactory,
        upgradeRepo = FakeUpgradeRepo(pro = pro),
    )

    private fun assertNothingWasContacted() {
        verify(exactly = 0) { clientFactory.create() }
        coVerify(exactly = 0) { locationManager.get(any()) }
    }

    /** Delegated to [SmbFileSystemOps], [SmbGateway] has no listing override at all. */
    @Test
    fun `a free user cannot list a share`() = runTest {
        val ops = SmbFileSystemOps(pool(pro = false), TestDispatcherProvider())

        shouldThrow<SmbProRequiredException> { ops.lookupFiles(path, LookupOptions()) }

        assertNothingWasContacted()
    }

    /** Streams lease the connection directly instead of going through the operation wrapper. */
    @Test
    fun `a free user cannot read a file`() = runTest {
        val ops = SmbFileSystemOps(pool(pro = false), TestDispatcherProvider())

        shouldThrow<SmbProRequiredException> { ops.openInputStream(path) }

        assertNothingWasContacted()
    }

    @Test
    fun `a free user cannot write a file`() = runTest {
        val ops = SmbFileSystemOps(pool(pro = false), TestDispatcherProvider())

        shouldThrow<SmbProRequiredException> { ops.openOutputStream(path) }

        assertNothingWasContacted()
    }

    /** An override of [SmbGateway]'s own, built on top of the same primitives. */
    @Test
    fun `a free user cannot walk a share`() = runTest {
        val pool = pool(pro = false)
        val gateway = SmbGateway(
            TestScope(),
            TestDispatcherProvider(),
            SmbFileSystemOps(pool, TestDispatcherProvider()),
            pool,
        )

        val error = shouldThrow<ReadException> {
            gateway.walk(path, LookupOptions(), APathGateway.WalkOptions()).toList()
        }

        error.cause.shouldBeInstanceOf<SmbProRequiredException>()
        assertNothingWasContacted()
    }

    /** Builds its own client, so the pool's gate does not cover the form's connection test. */
    @Test
    fun `a free user cannot test a connection`() = runTest {
        val tester = SmbConnectionTester(clientFactory, FakeUpgradeRepo(pro = false))

        shouldThrow<SmbProRequiredException> {
            tester.test("nas.local", 445, "media", "darken", null, "hunter2".toCharArray())
        }

        verify(exactly = 0) { clientFactory.create() }
    }
}
