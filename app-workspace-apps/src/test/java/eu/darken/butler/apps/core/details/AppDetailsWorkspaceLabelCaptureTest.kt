package eu.darken.butler.apps.core.details

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import eu.darken.butler.apps.core.AppSizeCache
import eu.darken.butler.common.ca.toCaString
import eu.darken.butler.common.files.APath
import eu.darken.butler.common.pkgs.Pkg
import eu.darken.butler.common.pkgs.PkgRepo
import eu.darken.butler.common.pkgs.features.InstallId
import eu.darken.butler.common.pkgs.features.Installed
import eu.darken.butler.common.user.UserHandle2
import eu.darken.butler.permissions.core.PathRequirements
import eu.darken.butler.workspace.contracts.apps.AppDetailsArguments
import eu.darken.butler.workspace.contracts.apps.DetailTab
import eu.darken.butler.workspace.core.Workspace
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
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
 * The label a paused App details tab shows comes from the arguments the live tab hands back, so the
 * live tab has to capture it while the package data is available.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AppDetailsWorkspaceLabelCaptureTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()

    private val installId = InstallId(Pkg.Id(PKG), UserHandle2(0))
    private val pkgData = MutableStateFlow(PkgRepo.PkgData())

    private fun installedPkg(label: String?): Installed = mockk<Installed>().also { pkg ->
        every { pkg.id } returns installId.pkgId
        every { pkg.userHandle } returns installId.userHandle
        every { pkg.installId } returns installId
        every { pkg.packageName } returns PKG
        every { pkg.label } returns label?.toCaString()
    }

    private fun TestScope.createWorkspace(appLabel: String? = null): AppDetailsWorkspace {
        val pkgRepo = mockk<PkgRepo>()
        every { pkgRepo.data } returns pkgData

        return AppDetailsWorkspace(
            id = Workspace.Id(),
            creationArguments = AppDetailsArguments(
                installId = installId,
                appLabel = appLabel,
                initialTab = DetailTab.COMPONENTS,
            ),
            context = context,
            dispatcherProvider = TestDispatcherProvider(StandardTestDispatcher(testScheduler)),
            pkgRepo = pkgRepo,
            pkgOps = mockk(relaxed = true),
            apkArchiveParser = mockk(relaxed = true),
            appSizeCache = mockk(relaxed = true) {
                every { snapshot } returns MutableStateFlow(AppSizeCache.Snapshot())
                every { isAvailable } returns MutableStateFlow(false)
            },
            // Explicit, not relaxed: a false exists() would mean ABSENT and silently drop rows.
            gatewaySwitch = mockk { coEvery { exists(any()) } returns true },
            pathPermissionCheck = mockk { every { monitor(any<APath<*>>()) } returns flowOf(PathRequirements()) },
            rootManager = mockk(relaxed = true),
            adbManager = mockk(relaxed = true),
            workspaceRemote = mockk(relaxed = true),
        )
    }

    @Test
    fun `the resolved label is captured for the paused tab`() = runTest {
        val workspace = createWorkspace()

        pkgData.value = PkgRepo.PkgData.from(listOf(installedPkg("Butler")))
        advanceUntilIdle()

        workspace.createArguments().appLabel shouldBe "Butler"
    }

    @Test
    fun `the captured arguments still reopen on the overview tab`() = runTest {
        val workspace = createWorkspace()

        pkgData.value = PkgRepo.PkgData.from(listOf(installedPkg("Butler")))
        advanceUntilIdle()

        workspace.createArguments().initialTab shouldBe DetailTab.OVERVIEW
    }

    @Test
    fun `a vanished package does not erase the captured label`() = runTest {
        val workspace = createWorkspace()

        pkgData.value = PkgRepo.PkgData.from(listOf(installedPkg("Butler")))
        advanceUntilIdle()

        pkgData.value = PkgRepo.PkgData()
        advanceUntilIdle()

        workspace.createArguments().appLabel shouldBe "Butler"
    }

    @Test
    fun `a blank label does not erase the captured label`() = runTest {
        val workspace = createWorkspace()

        pkgData.value = PkgRepo.PkgData.from(listOf(installedPkg("Butler")))
        advanceUntilIdle()

        pkgData.value = PkgRepo.PkgData.from(listOf(installedPkg("   ")))
        advanceUntilIdle()

        workspace.createArguments().appLabel shouldBe "Butler"
    }

    @Test
    fun `a label that is just the package name is not worth caching`() = runTest {
        val workspace = createWorkspace()

        pkgData.value = PkgRepo.PkgData.from(listOf(installedPkg(PKG)))
        advanceUntilIdle()

        workspace.createArguments().appLabel shouldBe null
    }

    companion object {
        private const val PKG = "eu.darken.butler"
    }
}
