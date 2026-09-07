package eu.darken.butler.apps.ui.apps

import android.content.pm.PackageInfo
import eu.darken.butler.apps.core.AppsWorkspace
import eu.darken.butler.apps.core.engine.AppItem
import eu.darken.butler.apps.ui.apps.dialogs.AppsDialogState
import eu.darken.butler.apps.ui.apps.elements.AppsActionBarItem
import eu.darken.butler.apps.ui.apps.preview.AppsMockDataProvider
import eu.darken.butler.common.ca.toCaString
import eu.darken.butler.common.pkgs.container.UninstalledPkg
import eu.darken.butler.common.user.UserHandle2
import eu.darken.butler.common.user.UserProfile2
import eu.darken.butler.workspace.core.Workspace
import eu.darken.butler.workspace.core.WorkspaceProvider
import eu.darken.butler.workspace.core.WorkspaceRemote
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import testhelpers.BaseTest
import testhelpers.coroutine.TestDispatcherProvider

/**
 * A package command needs an install to act on. The long-press dialog builds its own action items,
 * so filtering only in the action-bar builder would leave that path wide open.
 */
class AppsWorkspaceUninstalledGateTest : BaseTest() {

    private val installedApp = AppsMockDataProvider.createMockAppItem(
        packageName = "com.example.installed",
        label = "Installed App",
    )
    // Assembled by hand instead of via AppItem.from(): that reads PackageInfo.longVersionCode,
    // which an unmocked android.jar stub refuses to answer in a plain unit test. What the gate
    // reads is AppItem.isUninstalled, and that derives from the pkg being an UninstalledPkg.
    private val uninstalledApp = AppItem(
        pkg = UninstalledPkg(
            packageInfo = PackageInfo().apply { packageName = "com.example.uninstalled" },
            userHandle = UserHandle2(0),
        ),
        label = "Uninstalled App".toCaString(),
        icon = null,
        packageName = "com.example.uninstalled",
        versionName = null,
        versionCode = 0L,
        appSize = null,
        isSystemApp = false,
        isEnabled = true,
        isUpdatedSystemApp = false,
        installedAt = null,
        updatedAt = null,
        installerInfo = null,
        isSplitApk = false,
        isDebuggable = false,
        userProfile = UserProfile2(handle = UserHandle2(0)),
    )

    private lateinit var vm: AppsWorkspaceViewModel

    private fun createVM(selected: List<AppItem> = emptyList()): AppsWorkspaceViewModel {
        val apps = listOf(installedApp, uninstalledApp)
        val workspace = mockk<AppsWorkspace>(relaxed = true)
        every { workspace.state } returns flowOf(
            AppsWorkspace.State.Ready(
                apps = apps,
                filteredApps = apps,
                selectedAppIds = selected.map { it.pkg.installId }.toSet(),
                hasRoot = true,
            )
        )
        val id = Workspace.Id()
        return AppsWorkspaceViewModel(
            id = id,
            context = mockk(relaxed = true),
            dispatchers = TestDispatcherProvider(),
            workspaceProvider = mockk<WorkspaceProvider> { every { retrieve(id) } returns flowOf(workspace) },
            workspaceRemote = mockk<WorkspaceRemote>(relaxed = true),
            appsSettings = mockk(relaxed = true),
            appSizeCache = mockk(relaxed = true),
            tabViewStore = mockk(relaxed = true),
        ).also { vm = it }
    }

    private suspend fun dialogState(): AppsDialogState =
        vm.state.first().shouldBeInstanceOf<AppsWorkspaceViewModel.State.Ready>().dialogState

    @Test
    fun `an uninstalled entry raises no uninstall confirmation`() = runTest {
        createVM()

        vm.onPageAction(AppsPageAction.ActionBarClick(AppsActionBarItem.Uninstall(listOf(uninstalledApp))))

        dialogState() shouldBe AppsDialogState.None
    }

    @Test
    fun `an uninstalled entry raises no clear data confirmation`() = runTest {
        createVM()

        vm.onPageAction(AppsPageAction.ActionBarClick(AppsActionBarItem.ClearData(listOf(uninstalledApp))))

        dialogState() shouldBe AppsDialogState.None
    }

    @Test
    fun `an uninstalled entry raises no enable or disable confirmation`() = runTest {
        createVM()

        vm.onPageAction(AppsPageAction.ActionBarClick(AppsActionBarItem.Disable(listOf(uninstalledApp))))
        dialogState() shouldBe AppsDialogState.None

        vm.onPageAction(AppsPageAction.ActionBarClick(AppsActionBarItem.Enable(listOf(uninstalledApp))))
        dialogState() shouldBe AppsDialogState.None
    }

    @Test
    fun `a mixed selection acts on the installed entries only`() = runTest {
        createVM()

        vm.onPageAction(
            AppsPageAction.ActionBarClick(AppsActionBarItem.Uninstall(listOf(installedApp, uninstalledApp)))
        )

        dialogState().shouldBeInstanceOf<AppsDialogState.ConfirmUninstall>().apps shouldBe listOf(installedApp)
    }

    @Test
    fun `the action bar offers no package commands for an uninstalled selection`() = runTest {
        createVM(selected = listOf(uninstalledApp))

        val actions = vm.state.first()
            .shouldBeInstanceOf<AppsWorkspaceViewModel.State.Ready>()
            .availableActions

        actions.filterIsInstance<AppsActionBarItem.Uninstall>() shouldBe emptyList()
        actions.filterIsInstance<AppsActionBarItem.ClearData>() shouldBe emptyList()
        actions.filterIsInstance<AppsActionBarItem.Disable>() shouldBe emptyList()
        actions.filterIsInstance<AppsActionBarItem.Enable>() shouldBe emptyList()
        actions.filterIsInstance<AppsActionBarItem.ExportApk>().size shouldBe 1
    }
}
