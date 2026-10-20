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
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.flow.first
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
}
