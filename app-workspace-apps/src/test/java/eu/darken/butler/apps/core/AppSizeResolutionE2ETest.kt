package eu.darken.butler.apps.core

import android.app.AppOpsManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import eu.darken.butler.apps.core.details.AppDetailsWorkspace
import eu.darken.butler.apps.core.engine.AppsEngine
import eu.darken.butler.common.files.APath
import eu.darken.butler.common.pkgs.Pkg
import eu.darken.butler.common.pkgs.PkgRepo
import eu.darken.butler.common.pkgs.features.InstallId
import eu.darken.butler.common.pkgs.pkgops.PkgOps
import eu.darken.butler.common.user.UserHandle2
import eu.darken.butler.common.user.UserManager2
import eu.darken.butler.permissions.core.PathRequirements
import eu.darken.butler.setup.core.SetupStateProvider
import eu.darken.butler.apps.ui.apps.preview.AppsMockDataProvider
import eu.darken.butler.workspace.contracts.apps.AppDetailsArguments
import eu.darken.butler.workspace.contracts.apps.SortSettings
import eu.darken.butler.workspace.core.Workspace
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import testhelpers.BaseTest
import testhelpers.coroutine.TestDispatcherProvider

/**
 * End-to-end cover for "sizes actually get measured": from selecting the size sort (and from opening
 * App Details) all the way through to a [PkgOps.querySizeStats] call.
 *
 * Deliberately does NOT mock [eu.darken.butler.common.permissions.Permission], so the real
 * availability gate in [AppSizeCache] is part of what's under test - both resolution call sites bail
 * out silently before logging when that gate is closed, which makes it the one shared way for the
 * whole feature to go inert.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AppSizeResolutionE2ETest : BaseTest() {

    private val context = ApplicationProvider.getApplicationContext<Context>()

    private val installId = InstallId(Pkg.Id("com.test.app"), UserHandle2(0))
    private val installed = AppsMockDataProvider.createMockInstalled(
        packageName = installId.pkgId.name,
        label = "Test App",
    )

    private val pkgOps = mockk<PkgOps>(relaxed = true)
    private val pkgRevision = MutableStateFlow(0L)
    private val pkgRepo = mockk<PkgRepo>().also {
        every { it.data } returns MutableStateFlow(PkgRepo.PkgData.from(listOf(installed)))
        every { it.revision } returns pkgRevision
    }
    private val scopes = mutableListOf<CoroutineScope>()

    @After
    fun teardown() {
        scopes.forEach { it.cancel() }
        scopes.clear()
    }

    private fun TestScope.newScope(): CoroutineScope =
        CoroutineScope(StandardTestDispatcher(testScheduler)).also { scopes += it }

    private fun TestScope.createCache(dispatcherProvider: TestDispatcherProvider) = AppSizeCache(
        appScope = newScope(),
        context = context,
        dispatcherProvider = dispatcherProvider,
        pkgRepo = pkgRepo,
        pkgOps = pkgOps,
        setupStateProvider = mockk<SetupStateProvider>().also {
            every { it.state } returns flowOf(SetupStateProvider.State(modules = emptyMap()))
        },
    )

    private fun setUsageAccess(granted: Boolean) {
        val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        shadowOf(appOps).setMode(
            AppOpsManager.OPSTR_GET_USAGE_STATS,
            context.packageManager.getApplicationInfo(context.packageName, 0).uid,
            context.packageName,
            if (granted) AppOpsManager.MODE_ALLOWED else AppOpsManager.MODE_IGNORED,
        )
    }

    private fun stubSizes() {
        coEvery { pkgOps.querySizeStats(any(), any()) } returns PkgOps.SizeStats(
            appBytes = 100,
            cacheBytes = 20,
            externalCacheBytes = null,
            dataBytes = 50,
        )
    }

    @Test
    fun `usage access is available so resolution is not gated off`() = runTest {
        val cache = createCache(TestDispatcherProvider(StandardTestDispatcher(testScheduler)))
        advanceUntilIdle()

        cache.isAvailable.value shouldBe true
    }

    @Test
    fun `selecting the size sort measures the visible apps`() = runTest {
        stubSizes()
        val dispatcherProvider = TestDispatcherProvider(StandardTestDispatcher(testScheduler))
        val engine = AppsEngine(
            workspaceId = Workspace.Id(),
            workspaceScope = newScope(),
            context = context,
            pkgRepo = pkgRepo,
            userManager = mockk<UserManager2>().also { every { it.users } returns MutableStateFlow(emptySet()) },
            dispatcherProvider = dispatcherProvider,
            appSizeCache = createCache(dispatcherProvider),
        )
        advanceUntilIdle()

        // Nothing measured while the default (name) sort is active.
        coVerify(exactly = 0) { pkgOps.querySizeStats(any(), any()) }

        engine.updateSortSettings(SortSettings(mode = SortSettings.Mode.SIZE))
        advanceUntilIdle()

        coVerify(exactly = 1) { pkgOps.querySizeStats(installId, any()) }
        engine.state.value.filteredApps.single().appSize shouldBe 150L
    }

    /**
     * Usage access granted while Butler is already running, by a route that never touches Butler's
     * own Setup screen. Nothing re-reads the permission on the measurement path unless resolve()
     * does it, so gating the trigger on the cached flag latches the feature off for the whole
     * process - no chips, no breakdown, no log line, until a restart.
     */
    @Test
    fun `usage access granted at runtime is picked up without a restart`() = runTest {
        setUsageAccess(granted = false)
        stubSizes()
        // Two apps, so narrowing the search below actually changes the filtered ids and produces a
        // trigger. With a single app the list is identical before and after and gets deduped away.
        val other = AppsMockDataProvider.createMockInstalled(packageName = "com.other.app", label = "Other")
        every { pkgRepo.data } returns MutableStateFlow(PkgRepo.PkgData.from(listOf(installed, other)))
        val dispatcherProvider = TestDispatcherProvider(StandardTestDispatcher(testScheduler))
        val engine = AppsEngine(
            workspaceId = Workspace.Id(),
            workspaceScope = newScope(),
            context = context,
            pkgRepo = pkgRepo,
            userManager = mockk<UserManager2>().also { every { it.users } returns MutableStateFlow(emptySet()) },
            dispatcherProvider = dispatcherProvider,
            appSizeCache = createCache(dispatcherProvider),
        )

        engine.updateSortSettings(SortSettings(mode = SortSettings.Mode.SIZE))
        advanceUntilIdle()
        coVerify(exactly = 0) { pkgOps.querySizeStats(any(), any()) }

        // Granted outside Butler: no sort change, no revision bump, no setup-state emission and no
        // sort dialog - none of the paths that already call refreshAvailability().
        setUsageAccess(granted = true)
        advanceUntilIdle()

        // Ordinary list activity is all it may take to recover.
        engine.updateSearchQuery(installId.pkgId.name)
        advanceUntilIdle()

        coVerify(atLeast = 1) { pkgOps.querySizeStats(installId, any()) }
        engine.state.value.filteredApps.single().appSize shouldBe 150L
    }

    private fun detailsWorkspace(
        dispatcherProvider: TestDispatcherProvider,
        cache: AppSizeCache,
    ) = AppDetailsWorkspace(
        id = Workspace.Id(),
        creationArguments = AppDetailsArguments(installId = installId),
        context = context,
        dispatcherProvider = dispatcherProvider,
        pkgRepo = pkgRepo,
        pkgOps = pkgOps,
        apkArchiveParser = mockk(relaxed = true),
        appSizeCache = cache,
        // Explicit, not relaxed: a false exists() would mean ABSENT and silently drop rows.
        gatewaySwitch = mockk { coEvery { exists(any()) } returns true },
        pathPermissionCheck = mockk { every { monitor(any<APath<*>>()) } returns flowOf(PathRequirements()) },
        rootManager = mockk<eu.darken.butler.common.root.RootManager> {
            every { useRoot } returns flowOf(false)
        },
        adbManager = mockk<eu.darken.butler.common.adb.AdbManager> {
            every { useAdb } returns flowOf(false)
        },
        workspaceRemote = mockk(relaxed = true),
    )

    @Test
    fun `opening app details measures that app`() = runTest {
        stubSizes()
        val dispatcherProvider = TestDispatcherProvider(StandardTestDispatcher(testScheduler))
        val workspace = detailsWorkspace(dispatcherProvider, createCache(dispatcherProvider))
        advanceUntilIdle()

        coVerify(exactly = 1) { pkgOps.querySizeStats(installId, any()) }

        val state = workspace.state.first()
        state.app?.appSize shouldBe 100L
        state.app?.dataSize shouldBe 30L
        state.app?.cacheSize shouldBe 20L
        state.app?.totalSize shouldBe 150L
    }

    /**
     * Usage access revoked while Butler is already running, on an app whose size is already
     * measured. Nothing on the details screen re-reads the permission on its own, and the size
     * collector returns early once the app has been attempted — so unless entering the screen
     * re-derives it first, the card keeps showing numbers Android no longer updates instead of the
     * setup block, for the rest of the process.
     */
    @Test
    fun `usage access revoked at runtime surfaces the setup state on a measured app`() = runTest {
        stubSizes()
        val dispatcherProvider = TestDispatcherProvider(StandardTestDispatcher(testScheduler))
        val cache = createCache(dispatcherProvider)

        val measured = detailsWorkspace(dispatcherProvider, cache)
        advanceUntilIdle()
        measured.state.first().let {
            it.sizesAvailable shouldBe true
            it.app?.totalSize shouldBe 150L
        }

        // Revoked outside Butler: no setup-state emission, no revision bump, none of the paths that
        // already call refreshAvailability().
        setUsageAccess(granted = false)
        advanceUntilIdle()

        // Re-entering the screen is all the user does, and the size is already cached.
        val reopened = detailsWorkspace(dispatcherProvider, cache)
        advanceUntilIdle()

        reopened.state.first().sizesAvailable shouldBe false
    }
}
