package eu.darken.butler.explorer.ui.explorer

import eu.darken.butler.common.files.smb.SmbConnectionTester
import eu.darken.butler.common.files.smb.credentials.SmbCredential
import eu.darken.butler.common.files.smb.credentials.SmbCredentialUnavailableException
import eu.darken.butler.common.files.smb.credentials.SmbCredentialStore
import eu.darken.butler.common.files.smb.location.SmbLocation
import eu.darken.butler.common.files.smb.location.SmbLocationManager
import eu.darken.butler.explorer.core.ExplorerNavigation
import eu.darken.butler.explorer.core.ExplorerWorkspace
import eu.darken.butler.explorer.ui.explorer.dialogs.ExplorerDialogState
import eu.darken.butler.explorer.ui.explorer.dialogs.SmbLocationFormInput
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
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import testhelpers.BaseTest
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
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
    private val workspace = mockk<ExplorerWorkspace>().apply { coEvery { navigate(any()) } just Runs }

    private fun CoroutineScope.controller(
        locationManager: SmbLocationManager,
        dialogs: ExplorerDialogController,
        credentialStore: SmbCredentialStore = mockk(relaxed = true),
        upgradeRepo: UpgradeRepo = FakeUpgradeRepo(pro = true),
        connectionTester: SmbConnectionTester = mockk(relaxed = true),
        upgradeHintTimeout: Duration = ExplorerSmbLocationController.UPGRADE_HINT_TIMEOUT,
    ) = ExplorerSmbLocationController(
        locationManager = locationManager,
        credentialStore = credentialStore,
        connectionTester = connectionTester,
        upgradeRepo = upgradeRepo,
        navToUpgrade = { upgrades++ },
        dialogs = dialogs,
        workspace = { workspace },
        currentLocation = { null },
        clearSelection = {},
        doLaunch = { block -> launch { block() } },
        tag = "test",
        upgradeHintTimeout = { upgradeHintTimeout },
    )

    private val guestInput = SmbLocationFormInput(
        label = "",
        host = "nas.local",
        port = "445",
        share = "media",
        basePath = "",
        authType = SmbLocation.AuthType.GUEST,
        username = "",
        domain = "",
        password = "",
        rememberCredential = true,
    )

    private fun savingLocationManager() = mockk<SmbLocationManager>().apply {
        coEvery {
            create(any(), any(), any(), any(), any(), any(), any(), any(), any(), any())
        } returns location("NAS")
    }

    @Test
    fun `a free user gets the add form`() = runTest {
        val dialogs = dialogs()

        controller(mockk(), dialogs, upgradeRepo = FakeUpgradeRepo(pro = false)).showAddForm()
        advanceUntilIdle()

        upgrades shouldBe 0
        dialogs.current().shouldBeInstanceOf<ExplorerDialogState.SmbLocationForm>()
    }

    @Test
    fun `a free user gets the edit form`() = runTest {
        val locationManager = mockk<SmbLocationManager>().apply {
            coEvery { get(locationId) } returns location("NAS")
        }
        val dialogs = dialogs()

        controller(locationManager, dialogs, upgradeRepo = FakeUpgradeRepo(pro = false)).showEditForm(locationId)
        advanceUntilIdle()

        upgrades shouldBe 0
        dialogs.current().shouldBeInstanceOf<ExplorerDialogState.SmbLocationForm>()
    }

    /** Saving is free, browsing is not: the location is stored and the user is told why it stays shut. */
    @Test
    fun `a free user saves a location and gets the upgrade hint`() = runTest {
        val locationManager = savingLocationManager()
        val dialogs = dialogs()
        val controller = controller(locationManager, dialogs, upgradeRepo = FakeUpgradeRepo(pro = false))
        controller.showAddForm()

        controller.onFormSubmit(guestInput)
        runCurrent()

        coVerify(exactly = 1) {
            locationManager.create(any(), any(), any(), any(), any(), any(), any(), any(), any(), any())
        }
        dialogs.current() shouldBe ExplorerDialogState.None
        controller.upgradeHint.value?.reason shouldBe SmbUpgradeHint.Reason.SAVED
        upgrades shouldBe 0
    }

    @Test
    fun `a pro user saves a location without the upgrade hint`() = runTest {
        val locationManager = savingLocationManager()
        val dialogs = dialogs()
        val controller = controller(locationManager, dialogs)
        controller.showAddForm()

        controller.onFormSubmit(guestInput)
        runCurrent()

        coVerify(exactly = 1) {
            locationManager.create(any(), any(), any(), any(), any(), any(), any(), any(), any(), any())
        }
        coVerify(exactly = 1) { workspace.navigate(ExplorerNavigation.Refresh) }
        dialogs.current() shouldBe ExplorerDialogState.None
        controller.upgradeHint.value shouldBe null
    }

    /** A paying user whose billing is still connecting must neither wait for it nor be told to upgrade. */
    @Test
    fun `saving while billing connects refreshes right away and shows no hint`() = runTest {
        val upgradeRepo = FakeUpgradeRepo(pro = false, settled = false)
        val controller = controller(savingLocationManager(), dialogs(), upgradeRepo = upgradeRepo)
        controller.showAddForm()

        controller.onFormSubmit(guestInput)
        runCurrent()

        coVerify(exactly = 1) { workspace.navigate(ExplorerNavigation.Refresh) }
        controller.upgradeHint.value shouldBe null
    }

    @Test
    fun `saving after a failed billing lookup shows no hint`() = runTest {
        val upgradeRepo = FakeUpgradeRepo(pro = false, error = IllegalStateException("billing is down"))
        val controller = controller(savingLocationManager(), dialogs(), upgradeRepo = upgradeRepo)
        controller.showAddForm()

        controller.onFormSubmit(guestInput)
        runCurrent()

        controller.upgradeHint.value shouldBe null
    }

    @Test
    fun `the upgrade hint goes away on its own`() = runTest {
        val controller = controller(mockk(), dialogs(), upgradeRepo = FakeUpgradeRepo(pro = false))

        controller.showUpgradeHint(SmbUpgradeHint.Reason.LOCKED)
        advanceTimeBy(ExplorerSmbLocationController.UPGRADE_HINT_TIMEOUT + 1.seconds)

        controller.upgradeHint.value shouldBe null
    }

    @Test
    fun `the hint stays for as long as the accessibility settings ask`() = runTest {
        val controller = controller(mockk(), dialogs(), upgradeHintTimeout = 20.seconds)

        controller.showUpgradeHint(SmbUpgradeHint.Reason.LOCKED)
        advanceTimeBy(ExplorerSmbLocationController.UPGRADE_HINT_TIMEOUT + 1.seconds)

        controller.upgradeHint.value?.reason shouldBe SmbUpgradeHint.Reason.LOCKED
    }

    @Test
    fun `an earlier hint's timeout does not close a newer hint`() = runTest {
        val controller = controller(mockk(), dialogs())

        controller.showUpgradeHint(SmbUpgradeHint.Reason.SAVED)
        advanceTimeBy(ExplorerSmbLocationController.UPGRADE_HINT_TIMEOUT - 1.seconds)
        controller.showUpgradeHint(SmbUpgradeHint.Reason.LOCKED)
        advanceTimeBy(2.seconds)

        controller.upgradeHint.value?.reason shouldBe SmbUpgradeHint.Reason.LOCKED
    }

    @Test
    fun `upgrading from the hint closes it and opens the upgrade screen`() = runTest {
        val controller = controller(mockk(), dialogs(), upgradeRepo = FakeUpgradeRepo(pro = false))
        controller.showUpgradeHint(SmbUpgradeHint.Reason.LOCKED)

        controller.onUpgradeHintAction()

        controller.upgradeHint.value shouldBe null
        upgrades shouldBe 1
    }

    @Test
    fun `a form closed during the connection test is not saved`() = runTest {
        val locationManager = savingLocationManager()
        val dialogs = dialogs()
        val tester = mockk<SmbConnectionTester>().apply {
            coEvery { test(any(), any(), any(), any(), any(), any()) } coAnswers { dialogs.dismiss() }
        }
        val controller = controller(locationManager, dialogs, connectionTester = tester)
        controller.showAddForm()

        controller.onFormSubmit(guestInput)
        runCurrent()

        coVerify(exactly = 0) {
            locationManager.create(any(), any(), any(), any(), any(), any(), any(), any(), any(), any())
        }
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
