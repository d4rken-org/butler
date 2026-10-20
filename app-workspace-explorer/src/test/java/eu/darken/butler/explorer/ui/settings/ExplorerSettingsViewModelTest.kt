package eu.darken.butler.explorer.ui.settings

import eu.darken.butler.common.datastore.DataStoreValue
import eu.darken.butler.common.files.APath
import eu.darken.butler.common.files.LocalPath
import eu.darken.butler.common.navigation.NavEvent
import eu.darken.butler.common.trash.TrashSettings
import eu.darken.butler.explorer.core.DefaultStartLocation
import eu.darken.butler.explorer.core.ExplorerSettings
import eu.darken.butler.explorer.core.SortSettings
import eu.darken.butler.explorer.core.sorting.rules.FolderSortRulesRepo
import eu.darken.butler.workspace.contracts.picker.HostedPickerRequests
import eu.darken.butler.workspace.contracts.picker.HostedPickerRequests.Outcome
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import testhelpers.BaseTest
import testhelpers.coroutine.TestDispatcherProvider
import kotlin.time.Duration.Companion.days

class ExplorerSettingsViewModelTest : BaseTest() {

    private val stored = MutableStateFlow<DefaultStartLocation?>(null)

    private val startLocationValue: DataStoreValue<DefaultStartLocation?> = mockk {
        every { flow } returns stored
        coEvery { update(any()) } answers {
            val old = stored.value
            stored.value = firstArg<(DefaultStartLocation?) -> DefaultStartLocation?>().invoke(old)
            DataStoreValue.Updated(old = old, new = stored.value)
        }
    }

    private val explorerSettings: ExplorerSettings = mockk {
        every { sortSettings } returns mockk { every { flow } returns MutableStateFlow(SortSettings()) }
        every { useRegexPatterns } returns mockk { every { flow } returns MutableStateFlow(false) }
        every { useBackButtonForNavigation } returns mockk { every { flow } returns MutableStateFlow(true) }
        every { openFilesOnTap } returns mockk { every { flow } returns MutableStateFlow(false) }
        every { showSystemLocations } returns mockk { every { flow } returns MutableStateFlow(false) }
        every { defaultStartLocation } returns startLocationValue
    }

    private val trashSettings: TrashSettings = mockk {
        every { enabled } returns mockk { every { flow } returns MutableStateFlow(true) }
        every { expiresAfter } returns mockk { every { flow } returns MutableStateFlow(30.days) }
        every { maxTrashSize } returns mockk { every { flow } returns MutableStateFlow(500L * 1048576L) }
        every { confirmTrashMove } returns mockk { every { flow } returns MutableStateFlow(true) }
    }

    private val folderSortRules: FolderSortRulesRepo = mockk { every { count } returns MutableStateFlow(0) }

    /** Hands out one ticket per request; each outcome resolves when the test says so. */
    private class FakeHostedPickers : HostedPickerRequests {
        val requests = mutableListOf<HostedPickerRequests.Request>()
        val outcomes = mutableMapOf<String, CompletableDeferred<Outcome>>()

        override fun register(request: HostedPickerRequests.Request): HostedPickerRequests.Ticket {
            requests += request
            val ticket = HostedPickerRequests.Ticket("ticket-${requests.size}")
            outcomes[ticket.token] = CompletableDeferred()
            return ticket
        }

        /** Tickets whose waiter gave up before an outcome arrived. */
        val abandoned = mutableListOf<String>()

        override suspend fun awaitOutcome(ticket: HostedPickerRequests.Ticket): Outcome {
            val outcome = outcomes.getValue(ticket.token)
            try {
                return outcome.await()
            } finally {
                if (!outcome.isCompleted) abandoned += ticket.token
            }
        }
    }

    private val hostedPickers = FakeHostedPickers()
    private val downloads = LocalPath.build("/storage/emulated/0/Download")
    private val pictures = LocalPath.build("/storage/emulated/0/Pictures")

    private fun createViewModel(dispatcherProvider: TestDispatcherProvider = TestDispatcherProvider()) = ExplorerSettingsViewModel(
        dispatcherProvider = dispatcherProvider,
        explorerSettings = explorerSettings,
        trashSettings = trashSettings,
        folderSortRules = folderSortRules,
        hostedPickers = hostedPickers,
    )

    private suspend fun ExplorerSettingsViewModel.current() = state.filterNotNull().first()

    private suspend fun ExplorerSettingsViewModel.dialog() =
        state.filterNotNull().first { it.startLocationDialog != null }.startLocationDialog!!

    @Test
    fun `an unset start location shows as Home`() = runTest {
        createViewModel().current().startLocation shouldBe DefaultStartLocation.Home
    }

    @Test
    fun `choosing Home keeps the folder on offer and only saving writes it`() = runTest {
        stored.value = DefaultStartLocation.Directory(downloads)
        val vm = createViewModel()

        vm.openStartLocationDialog()
        vm.dialog().apply {
            selection shouldBe DefaultStartLocation.Directory(downloads)
            folder shouldBe downloads
        }

        vm.selectStartLocation(DefaultStartLocation.Home)
        vm.dialog().folder shouldBe downloads
        stored.value shouldBe DefaultStartLocation.Directory(downloads)

        vm.confirmStartLocation()
        vm.current().startLocationDialog shouldBe null
        stored.value shouldBe DefaultStartLocation.Home
    }

    @Test
    fun `dismissing discards the draft`() = runTest {
        val vm = createViewModel()
        vm.openStartLocationDialog()
        vm.selectStartLocation(DefaultStartLocation.Device)

        vm.dismissStartLocationDialog()

        vm.current().startLocationDialog shouldBe null
        stored.value shouldBe null
    }

    @Test
    fun `a picked folder fills the draft and opens the picker where the draft is`() = runTest {
        stored.value = DefaultStartLocation.Directory(downloads)
        val vm = createViewModel()
        vm.openStartLocationDialog()
        vm.dialog()

        vm.chooseStartFolder()
        hostedPickers.requests.single().startPath shouldBe downloads
        vm.navEvents.first() shouldBe NavEvent.GoTo(HostedPickerRequests.Ticket("ticket-1").destination)

        hostedPickers.outcomes.getValue("ticket-1").complete(Outcome.Picked(listOf(pictures)))

        vm.state.filterNotNull().first { it.startLocationDialog?.folder == pictures }
            .startLocationDialog!!.selection shouldBe DefaultStartLocation.Directory(pictures)
        stored.value shouldBe DefaultStartLocation.Directory(downloads)
    }

    @Test
    fun `a repeated tap while the picker is open starts no second one`() = runTest {
        val vm = createViewModel()
        vm.openStartLocationDialog()
        vm.dialog()

        vm.chooseStartFolder()
        vm.chooseStartFolder()

        hostedPickers.requests.size shouldBe 1
        vm.dialog().picking shouldBe true
    }

    @Test
    fun `a result with more than one path leaves the draft alone`() = runTest {
        val vm = createViewModel()
        vm.openStartLocationDialog()
        vm.dialog()

        vm.chooseStartFolder()
        hostedPickers.outcomes.getValue("ticket-1").complete(Outcome.Picked(listOf<APath<*>>(downloads, pictures)))

        vm.state.filterNotNull().first { it.startLocationDialog?.picking == false }.startLocationDialog!!.apply {
            selection shouldBe DefaultStartLocation.Home
            folder shouldBe null
        }
    }

    @Test
    fun `saving a picked folder writes it and closes the dialog`() = runTest {
        val vm = createViewModel()
        vm.openStartLocationDialog()
        vm.dialog()
        vm.chooseStartFolder()
        hostedPickers.outcomes.getValue("ticket-1").complete(Outcome.Picked(listOf(pictures)))
        vm.state.filterNotNull().first { it.startLocationDialog?.picking == false }

        vm.confirmStartLocation()

        stored.value shouldBe DefaultStartLocation.Directory(pictures)
        vm.current().startLocationDialog shouldBe null
    }

    @Test
    fun `dismissing before the wait ever ran still abandons the request`() = runTest {
        val vm = createViewModel(TestDispatcherProvider(StandardTestDispatcher(testScheduler)))
        vm.openStartLocationDialog()
        advanceUntilIdle()

        vm.chooseStartFolder()
        vm.dismissStartLocationDialog()
        advanceUntilIdle()

        hostedPickers.abandoned shouldBe listOf("ticket-1")
    }
}
