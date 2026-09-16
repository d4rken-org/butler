package eu.darken.butler.explorer.ui.explorer

import eu.darken.butler.common.files.smb.SmbConnectionTester
import eu.darken.butler.common.files.smb.credentials.SmbCredential
import eu.darken.butler.common.files.smb.credentials.SmbCredentialUnavailableException
import eu.darken.butler.common.files.smb.credentials.SmbCredentialStore
import eu.darken.butler.common.files.smb.location.SmbLocation
import eu.darken.butler.common.files.smb.location.SmbLocationManager
import eu.darken.butler.explorer.core.ExplorerWorkspace
import eu.darken.butler.explorer.ui.explorer.dialogs.ExplorerDialogState
import eu.darken.butler.upgrade.UpgradeRepo
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.just
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
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

    private var upgrades = 0

    private fun CoroutineScope.controller(
        locationManager: SmbLocationManager,
        dialogs: ExplorerDialogController,
        credentialStore: SmbCredentialStore = mockk(relaxed = true),
        upgradeRepo: UpgradeRepo = FakeUpgradeRepo(pro = true),
    ) = ExplorerSmbLocationController(
        locationManager = locationManager,
        credentialStore = credentialStore,
        connectionTester = mockk<SmbConnectionTester>(relaxed = true),
        upgradeRepo = upgradeRepo,
        navToUpgrade = { upgrades++ },
        dialogs = dialogs,
        workspace = { mockk<ExplorerWorkspace>().apply { coEvery { navigate(any()) } just Runs } },
        currentLocation = { null },
        clearSelection = {},
        onError = {},
        doLaunch = { block -> launch { block() } },
        tag = "test",
    )

    @Test
    fun `a free user is routed to the upgrade screen instead of the add form`() = runTest {
        val dialogs = dialogs()

        controller(mockk(), dialogs, upgradeRepo = FakeUpgradeRepo(pro = false)).showAddForm()
        advanceUntilIdle()

        upgrades shouldBe 1
        dialogs.current() shouldBe ExplorerDialogState.None
    }

    /**
     * Editing is not a local-only change: submitting the form runs a connection test against
     * whatever endpoint it now names, so the form itself is what has to be gated.
     */
    @Test
    fun `a free user is routed to the upgrade screen instead of the edit form`() = runTest {
        val locationManager = mockk<SmbLocationManager>()
        val dialogs = dialogs()

        controller(locationManager, dialogs, upgradeRepo = FakeUpgradeRepo(pro = false))
            .showEditForm(locationId)
        advanceUntilIdle()

        upgrades shouldBe 1
        dialogs.current() shouldBe ExplorerDialogState.None
        coVerify(exactly = 0) { locationManager.get(any()) }
    }

    @Test
    fun `a free user is routed to the upgrade screen instead of the sign-in form`() = runTest {
        val locationManager = mockk<SmbLocationManager>()
        val dialogs = dialogs()

        controller(locationManager, dialogs, upgradeRepo = FakeUpgradeRepo(pro = false))
            .promptSignIn(locationId)
        advanceUntilIdle()

        upgrades shouldBe 1
        dialogs.current() shouldBe ExplorerDialogState.None
    }

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

    @Test
    fun `revealing resolves the saved credential and wipes its copy`() = runTest {
        val form = ExplorerDialogState.SmbLocationForm(existing = location("NAS"))
        val dialogs = dialogs().apply { show(form) }
        val credential = SmbCredential("darken", null, "saved-password".toCharArray())
        val store = mockk<SmbCredentialStore>().apply {
            coEvery { resolve(form.existing!!) } returns credential
        }

        val revealed = controller(mockk(), dialogs, store).revealPassword(form)

        revealed?.value shouldBe "saved-password"
        revealed.toString().contains("saved-password") shouldBe false
        credential.password.all { it == Char(0) } shouldBe true
    }

    @Test
    fun `unavailable credentials show an error and keep the form editable`() = runTest {
        val form = ExplorerDialogState.SmbLocationForm(existing = location("NAS"))
        val dialogs = dialogs().apply { show(form) }
        val store = mockk<SmbCredentialStore>().apply {
            coEvery { resolve(any()) } throws SmbCredentialUnavailableException(locationId, "Missing")
        }

        controller(mockk(), dialogs, store).revealPassword(form) shouldBe null

        val current = dialogs.current().shouldBeInstanceOf<ExplorerDialogState.SmbLocationForm>()
        current.existing shouldBe form.existing
        (current.error != null) shouldBe true
        current.isTesting shouldBe false
    }

    @Test
    fun `an equal but different form instance cannot resolve credentials`() = runTest {
        val form = ExplorerDialogState.SmbLocationForm(existing = location("NAS"))
        val dialogs = dialogs().apply { show(form.copy()) }
        val store = mockk<SmbCredentialStore>()

        controller(mockk(), dialogs, store).revealPassword(form) shouldBe null

        coVerify(exactly = 0) { store.resolve(any()) }
    }

    @Test
    fun `a dismissed form cannot resolve credentials`() = runTest {
        val form = ExplorerDialogState.SmbLocationForm(existing = location("NAS"))
        val dialogs = dialogs().apply {
            show(form)
            dismiss()
        }
        val store = mockk<SmbCredentialStore>()

        controller(mockk(), dialogs, store).revealPassword(form) shouldBe null

        coVerify(exactly = 0) { store.resolve(any()) }
    }

    @Test
    fun `cancelling a reveal propagates without showing an error`() = runTest {
        val form = ExplorerDialogState.SmbLocationForm(existing = location("NAS"))
        val dialogs = dialogs().apply { show(form) }
        val store = mockk<SmbCredentialStore>().apply {
            coEvery { resolve(any()) } throws CancellationException("Dismissed")
        }

        var cancelled = false
        try {
            controller(mockk(), dialogs, store).revealPassword(form)
        } catch (_: CancellationException) {
            cancelled = true
        }

        cancelled shouldBe true
        dialogs.current() shouldBe form
    }
}
