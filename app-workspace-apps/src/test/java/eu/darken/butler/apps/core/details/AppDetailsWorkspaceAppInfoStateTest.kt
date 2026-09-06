package eu.darken.butler.apps.core.details

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import eu.darken.butler.apps.core.AppSizeCache
import eu.darken.butler.apps.ui.apps.preview.AppsMockDataProvider
import eu.darken.butler.common.adb.AdbManager
import eu.darken.butler.common.files.APath
import eu.darken.butler.common.files.Existence
import eu.darken.butler.common.pkgs.Pkg
import eu.darken.butler.common.pkgs.PkgRepo
import eu.darken.butler.common.pkgs.features.InstallId
import eu.darken.butler.common.pkgs.features.Installed
import eu.darken.butler.common.root.RootManager
import eu.darken.butler.common.user.UserHandle2
import eu.darken.butler.permissions.core.PathRequirements
import eu.darken.butler.workspace.contracts.apps.AppDetailsArguments
import eu.darken.butler.workspace.core.Workspace
import eu.darken.butler.workspace.core.WorkspaceAction
import eu.darken.butler.workspace.core.WorkspaceRemote
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
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
import java.io.IOException

/**
 * A failing package source is not the same situation as a removed package: it says nothing about
 * the app, so the screen has to stay put and stay recoverable.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AppDetailsWorkspaceAppInfoStateTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()

    private val installId = InstallId(Pkg.Id(PKG), UserHandle2(0))
    // A shared flow with nothing emitted yet, because that is what PkgRepo.data is: a cache flow
    // that stays silent until the package cache resolves. A StateFlow seeded with an empty PkgData
    // would instead say "queried, found nothing", which is a different situation (Gone).
    private val pkgData = MutableSharedFlow<PkgRepo.PkgData>(
        replay = 1,
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    private val workspaceRemote = mockk<WorkspaceRemote>(relaxed = true)

    private val installed: Installed = AppsMockDataProvider.createMockInstalled(packageName = PKG, label = "Butler")

    private fun TestScope.createWorkspace(): AppDetailsWorkspace = AppDetailsWorkspace(
        id = Workspace.Id(),
        creationArguments = AppDetailsArguments(installId = installId),
        context = context,
        dispatcherProvider = TestDispatcherProvider(StandardTestDispatcher(testScheduler)),
        pkgRepo = mockk<PkgRepo> { every { data } returns pkgData },
        pkgOps = mockk(relaxed = true),
        apkArchiveParser = mockk(relaxed = true),
        appSizeCache = mockk(relaxed = true) {
            every { snapshot } returns MutableStateFlow(AppSizeCache.Snapshot())
            every { isAvailable } returns MutableStateFlow(false)
        },
        gatewaySwitch = mockk { coEvery { existsStrict(any()) } returns Existence.PRESENT },
        pathPermissionCheck = mockk { every { monitor(any<APath<*>>()) } returns flowOf(PathRequirements()) },
        // Real flows, not relaxed mocks: `state` combines both, and a relaxed flow never emits,
        // so the combine would never produce a state to collect.
        rootManager = mockk<RootManager> { every { useRoot } returns flowOf(false) },
        adbManager = mockk<AdbManager> { every { useAdb } returns flowOf(false) },
        workspaceRemote = workspaceRemote,
    )

    private fun TestScope.statesOf(
        workspace: AppDetailsWorkspace,
        block: TestScope.() -> Unit = {},
    ): List<AppDetailsWorkspace.State> {
        val seen = mutableListOf<AppDetailsWorkspace.State>()
        val job = launch { workspace.state.collect { seen += it } }
        advanceUntilIdle()
        block()
        advanceUntilIdle()
        job.cancel()
        return seen
    }

    @Test
    fun `nothing is known before the first emission`() = runTest {
        val seen = statesOf(createWorkspace())

        seen.first().appState shouldBe AppInfoState.Loading
    }

    @Test
    fun `a matching package is ready`() = runTest {
        val seen = statesOf(createWorkspace()) {
            pkgData.tryEmit(PkgRepo.PkgData.from(listOf(installed)))
        }

        seen.last().appState.shouldBeInstanceOf<AppInfoState.Ready>().info.packageName shouldBe PKG
    }

    @Test
    fun `a package that is not there is gone`() = runTest {
        val seen = statesOf(createWorkspace()) {
            pkgData.tryEmit(PkgRepo.PkgData.from(emptyList()))
        }

        seen.last().appState shouldBe AppInfoState.Gone
    }

    @Test
    fun `a failing source is not a missing package`() = runTest {
        val boom = IOException("Package source unavailable")
        val seen = statesOf(createWorkspace()) {
            pkgData.tryEmit(PkgRepo.PkgData(error = boom))
        }

        seen.last().appState.shouldBeInstanceOf<AppInfoState.SourceError>().error shouldBe boom
    }

    @Test
    fun `the workspace closes once the package is gone`() = runTest {
        val workspace = createWorkspace()

        pkgData.tryEmit(PkgRepo.PkgData.from(listOf(installed)))
        advanceUntilIdle()
        pkgData.tryEmit(PkgRepo.PkgData.from(emptyList()))
        advanceUntilIdle()

        coVerify { workspaceRemote.execute(any<WorkspaceAction.Close>()) }
    }

    @Test
    fun `a source error does not close the workspace`() = runTest {
        val workspace = createWorkspace()

        pkgData.tryEmit(PkgRepo.PkgData.from(listOf(installed)))
        advanceUntilIdle()
        pkgData.tryEmit(PkgRepo.PkgData(error = IOException("Package source unavailable")))
        advanceUntilIdle()

        coVerify(exactly = 0) { workspaceRemote.execute(any<WorkspaceAction.Close>()) }
    }

    /** The whole point of not using a terminal `catch`: the same instance has to recover. */
    @Test
    fun `a recovered source is ready again`() = runTest {
        val seen = statesOf(createWorkspace()) {
            pkgData.tryEmit(PkgRepo.PkgData(error = IOException("Package source unavailable")))
            advanceUntilIdle()
            pkgData.tryEmit(PkgRepo.PkgData.from(listOf(installed)))
        }

        seen.map { it.appState }.any { it is AppInfoState.SourceError } shouldBe true
        seen.last().appState.shouldBeInstanceOf<AppInfoState.Ready>().info.packageName shouldBe PKG
    }

    companion object {
        private const val PKG = "eu.darken.butler"
    }
}
