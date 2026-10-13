package eu.darken.butler.common.files.webdav

import eu.darken.butler.common.files.APathGateway
import eu.darken.butler.common.files.Existence
import eu.darken.butler.common.files.LookupOptions
import eu.darken.butler.common.files.WebDavPath
import eu.darken.butler.common.files.errors.ReadException
import eu.darken.butler.common.files.smb.FakeUpgradeRepo
import eu.darken.butler.common.files.webdav.credentials.WebDavCredentialStore
import eu.darken.butler.common.files.webdav.location.WebDavLocationManager
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
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
import org.junit.jupiter.api.io.TempDir
import testhelpers.BaseTest
import testhelpers.coroutine.TestDispatcherProvider
import java.io.File
import kotlin.uuid.Uuid

/**
 * Browsing WebDAV servers is a Pro feature, gated at the client pool rather than per gateway method:
 * [WebDavGateway] delegates most of [eu.darken.butler.common.files.FileSystemOps] to
 * [WebDavFileSystemOps], so a per-override gate would leave browsing, reading and writing open.
 */
class WebDavProGateTest : BaseTest() {

    private val locationId = Uuid.parse("11111111-2222-3333-4444-555555555555")
    private val path = WebDavPath(locationId, listOf("media"))

    private val locationManager = mockk<WebDavLocationManager>()
    private val credentialStore = mockk<WebDavCredentialStore>(relaxed = true).apply {
        every { evictions } returns MutableSharedFlow()
    }

    /** Asked for a connector means the gate let something through. */
    private val connectorFactory = mockk<WebDavConnectorFactory>()

    @TempDir
    lateinit var spoolDir: File

    private fun pool() = WebDavClientPool(
        appScope = TestScope(),
        locationManager = locationManager,
        credentialStore = credentialStore,
        connectorFactory = connectorFactory,
        upgradeRepo = FakeUpgradeRepo(pro = false),
    )

    private fun ops(pool: WebDavClientPool = pool()) =
        WebDavFileSystemOps(pool, TestDispatcherProvider(), WebDavSpool { spoolDir })

    private fun assertNothingWasContacted() {
        verify(exactly = 0) { connectorFactory.create() }
        coVerify(exactly = 0) { locationManager.get(any()) }
        coVerify(exactly = 0) { credentialStore.resolve(any()) }
        spoolDir.listFiles()!!.size shouldBe 0
    }

    @Test
    fun `a free user cannot list a server`() = runTest {
        val ops = ops()

        shouldThrow<WebDavProRequiredException> { ops.lookupFiles(path, LookupOptions()) }
        shouldThrow<WebDavProRequiredException> { ops.lookup(path, LookupOptions()) }
        ops.existsStrict(path) shouldBe Existence.UNKNOWN

        assertNothingWasContacted()
    }

    /** Streams and handles lease the session directly instead of going through the operation wrapper. */
    @Test
    fun `a free user cannot read or write a byte`() = runTest {
        val ops = ops()

        shouldThrow<WebDavProRequiredException> { ops.openInputStream(path) }
        shouldThrow<WebDavProRequiredException> { ops.openOutputStream(path) }
        shouldThrow<WebDavProRequiredException> { ops.openOutputStream(path, append = true) }
        shouldThrow<WebDavProRequiredException> { ops.file(path, readWrite = false) }
        shouldThrow<WebDavProRequiredException> { ops.file(path, readWrite = true) }
        shouldThrow<WebDavProRequiredException> { ops.upload(path, 1, "x".byteInputStream()) {} }

        assertNothingWasContacted()
    }

    @Test
    fun `a free user cannot change anything`() = runTest {
        val ops = ops()

        shouldThrow<WebDavProRequiredException> { ops.createDir(path) }
        shouldThrow<WebDavProRequiredException> { ops.createFile(path) }
        shouldThrow<WebDavProRequiredException> { ops.delete(path) }
        shouldThrow<WebDavProRequiredException> { ops.move(path, path.child("b")) }
        shouldThrow<WebDavProRequiredException> { ops.copy(path, path.child("b")) }

        assertNothingWasContacted()
    }

    /** An override of [WebDavGateway]'s own, built on top of the same primitives. */
    @Test
    fun `a free user cannot walk a server`() = runTest {
        val pool = pool()
        val gateway = WebDavGateway(TestScope(), TestDispatcherProvider(), ops(pool), pool)

        val error = shouldThrow<ReadException> {
            gateway.walk(path, LookupOptions(), APathGateway.WalkOptions()).toList()
        }

        error.cause.shouldBeInstanceOf<WebDavProRequiredException>()
        assertNothingWasContacted()
    }
}
