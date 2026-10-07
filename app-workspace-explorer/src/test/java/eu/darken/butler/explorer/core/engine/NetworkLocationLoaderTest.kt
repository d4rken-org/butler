package eu.darken.butler.explorer.core.engine

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.twotone.Terminal
import eu.darken.butler.common.compose.icons.SmbShare
import eu.darken.butler.common.files.SftpPath
import eu.darken.butler.common.files.network.NetworkCredentialAvailability
import eu.darken.butler.common.files.network.NetworkEndpointProbe
import eu.darken.butler.common.files.network.NetworkEndpointState
import eu.darken.butler.common.files.network.NetworkLocation
import eu.darken.butler.common.files.network.NetworkLocationRepo
import eu.darken.butler.common.files.sftp.location.SftpLocation
import eu.darken.butler.common.files.sftp.location.TrustedHostKey
import eu.darken.butler.common.files.smb.location.SmbLocation
import eu.darken.butler.workspace.core.Workspace
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import testhelpers.BaseTest
import kotlin.time.Instant
import kotlin.uuid.Uuid

class NetworkLocationLoaderTest : BaseTest() {

    private val locationId = Uuid.parse("11111111-1111-1111-1111-111111111111")
    private val sftpLocationId = Uuid.parse("22222222-2222-2222-2222-222222222222")

    private fun location(
        id: Uuid = locationId,
        label: String? = "Home NAS",
        authType: SmbLocation.AuthType = SmbLocation.AuthType.PASSWORD,
    ) = SmbLocation(
        id = id,
        label = label,
        host = "nas.local",
        share = "media",
        authType = authType,
        rememberCredential = true,
        credentialVersion = 1,
        createdAt = Instant.fromEpochMilliseconds(0),
        updatedAt = Instant.fromEpochMilliseconds(0),
    )

    private fun sftpLocation(
        id: Uuid = sftpLocationId,
        createdAt: Instant = Instant.fromEpochMilliseconds(1_000),
    ) = SftpLocation(
        id = id,
        label = "Build server",
        host = "build.lan",
        port = 2222,
        username = "darken",
        basePath = "/srv/builds",
        authType = SftpLocation.AuthType.PRIVATE_KEY,
        rememberCredential = true,
        credentialVersion = 1,
        hostKey = TrustedHostKey("ssh-ed25519", ByteArray(51), "SHA256:Utlnml924yfwY1Df/Rf4pu3A8u5JKZ118Cd9/hz+ijM"),
        trustRevision = 1,
        createdAt = createdAt,
        updatedAt = createdAt,
    )

    private val endpointStates = MutableStateFlow<Map<Uuid, NetworkEndpointState>>(emptyMap())

    private val endpointProbe = mockk<NetworkEndpointProbe>(relaxed = true).apply {
        every { states } returns endpointStates
    }

    private fun loader(
        locations: List<SmbLocation>,
        availability: NetworkCredentialAvailability = NetworkCredentialAvailability.AVAILABLE,
    ) = networkLoader(locations.map { NetworkLocation.Smb(it) }) { availability }

    private fun networkLoader(
        locations: List<NetworkLocation>,
        availability: (NetworkLocation) -> NetworkCredentialAvailability = { NetworkCredentialAvailability.AVAILABLE },
    ) = NetworkLocationLoader(
        workspaceId = Workspace.Id(),
        locationRepo = mockk<NetworkLocationRepo>().apply {
            every { this@apply.locations } returns flowOf(locations)
            every { credentialAvailability(any()) } answers { flowOf(availability(firstArg())) }
        },
        endpointProbe = endpointProbe,
    )

    @Test
    fun `an empty list emits an empty network location`() = runTest {
        val emissions = loader(emptyList()).loadNetwork().take(2).toList()

        val last = emissions.last().shouldBeInstanceOf<ExplorerLocation.Network>()
        last.items shouldBe emptyList()
        last.info?.locationCount shouldBe 0
        last.progress shouldBe null
    }

    @Test
    fun `stored locations become network storage items`() = runTest {
        val stored = location()
        val emissions = loader(listOf(stored)).loadNetwork().take(2).toList()

        val last = emissions.last().shouldBeInstanceOf<ExplorerLocation.Network>()
        last.info?.locationCount shouldBe 1

        val item = last.items!!.single().shouldBeInstanceOf<ExplorerItem.Storage.Network>()
        item.location shouldBe NetworkLocation.Smb(stored)
        item.target.path shouldBe stored.rootPath
        item.status shouldBe ExplorerItem.Storage.Network.Status.AVAILABLE
        // Capacity is never read, drawing the view must not open a session anywhere
        item.totalBytes shouldBe null
        item.availableBytes shouldBe null
    }

    /** The list has to be on screen while the servers are still being asked. */
    @Test
    fun `rows are listed before any probe has an answer`() = runTest {
        val emissions = loader(listOf(location())).loadNetwork().take(2).toList()

        val item = emissions.last().items!!.single().shouldBeInstanceOf<ExplorerItem.Storage.Network>()
        item.endpoint shouldBe NetworkEndpointState()
        verify { endpointProbe.probe(listOf(NetworkLocation.Smb(location())), force = false) }
    }

    @Test
    fun `a probe result arrives as another emission`() = runTest {
        val emissions = mutableListOf<ExplorerLocation>()
        val collector = launch { loader(listOf(location())).loadNetwork().toList(emissions) }
        runCurrent()

        emissions.size shouldBe 2
        emissions.last().items!!.single().shouldBeInstanceOf<ExplorerItem.Storage.Network>()
            .endpoint.reachability shouldBe NetworkEndpointState.Reachability.CHECKING

        endpointStates.value = mapOf(
            locationId to NetworkEndpointState("192.168.1.50", NetworkEndpointState.Reachability.REACHABLE),
        )
        runCurrent()

        emissions.size shouldBe 3
        emissions.last().items!!.single().shouldBeInstanceOf<ExplorerItem.Storage.Network>()
            .endpoint shouldBe NetworkEndpointState("192.168.1.50", NetworkEndpointState.Reachability.REACHABLE)

        collector.cancel()
    }

    @Test
    fun `a refresh re-probes instead of reusing recent results`() = runTest {
        loader(listOf(location())).loadNetwork(force = true).take(2).toList()

        verify { endpointProbe.probe(listOf(NetworkLocation.Smb(location())), force = true) }
    }

    @Test
    fun `a location without a usable credential needs a sign-in`() = runTest {
        val emissions = loader(
            listOf(location()),
            availability = NetworkCredentialAvailability.MISSING,
        ).loadNetwork().take(2).toList()

        val item = emissions.last().items!!.single().shouldBeInstanceOf<ExplorerItem.Storage.Network>()
        item.status shouldBe ExplorerItem.Storage.Network.Status.SIGN_IN_REQUIRED
    }

    @Test
    fun `an unreadable key also needs a sign-in`() = runTest {
        val emissions = loader(
            listOf(location()),
            availability = NetworkCredentialAvailability.KEY_UNAVAILABLE,
        ).loadNetwork().take(2).toList()

        val item = emissions.last().items!!.single().shouldBeInstanceOf<ExplorerItem.Storage.Network>()
        item.status shouldBe ExplorerItem.Storage.Network.Status.SIGN_IN_REQUIRED
    }

    @Test
    fun `the first emission reports progress`() = runTest {
        val emissions = loader(listOf(location())).loadNetwork().take(2).toList()

        emissions.first().isLoading shouldBe true
        emissions.first().items shouldBe null
        emissions.last().isLoading shouldBe false
    }

    @Test
    fun `the load does not settle before the locations are known`() = runTest {
        val first = loader(listOf(location())).loadNetwork().first()

        first.isLoading shouldBe true
    }

    @Test
    fun `an SFTP server becomes a network storage item of its own kind`() = runTest {
        val stored = sftpLocation()
        val emissions = networkLoader(listOf(NetworkLocation.Sftp(stored))).loadNetwork().take(2).toList()

        val item = emissions.last().items!!.single().shouldBeInstanceOf<ExplorerItem.Storage.Network>()
        item.id shouldBe "network-$sftpLocationId"
        item.location shouldBe NetworkLocation.Sftp(stored)
        item.target.path shouldBe SftpPath.root(sftpLocationId)
        item.displayIcon shouldBe Icons.TwoTone.Terminal
        item.subtitle.get(mockk()) shouldBe "darken@build.lan:2222/srv/builds"
        item.status shouldBe ExplorerItem.Storage.Network.Status.AVAILABLE
        item.totalBytes shouldBe null
        verify { endpointProbe.probe(listOf(NetworkLocation.Sftp(stored)), force = false) }
    }

    @Test
    fun `SMB and SFTP locations are listed together in the order the repo keeps`() = runTest {
        val smb = NetworkLocation.Smb(location())
        val sftp = NetworkLocation.Sftp(sftpLocation())
        val emissions = networkLoader(listOf(smb, sftp)).loadNetwork().take(2).toList()

        val last = emissions.last().shouldBeInstanceOf<ExplorerLocation.Network>()
        last.info?.locationCount shouldBe 2
        val items = last.items!!.map { it.shouldBeInstanceOf<ExplorerItem.Storage.Network>() }
        items.map { it.location } shouldBe listOf(smb, sftp)
        items.map { it.displayIcon } shouldBe listOf(Icons.TwoTone.SmbShare, Icons.TwoTone.Terminal)
    }

    @Test
    fun `an SFTP credential is asked for per location`() = runTest {
        val smb = NetworkLocation.Smb(location())
        val sftp = NetworkLocation.Sftp(sftpLocation())
        val emissions = networkLoader(listOf(smb, sftp)) {
            when (it) {
                is NetworkLocation.Smb -> NetworkCredentialAvailability.AVAILABLE
                is NetworkLocation.Sftp -> NetworkCredentialAvailability.MISSING
            }
        }.loadNetwork().take(2).toList()

        val items = emissions.last().items!!.map { it.shouldBeInstanceOf<ExplorerItem.Storage.Network>() }
        items.map { it.status } shouldBe listOf(
            ExplorerItem.Storage.Network.Status.AVAILABLE,
            ExplorerItem.Storage.Network.Status.SIGN_IN_REQUIRED,
        )
    }
}
