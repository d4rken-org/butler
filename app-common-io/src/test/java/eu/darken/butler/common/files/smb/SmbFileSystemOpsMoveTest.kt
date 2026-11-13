package eu.darken.butler.common.files.smb

import eu.darken.butler.common.files.MoveOutcome
import eu.darken.butler.common.files.SmbPath
import eu.darken.butler.common.files.errors.WriteException
import eu.darken.butler.common.files.smb.credentials.SmbCredentialStore
import eu.darken.butler.common.files.smb.location.SmbLocation
import eu.darken.butler.common.files.smb.location.SmbLocationManager
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import testhelpers.BaseTest
import testhelpers.coroutine.TestDispatcherProvider
import kotlin.time.Instant
import kotlin.uuid.Uuid

/** A protected path is refused even when the move would otherwise be NotSupported and fall back to copy+delete. */
class SmbFileSystemOpsMoveTest : BaseTest() {

    private val serverId = Uuid.parse("11111111-2222-3333-4444-555555555555")
    private val otherId = Uuid.parse("66666666-7777-8888-9999-aaaaaaaaaaaa")

    private fun location(id: Uuid, share: String?) = SmbLocation(
        id = id,
        label = null,
        host = "nas.local",
        share = share,
        authType = SmbLocation.AuthType.GUEST,
        rememberCredential = false,
        credentialVersion = 1,
        createdAt = Instant.fromEpochMilliseconds(0),
        updatedAt = Instant.fromEpochMilliseconds(0),
    )

    private val locationManager = mockk<SmbLocationManager>().apply {
        coEvery { get(serverId) } returns location(serverId, share = null)
        coEvery { get(otherId) } returns location(otherId, share = "media")
    }
    private val credentialStore = mockk<SmbCredentialStore>(relaxed = true).apply {
        every { evictions } returns MutableSharedFlow()
    }

    /** Asked for a client means a lease was acquired. */
    private val clientFactory = mockk<SmbClientFactory>()

    private val ops = SmbFileSystemOps(
        SmbConnectionPool(
            appScope = TestScope(),
            locationManager = locationManager,
            credentialStore = credentialStore,
            clientFactory = clientFactory,
            upgradeRepo = FakeUpgradeRepo(pro = true),
        ),
        locationManager,
        TestDispatcherProvider(),
    )

    private val serverRoot = SmbPath(serverId, emptyList())
    private val shareEntry = SmbPath(serverId, listOf("media"))
    private val otherPath = SmbPath(otherId, listOf("movies", "a.mkv"))

    private fun assertNothingWasContacted() {
        verify(exactly = 0) { clientFactory.create() }
        verify(exactly = 0) { clientFactory.createLister() }
    }

    @Test
    fun `a server root cannot be moved to another location`() = runTest {
        shouldThrow<WriteException> { ops.move(serverRoot, otherPath) }
        assertNothingWasContacted()
    }

    @Test
    fun `a share entry cannot be moved to another location`() = runTest {
        shouldThrow<WriteException> { ops.move(shareEntry, otherPath) }
        assertNothingWasContacted()
    }

    @Test
    fun `a server root cannot be replaced from another location`() = runTest {
        shouldThrow<WriteException> { ops.move(otherPath, serverRoot) }
        assertNothingWasContacted()
    }

    @Test
    fun `a share entry cannot be replaced from another location`() = runTest {
        shouldThrow<WriteException> { ops.move(otherPath, shareEntry) }
        assertNothingWasContacted()
    }

    @Test
    fun `an unprotected move to another location is not supported`() = runTest {
        ops.move(SmbPath(serverId, listOf("media", "a.mkv")), otherPath)
            .shouldBeInstanceOf<MoveOutcome.NotSupported>()
        assertNothingWasContacted()
    }
}
