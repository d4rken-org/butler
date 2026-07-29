package eu.darken.butler.apps.core.details

import eu.darken.butler.apps.core.details.components.AppComponentsLoader
import eu.darken.butler.apps.core.details.components.ComponentEntry
import eu.darken.butler.apps.core.details.components.ComponentKind
import eu.darken.butler.apps.core.details.components.ComponentToggleState
import eu.darken.butler.apps.core.details.components.ComponentsData
import eu.darken.butler.apps.ui.details.components.ComponentsActionBarItem
import eu.darken.butler.common.pkgs.features.Installed
import eu.darken.butler.workspace.contracts.apps.DetailTab
import eu.darken.butler.workspace.core.Workspace
import eu.darken.butler.workspace.core.WorkspaceProvider
import eu.darken.butler.workspace.core.WorkspaceRemote
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import org.junit.jupiter.api.Test
import testhelpers.BaseTest
import testhelpers.coroutine.TestDispatcherProvider

/**
 * A batch confirm dialog captures the selection it was raised for. Nothing in the controller knows
 * about that dialog, so `onAppChanged` (a package update) or a route change can empty the selection
 * while it is up — the captured entries must never be applied afterwards.
 */
class AppDetailsWorkspaceComponentConfirmTest : BaseTest() {

    private val activity = ComponentEntry(
        kind = ComponentKind.ACTIVITY,
        packageName = "com.example.app",
        className = "com.example.app.MainActivity",
        isExported = true,
    )
    private val service = ComponentEntry(
        kind = ComponentKind.SERVICE,
        packageName = "com.example.app",
        className = "com.example.app.sync.SyncService",
        isExported = false,
    )
    private val data = ComponentsData(activities = listOf(activity), services = listOf(service))

    private val appInfo = AppInfo(
        install = mockk<Installed> {
            every { packageName } returns "com.example.app"
            every { versionCode } returns 1L
        },
    )

    private lateinit var workspace: AppDetailsWorkspace

    private fun createVM(): AppDetailsWorkspaceViewModel {
        val loader = mockk<AppComponentsLoader>()
        coEvery { loader.load(any()) } returns data
        // Empty on purpose: withEnabledStates() then hands back the very entries declared above, so
        // the assertions can compare instances instead of re-deriving the enriched copies.
        coEvery { loader.resolveEnabledStates(data) } returns emptyMap()

        workspace = mockk<AppDetailsWorkspace>(relaxed = true).apply {
            every { state } returns flowOf(
                AppDetailsWorkspace.State(
                    app = appInfo,
                    selectedTab = DetailTab.COMPONENTS,
                    componentToggleState = ComponentToggleState.AVAILABLE,
                )
            )
        }

        val id = Workspace.Id()
        return AppDetailsWorkspaceViewModel(
            id = id,
            context = mockk(relaxed = true),
            dispatchers = TestDispatcherProvider(),
            workspaceProvider = mockk<WorkspaceProvider> { every { retrieve(id) } returns flowOf(workspace) },
            workspaceRemote = mockk<WorkspaceRemote>(relaxed = true),
            componentsLoader = loader,
        )
    }

    @Test
    fun `a selection change retires the pending confirm request`() {
        val vm = createVM()
        vm.onComponentLongPressed(activity)

        vm.onComponentAction(ComponentsActionBarItem.Disable(listOf(activity)))
        vm.componentConfirm.value!!.entries.map { it.key } shouldBe listOf(activity.key)

        vm.onComponentLongPressed(service)

        vm.componentConfirm.value shouldBe null
    }

    @Test
    fun `confirming a stale request applies nothing`() {
        val vm = createVM()
        vm.onComponentLongPressed(activity)
        vm.onComponentAction(ComponentsActionBarItem.Disable(listOf(activity)))
        val stale = vm.componentConfirm.value!!

        // The dialog's own callback still holds the captured request after the selection moved on.
        vm.onComponentLongPressed(service)
        vm.onComponentConfirm(stale)

        coVerify(exactly = 0) { workspace.setComponentsEnabled(any(), any()) }
    }

    @Test
    fun `confirming an unchanged selection applies the live entries`() {
        val vm = createVM()
        vm.onComponentLongPressed(activity)
        vm.onComponentAction(ComponentsActionBarItem.Disable(listOf(activity)))

        vm.onComponentConfirm(vm.componentConfirm.value!!)

        coVerify(exactly = 1) { workspace.setComponentsEnabled(listOf(activity), false) }
        vm.componentConfirm.value shouldBe null
    }
}
