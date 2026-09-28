package eu.darken.butler.apps.core.details

import eu.darken.butler.apps.core.details.components.AppComponentsLoader
import eu.darken.butler.apps.core.details.components.ComponentEntry
import eu.darken.butler.apps.core.details.components.ComponentKind
import eu.darken.butler.apps.core.details.components.ComponentToggleState
import eu.darken.butler.apps.core.details.components.ComponentsData
import eu.darken.butler.apps.core.operations.PackageCommand
import eu.darken.butler.apps.ui.details.components.ComponentsActionBarItem
import eu.darken.butler.common.ca.toCaString
import eu.darken.butler.common.navigation.DestinationSetup
import eu.darken.butler.common.navigation.DestinationUpgrade
import eu.darken.butler.common.navigation.NavEvent
import eu.darken.butler.common.pkgs.Pkg
import eu.darken.butler.common.pkgs.features.InstallId
import eu.darken.butler.common.pkgs.features.Installed
import eu.darken.butler.common.user.UserHandle2
import eu.darken.butler.setup.core.SetupModule
import eu.darken.butler.workspace.contracts.apps.DetailTab
import eu.darken.butler.workspace.core.Workspace
import eu.darken.butler.workspace.core.WorkspaceProvider
import eu.darken.butler.workspace.core.WorkspaceRemote
import eu.darken.butler.workspace.core.operations.OperationFocusRequest
import eu.darken.butler.workspace.ui.operations.OperationsDisplayState
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
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
            // The command names its target by install and label.
            every { installId } returns InstallId(Pkg.Id("com.example.app"), UserHandle2(0))
            every { label } returns "Example App".toCaString()
        },
    )

    private lateinit var workspace: AppDetailsWorkspace

    private fun createVM(
        upgradeRepo: FakeUpgradeRepo = FakeUpgradeRepo(),
        toggleState: ComponentToggleState = ComponentToggleState.AVAILABLE,
    ): AppDetailsWorkspaceViewModel {
        val loader = mockk<AppComponentsLoader>()
        coEvery { loader.load(any()) } returns data
        // Empty on purpose: withEnabledStates() then hands back the very entries declared above, so
        // the assertions can compare instances instead of re-deriving the enriched copies.
        coEvery { loader.resolveEnabledStates(data) } returns emptyMap()

        workspace = mockk<AppDetailsWorkspace>(relaxed = true).apply {
            every { state } returns flowOf(
                AppDetailsWorkspace.State(
                    appState = AppInfoState.Ready(appInfo),
                    selectedTab = DetailTab.COMPONENTS,
                    componentToggleState = toggleState,
                )
            )
            // A relaxed mock answers a nullable object return with a chained mock, not null, and a
            // chained ManagedOperation never reaches a Completed state for the refresh to wait on.
            coEvery { submit(any()) } returns null
        }

        val id = Workspace.Id()
        return AppDetailsWorkspaceViewModel(
            id = id,
            context = mockk(relaxed = true),
            dispatchers = TestDispatcherProvider(),
            workspaceProvider = mockk<WorkspaceProvider> { every { retrieve(id) } returns flowOf(workspace) },
            workspaceRemote = mockk<WorkspaceRemote>(relaxed = true),
            componentsLoader = loader,
            chromeFactory = mockk {
                every { create(any(), any()) } returns mockk(relaxed = true) {
                    every { operations } returns flowOf(OperationsDisplayState())
                    every { pendingConflicts } returns flowOf(emptyMap())
                }
            },
            operationFocusRequest = OperationFocusRequest(),
            upgradeRepo = upgradeRepo,
        )
    }

    @Test
    fun `a selection change retires the pending confirm request`() {
        val vm = createVM()
        vm.onComponentSelectionChanged(setOf(activity.key))

        vm.onComponentAction(ComponentsActionBarItem.Disable(listOf(activity)))
        vm.componentConfirm.value!!.entries.map { it.key } shouldBe listOf(activity.key)

        vm.onComponentSelectionChanged(setOf(service.key))

        vm.componentConfirm.value shouldBe null
    }

    @Test
    fun `confirming a stale request applies nothing`() {
        val vm = createVM()
        vm.onComponentSelectionChanged(setOf(activity.key))
        vm.onComponentAction(ComponentsActionBarItem.Disable(listOf(activity)))
        val stale = vm.componentConfirm.value!!

        // The dialog's own callback still holds the captured request after the selection moved on.
        vm.onComponentSelectionChanged(setOf(service.key))
        vm.onComponentConfirm(stale)

        coVerify(exactly = 0) { workspace.submit(any()) }
    }

    @Test
    fun `confirming an unchanged selection applies the live entries`() {
        val vm = createVM()
        vm.onComponentSelectionChanged(setOf(activity.key))
        vm.onComponentAction(ComponentsActionBarItem.Disable(listOf(activity)))

        vm.onComponentConfirm(vm.componentConfirm.value!!)

        coVerify(exactly = 1) {
            workspace.submit(
                match { it is PackageCommand.SetComponents && it.entries == listOf(activity) && !it.enabled }
            )
        }
        vm.componentConfirm.value shouldBe null
    }

    @Test
    fun `a free user is sent to the upgrade screen instead of the disable confirmation`() {
        val vm = createVM(FakeUpgradeRepo(pro = false))
        val events = collectNavEvents(vm)
        vm.onComponentSelectionChanged(setOf(activity.key))

        vm.onComponentAction(ComponentsActionBarItem.Disable(listOf(activity)))

        vm.componentConfirm.value shouldBe null
        events shouldBe listOf(NavEvent.GoTo(DestinationUpgrade))
    }

    @Test
    fun `a free user still reaches the enable confirmation`() {
        val vm = createVM(FakeUpgradeRepo(pro = false))
        vm.onComponentSelectionChanged(setOf(activity.key))

        vm.onComponentAction(ComponentsActionBarItem.Enable(listOf(activity)))

        vm.componentConfirm.value!!.enable shouldBe true
    }

    @Test
    fun `a free user cannot disable a single component from the sheet`() {
        val vm = createVM(FakeUpgradeRepo(pro = false))
        val events = collectNavEvents(vm)

        vm.onSetComponentEnabled(activity, enabled = false)

        coVerify(exactly = 0) { workspace.submit(any()) }
        events shouldBe listOf(NavEvent.GoTo(DestinationUpgrade))
    }

    @Test
    fun `a free user re-enables a single component from the sheet`() {
        val vm = createVM(FakeUpgradeRepo(pro = false))

        vm.onSetComponentEnabled(activity, enabled = true)

        coVerify(exactly = 1) {
            workspace.submit(match { it is PackageCommand.SetComponents && it.enabled })
        }
    }

    @Test
    fun `a selection change while the disable gate waits drops the request`() {
        val upgradeRepo = FakeUpgradeRepo(pro = false, settled = false)
        val vm = createVM(upgradeRepo)
        val events = collectNavEvents(vm)
        vm.onComponentSelectionChanged(setOf(activity.key))

        vm.onComponentAction(ComponentsActionBarItem.Disable(listOf(activity)))
        vm.onComponentSelectionChanged(setOf(service.key))
        upgradeRepo.set(pro = true)

        vm.componentConfirm.value shouldBe null
        // Resolved as Pro, so it was the selection check that dropped the request.
        events shouldBe emptyList()
    }

    @Test
    fun `confirming a disable re-checks the entitlement`() {
        val upgradeRepo = FakeUpgradeRepo(pro = true)
        val vm = createVM(upgradeRepo)
        val events = collectNavEvents(vm)
        vm.onComponentSelectionChanged(setOf(activity.key))
        vm.onComponentAction(ComponentsActionBarItem.Disable(listOf(activity)))
        val request = vm.componentConfirm.value!!

        upgradeRepo.set(pro = false)
        vm.onComponentConfirm(request)

        coVerify(exactly = 0) { workspace.submit(any()) }
        events shouldBe listOf(NavEvent.GoTo(DestinationUpgrade))
    }

    @Test
    fun `repeated disable taps while billing connects open the upgrade screen once`() {
        val upgradeRepo = FakeUpgradeRepo(pro = false, settled = false)
        val vm = createVM(upgradeRepo)
        val events = collectNavEvents(vm)
        vm.onComponentSelectionChanged(setOf(activity.key))

        repeat(3) { vm.onComponentAction(ComponentsActionBarItem.Disable(listOf(activity))) }
        upgradeRepo.set(pro = false)

        events shouldBe listOf(NavEvent.GoTo(DestinationUpgrade))
    }

    @Test
    fun `a finished disable gate does not block the next tap`() {
        val vm = createVM(FakeUpgradeRepo(pro = false))
        val events = collectNavEvents(vm)
        vm.onComponentSelectionChanged(setOf(activity.key))

        vm.onComponentAction(ComponentsActionBarItem.Disable(listOf(activity)))
        vm.onComponentAction(ComponentsActionBarItem.Disable(listOf(activity)))

        events shouldBe listOf(NavEvent.GoTo(DestinationUpgrade), NavEvent.GoTo(DestinationUpgrade))
    }

    @Test
    fun `a free user without elevated access is sent to the upgrade screen, not to setup`() {
        val vm = createVM(FakeUpgradeRepo(pro = false), ComponentToggleState.NEEDS_SETUP)
        val events = collectNavEvents(vm)

        vm.onSetComponentEnabled(activity, enabled = false)

        coVerify(exactly = 0) { workspace.submit(any()) }
        events shouldBe listOf(NavEvent.GoTo(DestinationUpgrade))
    }

    @Test
    fun `a pro user without elevated access is sent to setup instead of disabling`() {
        val vm = createVM(FakeUpgradeRepo(pro = true), ComponentToggleState.NEEDS_SETUP)
        val events = collectNavEvents(vm)

        vm.onSetComponentEnabled(activity, enabled = false)

        coVerify(exactly = 0) { workspace.submit(any()) }
        events shouldBe listOf(NavEvent.GoTo(setupDestination))
    }

    @Test
    fun `billing that settles as pro sends a disable without elevated access to setup`() {
        val upgradeRepo = FakeUpgradeRepo(pro = false, settled = false)
        val vm = createVM(upgradeRepo, ComponentToggleState.NEEDS_SETUP)
        val events = collectNavEvents(vm)

        vm.onSetComponentEnabled(activity, enabled = false)
        upgradeRepo.set(pro = true)

        coVerify(exactly = 0) { workspace.submit(any()) }
        events shouldBe listOf(NavEvent.GoTo(setupDestination))
    }

    @Test
    fun `a free user without elevated access is sent to setup to re-enable`() {
        val vm = createVM(FakeUpgradeRepo(pro = false), ComponentToggleState.NEEDS_SETUP)
        val events = collectNavEvents(vm)

        vm.onSetComponentEnabled(activity, enabled = true)

        coVerify(exactly = 0) { workspace.submit(any()) }
        events shouldBe listOf(NavEvent.GoTo(setupDestination))
    }

    private val setupDestination = DestinationSetup(
        typeFilter = setOf(SetupModule.Type.ROOT, SetupModule.Type.SHIZUKU),
        showCompleted = true,
    )

    private fun collectNavEvents(vm: AppDetailsWorkspaceViewModel): List<NavEvent> {
        val events = mutableListOf<NavEvent>()
        CoroutineScope(Dispatchers.Unconfined).launch { vm.navEvents.toList(events) }
        return events
    }
}
