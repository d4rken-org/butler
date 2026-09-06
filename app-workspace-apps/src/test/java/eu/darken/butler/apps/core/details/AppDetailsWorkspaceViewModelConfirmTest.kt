package eu.darken.butler.apps.core.details

import android.app.Application
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import eu.darken.butler.apps.core.details.components.ComponentsData
import eu.darken.butler.apps.ui.apps.preview.AppsMockDataProvider
import eu.darken.butler.apps.ui.details.AppDetailsConfirmRequest
import eu.darken.butler.workspace.core.Workspace
import eu.darken.butler.workspace.core.WorkspaceProvider
import eu.darken.butler.workspace.core.WorkspaceRemote
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import testhelpers.coroutine.TestDispatcherProvider

/**
 * Clearing data has no undo and no system dialog in front of it, and an elevated uninstall skips
 * Android's confirmation entirely — both need Butler's own.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AppDetailsWorkspaceViewModelConfirmTest {

    private val context = ApplicationProvider.getApplicationContext<Application>()

    private val appInfo = AppsMockDataProvider.Presets.chrome
    private val workspaceState = MutableStateFlow(
        AppDetailsWorkspace.State(appState = AppInfoState.Ready(appInfo)),
    )

    private lateinit var workspace: AppDetailsWorkspace

    private fun createVM(hasRoot: Boolean = true, hasAdb: Boolean = false): AppDetailsWorkspaceViewModel {
        workspaceState.value = AppDetailsWorkspace.State(
            appState = AppInfoState.Ready(appInfo),
            hasRoot = hasRoot,
            hasAdb = hasAdb,
        )
        workspace = mockk<AppDetailsWorkspace>(relaxed = true).apply {
            every { state } returns workspaceState
        }
        val id = Workspace.Id()
        return AppDetailsWorkspaceViewModel(
            id = id,
            context = context,
            dispatchers = TestDispatcherProvider(),
            workspaceProvider = mockk<WorkspaceProvider> { every { retrieve(id) } returns flowOf(workspace) },
            workspaceRemote = mockk<WorkspaceRemote>(relaxed = true),
            appSizeCache = mockk(relaxed = true),
            componentsLoader = mockk(relaxed = true) {
                coEvery { load(any()) } returns ComponentsData()
                coEvery { resolveEnabledStates(any()) } returns emptyMap()
            },
        )
    }

    private fun nextStartedActivity(): Intent? = shadowOf(context).nextStartedActivity

    @Test
    fun `clearing data asks first`() = runTest {
        val vm = createVM()

        vm.onClearData(appInfo)

        vm.appConfirm.value.shouldBeInstanceOf<AppDetailsConfirmRequest.ClearData>()
        coVerify(exactly = 0) { workspace.clearDataApp(any()) }
    }

    @Test
    fun `a confirmed clear data is dispatched once`() = runTest {
        val vm = createVM()
        vm.onClearData(appInfo)
        val request = vm.appConfirm.value!!

        vm.onAppConfirm(request)
        // The dialog's callback can be delivered twice before it leaves composition.
        vm.onAppConfirm(request)

        coVerify(exactly = 1) { workspace.clearDataApp(appInfo) }
        vm.appConfirm.value shouldBe null
    }

    @Test
    fun `an elevated uninstall asks first`() = runTest {
        val vm = createVM(hasRoot = true)

        vm.onUninstall(appInfo)

        vm.appConfirm.value.shouldBeInstanceOf<AppDetailsConfirmRequest.Uninstall>()
        coVerify(exactly = 0) { workspace.uninstallApp(any()) }
    }

    @Test
    fun `a confirmed uninstall is dispatched`() = runTest {
        val vm = createVM(hasRoot = true)
        vm.onUninstall(appInfo)

        vm.onAppConfirm(vm.appConfirm.value!!)

        coVerify(exactly = 1) { workspace.uninstallApp(appInfo) }
    }

    /** Without elevated access Android's own dialog is the confirmation, so Butler must not add one. */
    @Test
    fun `without elevated access the system dialog does the asking`() = runTest {
        val vm = createVM(hasRoot = false, hasAdb = false)

        vm.onUninstall(appInfo)

        vm.appConfirm.value shouldBe null
        coVerify(exactly = 0) { workspace.uninstallApp(any()) }
        nextStartedActivity()?.action shouldBe Intent.ACTION_DELETE
    }

    @Test
    fun `a pending request is retired when its target goes away`() = runTest {
        val vm = createVM()
        vm.onClearData(appInfo)

        workspaceState.value = AppDetailsWorkspace.State(appState = AppInfoState.Gone)

        vm.appConfirm.value shouldBe null
    }

    @Test
    fun `confirming a retired request dispatches nothing`() = runTest {
        val vm = createVM()
        vm.onClearData(appInfo)
        val stale = vm.appConfirm.value!!

        workspaceState.value = AppDetailsWorkspace.State(appState = AppInfoState.Gone)
        vm.onAppConfirm(stale)

        coVerify(exactly = 0) { workspace.clearDataApp(any()) }
    }
}
