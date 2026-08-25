package eu.darken.butler.explorer.core.engine

import eu.darken.butler.common.files.extensions.Segments
import eu.darken.butler.common.files.smb.SmbEndpointProbe
import eu.darken.butler.common.files.smb.SmbEndpointState
import eu.darken.butler.common.files.smb.credentials.SmbCredentialStore
import eu.darken.butler.common.files.smb.location.SmbLocation
import eu.darken.butler.common.files.smb.location.SmbLocationManager
import eu.darken.butler.workspace.core.Workspace
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.flow.Flow
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

    private class FakeLocationManager(private val stored: List<SmbLocation>) : SmbLocationManager {
        override val locations: Flow<List<SmbLocation>> get() = flowOf(stored)
        override suspend fun get(id: Uuid): SmbLocation? = stored.firstOrNull { it.id == id }
        override suspend fun create(
            label: String?,
            host: String,
            port: Int,
            share: String,
            basePath: Segments,
            domain: String?,
            username: String?,
            authType: SmbLocation.AuthType,
            rememberCredential: Boolean,
            password: CharArray?,
        ): SmbLocation = throw UnsupportedOperationException()

        override suspend fun update(
            id: Uuid,
            label: String?,
            host: String,
            port: Int,
            share: String,
            basePath: Segments,
            domain: String?,
            username: String?,
            authType: SmbLocation.AuthType,
            rememberCredential: Boolean,
            password: CharArray?,
        ): SmbLocation = throw UnsupportedOperationException()

        override suspend fun delete(id: Uuid) = throw UnsupportedOperationException()
    }

    private val endpointStates = MutableStateFlow<Map<Uuid, SmbEndpointState>>(emptyMap())

    private val endpointProbe = mockk<SmbEndpointProbe>(relaxed = true).apply {
        every { states } returns endpointStates
    }

    private fun loader(
        locations: List<SmbLocation>,
        availability: SmbCredentialStore.Availability = SmbCredentialStore.Availability.AVAILABLE,
    ) = NetworkLocationLoader(
        workspaceId = Workspace.Id(),
        locationManager = FakeLocationManager(locations),
        credentialStore = mockk(relaxed = true) {
            every { availability(any<SmbLocation>()) } returns flowOf(availability)
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
        item.location shouldBe stored
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
        item.endpoint shouldBe SmbEndpointState()
        verify { endpointProbe.probe(listOf(location()), force = false) }
    }

    @Test
    fun `a probe result arrives as another emission`() = runTest {
        val emissions = mutableListOf<ExplorerLocation>()
        val collector = launch { loader(listOf(location())).loadNetwork().toList(emissions) }
        runCurrent()

        emissions.size shouldBe 2
        emissions.last().items!!.single().shouldBeInstanceOf<ExplorerItem.Storage.Network>()
            .endpoint.reachability shouldBe SmbEndpointState.Reachability.CHECKING

        endpointStates.value = mapOf(
            locationId to SmbEndpointState("192.168.1.50", SmbEndpointState.Reachability.REACHABLE),
        )
        runCurrent()

        emissions.size shouldBe 3
        emissions.last().items!!.single().shouldBeInstanceOf<ExplorerItem.Storage.Network>()
            .endpoint shouldBe SmbEndpointState("192.168.1.50", SmbEndpointState.Reachability.REACHABLE)

        collector.cancel()
    }

    @Test
    fun `a refresh re-probes instead of reusing recent results`() = runTest {
        loader(listOf(location())).loadNetwork(force = true).take(2).toList()

        verify { endpointProbe.probe(listOf(location()), force = true) }
    }

    @Test
    fun `a location without a usable credential needs a sign-in`() = runTest {
        val emissions = loader(
            listOf(location()),
            availability = SmbCredentialStore.Availability.MISSING,
        ).loadNetwork().take(2).toList()

        val item = emissions.last().items!!.single().shouldBeInstanceOf<ExplorerItem.Storage.Network>()
        item.status shouldBe ExplorerItem.Storage.Network.Status.SIGN_IN_REQUIRED
    }

    @Test
    fun `an unreadable key also needs a sign-in`() = runTest {
        val emissions = loader(
            listOf(location()),
            availability = SmbCredentialStore.Availability.KEY_UNAVAILABLE,
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
}
