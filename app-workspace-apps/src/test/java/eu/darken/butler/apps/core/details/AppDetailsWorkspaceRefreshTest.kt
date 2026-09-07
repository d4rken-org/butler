package eu.darken.butler.apps.core.details

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import eu.darken.butler.apps.core.AppSizeCache
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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import testhelpers.coroutine.TestDispatcherProvider
import java.io.IOException

/**
 * Enabling or disabling an app leaves the cached package data stale, so the screen kept showing the
 * old state until something else happened to refresh it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AppDetailsWorkspaceRefreshTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()

    private val installId = InstallId(Pkg.Id(PKG), UserHandle2(0))
    private val installed = AppsMockDataProvider.createMockInstalled(packageName = PKG, label = "Butler")
    private val appInfo = AppInfo(install = installed)

    private val pkgRepo = mockk<PkgRepo>().also {
        every { it.data } returns MutableStateFlow(PkgRepo.PkgData.from(listOf(installed)))
        coEvery { it.refresh() } returns listOf(installed)
    }
    private val pkgOps = mockk<PkgOps>(relaxed = true)

    private fun TestScope.createWorkspace(): AppDetailsWorkspace = AppDetailsWorkspace(
        id = Workspace.Id(),
        creationArguments = AppDetailsArguments(installId = installId),
        context = context,
        dispatcherProvider = TestDispatcherProvider(StandardTestDispatcher(testScheduler)),
        pkgRepo = pkgRepo,
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
    fun `enabling an app refreshes the package data`() = runTest {
        createWorkspace().setAppEnabled(appInfo, enabled = true)

        coVerify(exactly = 1) { pkgRepo.refresh() }
    }

    @Test
    fun `uninstalling refreshes the package data`() = runTest {
        createWorkspace().uninstallApp(appInfo)

        coVerify(exactly = 1) { pkgRepo.refresh() }
    }

    /** A force-stop changes no package data, and a refresh re-gathers every source. */
    @Test
    fun `force stopping does not refresh the package data`() = runTest {
        createWorkspace().forceStopApp(appInfo)

        coVerify(exactly = 0) { pkgRepo.refresh() }
    }

    /** The operation succeeded; the source error reaches the screen through the state flow. */
    @Test
    fun `a failing refresh does not fail the operation`() = runTest {
        coEvery { pkgRepo.refresh() } throws IOException("Package source unavailable")

        createWorkspace().setAppEnabled(appInfo, enabled = false)

        coVerify(exactly = 1) { pkgRepo.refresh() }
    }

    companion object {
        private const val PKG = "eu.darken.butler"
    }
}
