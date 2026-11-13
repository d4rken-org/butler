package eu.darken.butler.common.files.smb

import eu.darken.butler.common.files.APathGateway
import eu.darken.butler.common.files.LookupOptions
import eu.darken.butler.common.files.SmbPath
import eu.darken.butler.common.files.errors.ReadException
import eu.darken.butler.common.files.smb.credentials.SmbCredentialStore
import eu.darken.butler.common.files.smb.location.SmbLocation
import eu.darken.butler.common.files.smb.location.SmbLocationManager
import eu.darken.smb.SmbShareLister
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.kotest.matchers.types.shouldNotBeInstanceOf
import io.mockk.coEvery
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
import kotlin.time.Instant
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
        verify(exactly = 0) { clientFactory.createLister() }
        coVerify(exactly = 0) { locationManager.get(any()) }
    }

    private val serverLocation = SmbLocation(
        id = locationId,
        label = null,
        host = "nas.local",
        share = null,
        authType = SmbLocation.AuthType.GUEST,
        rememberCredential = false,
        credentialVersion = 1,
        createdAt = Instant.fromEpochMilliseconds(0),
        updatedAt = Instant.fromEpochMilliseconds(0),
    )

    /** The root is recognised from the stored location, the listing itself is a network request. */
    @Test
    fun `a free user cannot list a server's shares`() = runTest {
        coEvery { locationManager.get(locationId) } returns serverLocation
        val ops = SmbFileSystemOps(pool(pro = false), locationManager, TestDispatcherProvider())

        shouldThrow<SmbProRequiredException> { ops.lookupFiles(serverLocation.rootPath, LookupOptions()) }

        verify(exactly = 0) { clientFactory.createLister() }
        verify(exactly = 0) { clientFactory.create() }
    }

    @Test
    fun `a free user cannot delete`() = runTest {
        val ops = SmbFileSystemOps(pool(pro = false), locationManager, TestDispatcherProvider())

        shouldThrow<SmbProRequiredException> { ops.delete(path, recursive = true) }

        assertNothingWasContacted()
    }

    /** Delegated to [SmbFileSystemOps], [SmbGateway] has no listing override at all. */
    @Test
    fun `a free user cannot list a share`() = runTest {
        val ops = SmbFileSystemOps(pool(pro = false), locationManager, TestDispatcherProvider())

        shouldThrow<SmbProRequiredException> { ops.lookupFiles(path, LookupOptions()) }

        assertNothingWasContacted()
    }

    /** Streams lease the connection directly instead of going through the operation wrapper. */
    @Test
    fun `a free user cannot read a file`() = runTest {
        val ops = SmbFileSystemOps(pool(pro = false), locationManager, TestDispatcherProvider())

        shouldThrow<SmbProRequiredException> { ops.openInputStream(path) }

        assertNothingWasContacted()
    }

    @Test
    fun `a free user cannot write a file`() = runTest {
        val ops = SmbFileSystemOps(pool(pro = false), locationManager, TestDispatcherProvider())

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
            SmbFileSystemOps(pool, locationManager, TestDispatcherProvider()),
            pool,
        )

        val error = shouldThrow<ReadException> {
            gateway.walk(path, LookupOptions(), APathGateway.WalkOptions()).toList()
        }

        error.cause.shouldBeInstanceOf<SmbProRequiredException>()
        assertNothingWasContacted()
    }

    /** Checking a share before buying is free; the test connects without asking for the upgrade. */
    @Test
    fun `a connection test reaches the server without the upgrade`() = runTest {
        every { clientFactory.create() } throws IllegalStateException("reached the client")
        val tester = SmbConnectionTester(clientFactory)

        val error = shouldThrow<Exception> {
            tester.test("nas.local", 445, "media", "darken", null, "hunter2".toCharArray())
        }

        error.shouldNotBeInstanceOf<SmbProRequiredException>()
        verify(exactly = 1) { clientFactory.create() }
    }

    @Test
    fun `a server entry's connection test lists the shares without the upgrade`() = runTest {
        var listings = 0
        every { clientFactory.createLister() } returns SmbShareLister { _, _ ->
            listings++
            emptyList()
        }
        val tester = SmbConnectionTester(clientFactory)

        tester.test("nas.local", 445, null, "darken", null, "hunter2".toCharArray())

        listings shouldBe 1
        verify(exactly = 0) { clientFactory.create() }
    }
}
