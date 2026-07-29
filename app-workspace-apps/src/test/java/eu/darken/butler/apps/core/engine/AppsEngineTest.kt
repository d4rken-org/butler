package eu.darken.butler.apps.core.engine

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import eu.darken.butler.apps.core.AppSizeCache
import eu.darken.butler.apps.ui.apps.preview.AppsMockDataProvider
import eu.darken.butler.common.permissions.Permission
import eu.darken.butler.common.pkgs.Pkg
import eu.darken.butler.common.pkgs.PkgRepo
import eu.darken.butler.common.pkgs.features.InstallId
import eu.darken.butler.common.pkgs.pkgops.PkgOps
import eu.darken.butler.common.user.UserHandle2
import eu.darken.butler.common.user.UserManager2
import eu.darken.butler.setup.core.SetupStateProvider
import eu.darken.butler.workspace.contracts.apps.SortSettings
import eu.darken.butler.workspace.core.Workspace
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import testhelpers.BaseTest
import testhelpers.coroutine.TestDispatcherProvider

/**
 * Sizes are expensive, so the engine may only measure them while the user actually sorts by size -
 * and it has to stop measuring the moment they stop.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AppsEngineTest : BaseTest() {

    private val context = ApplicationProvider.getApplicationContext<Context>()

    private val pkgOps = mockk<PkgOps>()
    private val pkgRevision = MutableStateFlow(0L)
    private val installId = InstallId(Pkg.Id("com.test.app"), UserHandle2(0))

    private val installed = AppsMockDataProvider.createMockInstalled(
        packageName = installId.pkgId.name,
        label = "Test App",
    )

    private val pkgRepo = mockk<PkgRepo>().also {
        every { it.data } returns MutableStateFlow(PkgRepo.PkgData.from(listOf(installed)))
        every { it.revision } returns pkgRevision
    }

    private val userManager = mockk<UserManager2>().also {
        every { it.users } returns MutableStateFlow(emptySet())
    }

    private val setupStateProvider = mockk<SetupStateProvider>().also {
        every { it.state } returns flowOf(SetupStateProvider.State(modules = emptyMap()))
    }

    @Before
    fun setup() {
        mockkObject(Permission.PACKAGE_USAGE_STATS)
        every { Permission.PACKAGE_USAGE_STATS.isGranted(any()) } returns true
    }

    private fun TestScope.createEngine(): AppsEngine {
        val dispatcherProvider = TestDispatcherProvider(StandardTestDispatcher(testScheduler))
        return AppsEngine(
            workspaceId = Workspace.Id(),
            workspaceScope = backgroundScope,
            context = context,
            pkgRepo = pkgRepo,
            userManager = userManager,
            dispatcherProvider = dispatcherProvider,
            appSizeCache = AppSizeCache(
                appScope = backgroundScope,
                context = context,
                dispatcherProvider = dispatcherProvider,
                pkgRepo = pkgRepo,
                pkgOps = pkgOps,
                setupStateProvider = setupStateProvider,
            ),
        )
    }

    @Test
    fun `no sizes are measured while sorting by something else`() = runTest {
        coEvery { pkgOps.querySizeStats(any(), any()) } returns PkgOps.SizeStats(
            appBytes = 100,
            cacheBytes = 20,
            externalCacheBytes = null,
            dataBytes = 50,
        )
        val engine = createEngine()

        engine.updateSortSettings(SortSettings(mode = SortSettings.Mode.NAME))
        advanceUntilIdle()

        coVerify(exactly = 0) { pkgOps.querySizeStats(any(), any()) }
        engine.state.value.filteredApps.single().appSize shouldBe null
    }

    @Test
    fun `leaving the size sort cancels the batch in flight`() = runTest {
        val gate = CompletableDeferred<Unit>()
        coEvery { pkgOps.querySizeStats(any(), any()) } coAnswers {
            gate.await()
            PkgOps.SizeStats(appBytes = 100, cacheBytes = 20, externalCacheBytes = null, dataBytes = 50)
        }
        val engine = createEngine()

        engine.updateSortSettings(SortSettings(mode = SortSettings.Mode.SIZE))
        advanceUntilIdle()
        coVerify(exactly = 1) { pkgOps.querySizeStats(any(), any()) }

        engine.updateSortSettings(SortSettings(mode = SortSettings.Mode.NAME))
        advanceUntilIdle()

        // The batch never published, so nothing was cached and the row stays unmeasured.
        gate.complete(Unit)
        advanceUntilIdle()
        engine.state.value.filteredApps.single().appSize shouldBe null
    }
}
