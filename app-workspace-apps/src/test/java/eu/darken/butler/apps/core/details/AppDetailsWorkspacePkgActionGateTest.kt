package eu.darken.butler.apps.core.details

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import eu.darken.butler.apps.core.AppSizeCache
import eu.darken.butler.apps.core.details.components.ComponentEntry
import eu.darken.butler.apps.core.details.components.ComponentKind
import eu.darken.butler.apps.ui.apps.preview.AppsMockDataProvider
import eu.darken.butler.common.files.APath
import eu.darken.butler.common.files.Existence
import eu.darken.butler.common.pkgs.Pkg
import eu.darken.butler.common.pkgs.PkgRepo
import eu.darken.butler.common.pkgs.features.InstallId
import eu.darken.butler.common.pkgs.pkgops.PkgOps
import eu.darken.butler.common.user.UserHandle2
import eu.darken.butler.permissions.core.PathRequirements
import eu.darken.butler.workspace.contracts.apps.AppDetailsArguments
import eu.darken.butler.workspace.core.Workspace
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import testhelpers.coroutine.TestDispatcherProvider

/**
 * Disabling the rows in Compose is feedback, not a barrier: two taps can both be delivered before
 * a recomposition, and an uninstall dispatched twice is a second `pm uninstall` on a package the
 * first one is still removing.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AppDetailsWorkspacePkgActionGateTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()

    private val installId = InstallId(Pkg.Id(PKG), UserHandle2(0))
    private val installed = AppsMockDataProvider.createMockInstalled(packageName = PKG, label = "Butler")
    private val appInfo = AppInfo(install = installed)

    private val pkgOps = mockk<PkgOps>(relaxed = true)

    private fun TestScope.createWorkspace(): AppDetailsWorkspace = AppDetailsWorkspace(
        id = Workspace.Id(),
        creationArguments = AppDetailsArguments(installId = installId),
        context = context,
        dispatcherProvider = TestDispatcherProvider(StandardTestDispatcher(testScheduler)),
        pkgRepo = mockk<PkgRepo> {
            every { data } returns MutableStateFlow(PkgRepo.PkgData.from(listOf(installed)))
            coEvery { refresh() } returns listOf(installed)
        },
        pkgOps = pkgOps,
        apkArchiveParser = mockk(relaxed = true),
        appSizeCache = mockk(relaxed = true) {
            every { snapshot } returns MutableStateFlow(AppSizeCache.Snapshot())
            every { isAvailable } returns MutableStateFlow(false)
        },
        gatewaySwitch = mockk { coEvery { existsStrict(any()) } returns Existence.PRESENT },
        pathPermissionCheck = mockk { every { monitor(any<APath<*>>()) } returns flowOf(PathRequirements()) },
        rootManager = mockk(relaxed = true),
        adbManager = mockk(relaxed = true),
        workspaceRemote = mockk(relaxed = true),
    )

    @Test
    fun `a second uninstall while the first runs is rejected`() = runTest {
        val release = Channel<Unit>(Channel.UNLIMITED)
        coEvery { pkgOps.uninstall(any(), any()) } coAnswers {
            release.receive()
            true
        }
        val workspace = createWorkspace()

        launch { workspace.uninstallApp(appInfo) }
        advanceUntilIdle()
        launch { workspace.uninstallApp(appInfo) }
        advanceUntilIdle()

        release.send(Unit)
        advanceUntilIdle()

        coVerify(exactly = 1) { pkgOps.uninstall(any(), any()) }
    }

    @Test
    fun `a later uninstall goes through once the first finished`() = runTest {
        coEvery { pkgOps.uninstall(any(), any()) } returns true
        val workspace = createWorkspace()

        workspace.uninstallApp(appInfo)
        workspace.uninstallApp(appInfo)

        coVerify(exactly = 2) { pkgOps.uninstall(any(), any()) }
    }

    /** One lock across both would let a running app action block the component screen. */
    @Test
    fun `a component toggle is not blocked by a running app action`() = runTest {
        val release = Channel<Unit>(Channel.UNLIMITED)
        coEvery { pkgOps.uninstall(any(), any()) } coAnswers {
            release.receive()
            true
        }
        val workspace = createWorkspace()

        launch { workspace.uninstallApp(appInfo) }
        advanceUntilIdle()
        workspace.setComponentsEnabled(
            listOf(
                ComponentEntry(
                    kind = ComponentKind.ACTIVITY,
                    packageName = PKG,
                    className = "$PKG.MainActivity",
                    isExported = true,
                )
            ),
            enabled = false,
        )

        coVerify(exactly = 1) { pkgOps.changeComponentState(any(), any(), any(), any()) }

        release.send(Unit)
        advanceUntilIdle()
    }

    companion object {
        private const val PKG = "eu.darken.butler"
    }
}
