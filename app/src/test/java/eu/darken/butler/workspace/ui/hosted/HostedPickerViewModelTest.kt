package eu.darken.butler.workspace.ui.hosted

import eu.darken.butler.common.navigation.NavEvent
import eu.darken.butler.workspace.contracts.picker.DestinationHostedPicker
import eu.darken.butler.workspace.contracts.picker.HostedPickerRequests
import eu.darken.butler.workspace.core.Workspace
import eu.darken.butler.workspace.core.WorkspaceAction
import eu.darken.butler.workspace.core.hosted.FakeHostingRemote
import eu.darken.butler.workspace.core.hosted.HostedPickerCoordinator
import eu.darken.butler.workspace.ui.floatingbar.WorkspaceBarCollapseStates
import eu.darken.butler.workspace.ui.scroll.WorkspaceScrollPositions
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import eu.darken.butler.common.ca.toCaString
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import testhelpers.BaseTest
import testhelpers.coroutine.TestDispatcherProvider

class HostedPickerViewModelTest : BaseTest() {

    private val remote = FakeHostingRemote()

    private fun TestScope.coordinator() = HostedPickerCoordinator(appScope = backgroundScope, workspaceRemote = remote)

    private fun viewModel(coordinator: HostedPickerCoordinator, token: String) = HostedPickerViewModel(
        token = token,
        dispatcherProvider = TestDispatcherProvider(),
        coordinator = coordinator,
        workspaceRemote = remote,
        pageHosts = emptyMap(),
        scrollPositions = WorkspaceScrollPositions(),
        barCollapseStates = WorkspaceBarCollapseStates(),
    )

    @Test
    fun `a destination restored without its request dismisses itself`() = runTest(UnconfinedTestDispatcher()) {
        val token = Workspace.Id().longTag
        val vm = viewModel(coordinator(), token)

        vm.navEvents.first() shouldBe NavEvent.Dismiss(DestinationHostedPicker(token))
        remote.actions shouldBe emptyList()
    }

    @Test
    fun `shows the hosted picker, then dismisses its own entry once it closed`() = runTest(UnconfinedTestDispatcher()) {
        val coordinator = coordinator()
        val ticket = coordinator.register(HostedPickerRequests.Request())
        val vm = viewModel(coordinator, ticket.token)

        val showing = vm.state.first { it is HostedPickerViewModel.State.Showing }
            .shouldBeInstanceOf<HostedPickerViewModel.State.Showing>()
        showing.leaf.isHosted shouldBe true

        vm.close()

        remote.actions.filterIsInstance<WorkspaceAction.Close>().single().id shouldBe showing.leaf.id
        vm.navEvents.first() shouldBe NavEvent.Dismiss(ticket.destination)
    }

    @Test
    fun `waits on a picker that is still being created`() = runTest(UnconfinedTestDispatcher()) {
        val coordinator = coordinator()
        remote.createGate = CompletableDeferred()
        val ticket = coordinator.register(HostedPickerRequests.Request())

        viewModel(coordinator, ticket.token).state.first() shouldBe HostedPickerViewModel.State.Loading
    }

    @Test
    fun `a picker opened from the hosted one covers it, and closing it alone keeps the request`() =
        runTest(UnconfinedTestDispatcher()) {
            val coordinator = coordinator()
            val ticket = coordinator.register(HostedPickerRequests.Request())
            val vm = viewModel(coordinator, ticket.token)
            val pickerId = vm.showing().leaf.id
            val keyPicker = Workspace.Id()

            remote.infos.update {
                it + Workspace.Info(id = keyPicker, type = Workspace.Type.EXPLORER, title = "Key".toCaString(), callerWorkspaceId = pickerId)
            }
            vm.showing().leaf.id shouldBe keyPicker

            vm.closeWorkspace(keyPicker)

            remote.closes.map { it.id } shouldBe listOf(keyPicker)
            vm.showing().leaf.id shouldBe pickerId
            coordinator.attach(ticket.token).first()?.phase shouldBe HostedPickerCoordinator.Phase.OPEN
        }

    @Test
    fun `closing the hosted picker itself ends the request`() = runTest(UnconfinedTestDispatcher()) {
        val coordinator = coordinator()
        val ticket = coordinator.register(HostedPickerRequests.Request())
        val vm = viewModel(coordinator, ticket.token)
        val pickerId = vm.showing().leaf.id

        vm.closeWorkspace(pickerId)

        remote.closes.map { it.id } shouldBe listOf(pickerId)
        vm.navEvents.first() shouldBe NavEvent.Dismiss(ticket.destination)
    }

    @Test
    fun `tearing the screen down closes a picker it still shows`() = runTest(UnconfinedTestDispatcher()) {
        val coordinator = coordinator()
        val ticket = coordinator.register(HostedPickerRequests.Request())
        val store = ViewModelStore()
        val vm = ViewModelProvider(
            store,
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T = viewModel(coordinator, ticket.token) as T
            },
        )[HostedPickerViewModel::class.java]
        val pickerId = vm.showing().leaf.id

        store.clear()

        remote.closes.map { it.id } shouldBe listOf(pickerId)
        remote.infos.value shouldBe emptyList()
    }

    private suspend fun HostedPickerViewModel.showing() =
        state.first { it is HostedPickerViewModel.State.Showing } as HostedPickerViewModel.State.Showing
}
