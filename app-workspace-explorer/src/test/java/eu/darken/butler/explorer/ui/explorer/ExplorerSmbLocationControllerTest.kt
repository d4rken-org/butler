package eu.darken.butler.explorer.ui.explorer

import eu.darken.butler.common.files.smb.SmbConnectionTester
import eu.darken.butler.common.files.smb.credentials.SmbCredentialStore
import eu.darken.butler.common.files.smb.location.SmbLocation
import eu.darken.butler.common.files.smb.location.SmbLocationManager
import eu.darken.butler.explorer.core.ExplorerWorkspace
import eu.darken.butler.explorer.ui.explorer.dialogs.ExplorerDialogState
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.just
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import testhelpers.BaseTest
import kotlin.time.Instant
import kotlin.uuid.Uuid

class ExplorerSmbLocationControllerTest : BaseTest() {

    private val locationId = Uuid.parse("11111111-2222-3333-4444-555555555555")

    private fun location(label: String?) = SmbLocation(
        id = locationId,
        label = label,
        host = "nas.local",
        share = "media",
        authType = SmbLocation.AuthType.PASSWORD,
        rememberCredential = true,
        credentialVersion = 1,
        createdAt = Instant.fromEpochMilliseconds(0),
        updatedAt = Instant.fromEpochMilliseconds(0),
    )

    private fun dialogs() = ExplorerDialogController(
        filterState = { mockk() },
        useRegexPatterns = { false },
        clearSelection = {},
        tag = "test",
    )

    private fun CoroutineScope.controller(
        locationManager: SmbLocationManager,
        dialogs: ExplorerDialogController,
    ) = ExplorerSmbLocationController(
        locationManager = locationManager,
        credentialStore = mockk<SmbCredentialStore>(relaxed = true),
        connectionTester = mockk<SmbConnectionTester>(relaxed = true),
        dialogs = dialogs,
        workspace = { mockk<ExplorerWorkspace>().apply { coEvery { navigate(any()) } just Runs } },
        currentLocation = { null },
        clearSelection = {},
        onError = {},
        doLaunch = { block -> launch { block() } },
        tag = "test",
    )

    @Test
    fun `the edit form opens with what is stored now, not what the row was drawn from`() = runTest {
        val locationManager = mockk<SmbLocationManager>().apply {
            coEvery { get(locationId) } returns location("Basement NAS")
        }
        val dialogs = dialogs()

        controller(locationManager, dialogs).showEditForm(locationId)
        advanceUntilIdle()

        val form = dialogs.current().shouldBeInstanceOf<ExplorerDialogState.SmbLocationForm>()
        form.existing?.label shouldBe "Basement NAS"
    }

    @Test
    fun `no form opens for a location that is gone`() = runTest {
        val locationManager = mockk<SmbLocationManager>().apply {
            coEvery { get(locationId) } returns null
        }
        val dialogs = dialogs()

        controller(locationManager, dialogs).showEditForm(locationId)
        advanceUntilIdle()

        dialogs.current() shouldBe ExplorerDialogState.None
    }
}
