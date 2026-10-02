package eu.darken.butler.common.files.sftp

import eu.darken.butler.common.files.APathGateway
import eu.darken.butler.common.files.LookupOptions
import eu.darken.butler.common.files.SftpPath
import eu.darken.butler.common.files.errors.ReadException
import eu.darken.butler.common.files.sftp.credentials.SftpCredentialStore
import eu.darken.butler.common.files.sftp.location.SftpLocation
import eu.darken.butler.common.files.sftp.location.SftpLocationManager
import eu.darken.butler.common.files.smb.FakeUpgradeRepo
import eu.darken.ssh.HostKeyPolicy
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.types.shouldBeInstanceOf
import io.kotest.matchers.types.shouldNotBeInstanceOf
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
 * Browsing SFTP servers is a Pro feature, gated at the connection pool rather than per gateway
 * method: [SftpGateway] delegates most of [eu.darken.butler.common.files.FileSystemOps] to
 * [SftpFileSystemOps], so a per-override gate would leave browsing, reading and writing open.
 */
class SftpProGateTest : BaseTest() {

    private val locationId = Uuid.parse("11111111-2222-3333-4444-555555555555")
    private val path = SftpPath(locationId, listOf("media"))

    private val locationManager = mockk<SftpLocationManager>()
    private val credentialStore = mockk<SftpCredentialStore>(relaxed = true).apply {
        every { evictions } returns MutableSharedFlow()
    }

    /** Asked for a client means the gate let something through. */
    private val clientFactory = mockk<SftpClientFactory>()

    private fun pool(pro: Boolean) = SftpConnectionPool(
        appScope = TestScope(),
        locationManager = locationManager,
        credentialStore = credentialStore,
        clientFactory = clientFactory,
        upgradeRepo = FakeUpgradeRepo(pro = pro),
    )

    private fun assertNothingWasContacted() {
        verify(exactly = 0) { clientFactory.create() }
        coVerify(exactly = 0) { locationManager.get(any()) }
        coVerify(exactly = 0) { credentialStore.resolve(any()) }
    }

    @Test
    fun `a free user cannot list a server`() = runTest {
        val ops = SftpFileSystemOps(pool(pro = false), TestDispatcherProvider())

        shouldThrow<SftpProRequiredException> { ops.lookupFiles(path, LookupOptions()) }

        assertNothingWasContacted()
    }

    /** Streams and handles lease the connection directly instead of going through the operation wrapper. */
    @Test
    fun `a free user cannot read or write a file`() = runTest {
        val ops = SftpFileSystemOps(pool(pro = false), TestDispatcherProvider())

        shouldThrow<SftpProRequiredException> { ops.openInputStream(path) }
        shouldThrow<SftpProRequiredException> { ops.openOutputStream(path) }
        shouldThrow<SftpProRequiredException> { ops.file(path, readWrite = true) }

        assertNothingWasContacted()
    }

    @Test
    fun `a free user cannot change anything`() = runTest {
        val ops = SftpFileSystemOps(pool(pro = false), TestDispatcherProvider())

        shouldThrow<SftpProRequiredException> { ops.createDir(path) }
        shouldThrow<SftpProRequiredException> { ops.delete(path) }
        shouldThrow<SftpProRequiredException> { ops.move(path, path.child("b")) }

        assertNothingWasContacted()
    }

    /** An override of [SftpGateway]'s own, built on top of the same primitives. */
    @Test
    fun `a free user cannot walk a server`() = runTest {
        val pool = pool(pro = false)
        val gateway = SftpGateway(TestScope(), TestDispatcherProvider(), SftpFileSystemOps(pool, TestDispatcherProvider()), pool)

        val error = shouldThrow<ReadException> {
            gateway.walk(path, LookupOptions(), APathGateway.WalkOptions()).toList()
        }

        error.cause.shouldBeInstanceOf<SftpProRequiredException>()
        assertNothingWasContacted()
    }

    /** Checking a server before buying is free; the test connects without asking for the upgrade. */
    @Test
    fun `a connection test reaches the server without the upgrade`() = runTest {
        every { clientFactory.create() } throws IllegalStateException("reached the client")
        val tester = SftpConnectionTester(clientFactory)

        val error = shouldThrow<Exception> {
            tester.test(
                host = "nas.local",
                port = 22,
                username = "darken",
                authType = SftpLocation.AuthType.PASSWORD,
                password = "hunter2".toCharArray(),
                privateKey = null,
                passphrase = null,
                basePath = "",
                hostKeyPolicy = HostKeyPolicy.Unknown,
            )
        }

        error.shouldNotBeInstanceOf<SftpProRequiredException>()
        verify(exactly = 1) { clientFactory.create() }
    }
}
