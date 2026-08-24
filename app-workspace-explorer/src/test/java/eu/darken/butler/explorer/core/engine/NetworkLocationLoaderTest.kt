package eu.darken.butler.explorer.core.engine

import eu.darken.butler.common.files.extensions.Segments
import eu.darken.butler.common.files.smb.credentials.SmbCredentialStore
import eu.darken.butler.common.files.smb.location.SmbLocation
import eu.darken.butler.common.files.smb.location.SmbLocationManager
import eu.darken.butler.workspace.core.Workspace
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import testhelpers.BaseTest
import kotlin.time.Instant
import kotlin.uuid.Uuid

class NetworkLocationLoaderTest : BaseTest() {

    private fun location(
        id: Uuid = Uuid.random(),
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
        override suspend fun setLabel(id: Uuid, label: String?) = throw UnsupportedOperationException()
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
    )

    @Test
    fun `an empty list emits an empty network location`() = runTest {
        val emissions = loader(emptyList()).loadNetwork().toList()

        val last = emissions.last().shouldBeInstanceOf<ExplorerLocation.Network>()
        last.items shouldBe emptyList()
        last.info?.locationCount shouldBe 0
        last.progress shouldBe null
    }

    @Test
    fun `stored locations become network storage items`() = runTest {
        val stored = location()
        val emissions = loader(listOf(stored)).loadNetwork().toList()

        val last = emissions.last().shouldBeInstanceOf<ExplorerLocation.Network>()
        last.info?.locationCount shouldBe 1

        val item = last.items!!.single().shouldBeInstanceOf<ExplorerItem.Storage.Network>()
        item.location shouldBe stored
        item.target.path shouldBe stored.rootPath
        item.status shouldBe ExplorerItem.Storage.Network.Status.AVAILABLE
        // Capacity is never probed, drawing the view must not connect anywhere
        item.totalBytes shouldBe null
        item.availableBytes shouldBe null
    }

    @Test
    fun `a location without a usable credential needs a sign-in`() = runTest {
        val emissions = loader(
            listOf(location()),
            availability = SmbCredentialStore.Availability.MISSING,
        ).loadNetwork().toList()

        val item = emissions.last().items!!.single().shouldBeInstanceOf<ExplorerItem.Storage.Network>()
        item.status shouldBe ExplorerItem.Storage.Network.Status.SIGN_IN_REQUIRED
    }

    @Test
    fun `an unreadable key also needs a sign-in`() = runTest {
        val emissions = loader(
            listOf(location()),
            availability = SmbCredentialStore.Availability.KEY_UNAVAILABLE,
        ).loadNetwork().toList()

        val item = emissions.last().items!!.single().shouldBeInstanceOf<ExplorerItem.Storage.Network>()
        item.status shouldBe ExplorerItem.Storage.Network.Status.SIGN_IN_REQUIRED
    }

    @Test
    fun `the first emission reports progress`() = runTest {
        val emissions = loader(listOf(location())).loadNetwork().toList()

        emissions.first().isLoading shouldBe true
        emissions.last().isLoading shouldBe false
    }
}
