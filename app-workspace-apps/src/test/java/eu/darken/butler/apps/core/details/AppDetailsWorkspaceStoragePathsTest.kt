package eu.darken.butler.apps.core.details

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import eu.darken.butler.apps.core.AppPath
import eu.darken.butler.apps.core.AppSizeCache
import eu.darken.butler.apps.ui.apps.preview.AppsMockDataProvider
import eu.darken.butler.common.adb.AdbManager
import eu.darken.butler.common.files.APath
import eu.darken.butler.common.files.Existence
import eu.darken.butler.common.files.GatewaySwitch
import eu.darken.butler.common.files.LocalPath
import eu.darken.butler.common.pkgs.Pkg
import eu.darken.butler.common.pkgs.PkgRepo
import eu.darken.butler.common.pkgs.features.InstallId
import eu.darken.butler.common.pkgs.features.Installed
import eu.darken.butler.common.root.RootManager
import eu.darken.butler.common.user.UserHandle2
import eu.darken.butler.permissions.core.PathPermissionCheck
import eu.darken.butler.permissions.core.PathRequirements
import eu.darken.butler.setup.core.SetupModule
import eu.darken.butler.workspace.contracts.apps.AppDetailsArguments
import eu.darken.butler.workspace.core.Workspace
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
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
 * Only the internal data row can ever be withheld, and only when the directory is positively known
 * to be absent, which the gateway can only answer with root. The external row is always offered,
 * see `the external row is never withheld`.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AppDetailsWorkspaceStoragePathsTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()

    private val primaryInstallId = InstallId(Pkg.Id(PKG), UserHandle2(0))
    private val workProfileInstallId = InstallId(Pkg.Id(PKG), UserHandle2(10))

    private val gatewaySwitch = mockk<GatewaySwitch>().apply {
        coEvery { existsStrict(any()) } returns Existence.PRESENT
    }
    private val pathPermissionCheck = mockk<PathPermissionCheck>().apply {
        every { monitor(any<APath<*>>()) } returns flowOf(PathRequirements())
    }
    private val useRootFlow = MutableStateFlow(true)

    private fun installedFor(target: InstallId): Installed {
        val base = AppsMockDataProvider.createMockInstalled(packageName = PKG, label = "Test App")
        return object : Installed by base {
            override val userHandle: UserHandle2 = target.userHandle
            override val installId: InstallId = target
        }
    }

    private fun TestScope.createWorkspace(installId: InstallId = primaryInstallId) = AppDetailsWorkspace(
        id = Workspace.Id(),
        creationArguments = AppDetailsArguments(installId = installId),
        context = context,
        dispatcherProvider = TestDispatcherProvider(StandardTestDispatcher(testScheduler)),
        pkgRepo = mockk<PkgRepo>().also {
            every { it.data } returns MutableStateFlow(PkgRepo.PkgData.from(listOf(installedFor(installId))))
        },
        pkgOps = mockk(relaxed = true),
        apkArchiveParser = mockk(relaxed = true),
        appSizeCache = mockk(relaxed = true) {
            every { snapshot } returns MutableStateFlow(AppSizeCache.Snapshot())
            every { isAvailable } returns MutableStateFlow(false)
        },
        gatewaySwitch = gatewaySwitch,
        pathPermissionCheck = pathPermissionCheck,
        rootManager = mockk<RootManager> { every { useRoot } returns useRootFlow },
        adbManager = mockk<AdbManager> { every { useAdb } returns flowOf(false) },
        workspaceRemote = mockk(relaxed = true),
    )

    private fun TestScope.pathsOf(workspace: AppDetailsWorkspace): List<AppPath> {
        val seen = mutableListOf<AppDetailsWorkspace.State>()
        val job = launch { workspace.state.collect { seen += it } }
        advanceUntilIdle()
        job.cancel()
        return seen.last().availablePaths
    }

    @Test
    fun `a directory that is not there is not offered`() = runTest {
        coEvery { gatewaySwitch.existsStrict(match { it.path == INTERNAL }) } returns Existence.ABSENT

        pathsOf(createWorkspace()).map { it.path.path } shouldBe listOf(EXTERNAL)
    }

    /** A probe that could not tell is not an absence, the row stays offered. */
    @Test
    fun `a directory the probe could not check is still offered`() = runTest {
        coEvery { gatewaySwitch.existsStrict(match { it.path == INTERNAL }) } returns Existence.UNKNOWN

        pathsOf(createWorkspace()).map { it.path.path } shouldBe listOf(INTERNAL, EXTERNAL)
    }

    @Test
    fun `a directory that is there is offered`() = runTest {
        pathsOf(createWorkspace()).map { it.path.path } shouldBe listOf(INTERNAL, EXTERNAL)
    }

    /** Without root the gateway cannot tell "not there" from "not allowed to look". */
    @Test
    fun `without root a negative answer is not trusted`() = runTest {
        useRootFlow.value = false
        coEvery { gatewaySwitch.existsStrict(any()) } returns Existence.ABSENT

        pathsOf(createWorkspace()).map { it.path.path } shouldBe listOf(INTERNAL, EXTERNAL)
    }

    /**
     * The probe for the external path escalates to the root client, which stats it in the root
     * host's own mount namespace - launched without mount-master, so it does not present other
     * apps' `Android/data` at all. A false answer there is never an absence.
     */
    @Test
    fun `the external row is never withheld`() = runTest {
        coEvery { gatewaySwitch.existsStrict(any()) } returns Existence.ABSENT

        pathsOf(createWorkspace()).map { it.path.path } shouldBe listOf(EXTERNAL)
        coVerify(exactly = 0) { gatewaySwitch.existsStrict(match { it.path == EXTERNAL }) }

        useRootFlow.value = false

        pathsOf(createWorkspace()).map { it.path.path } shouldBe listOf(INTERNAL, EXTERNAL)
    }

    @Test
    @Config(sdk = [29])
    fun `the external row is never withheld on older Android versions either`() = runTest {
        coEvery { gatewaySwitch.existsStrict(any()) } returns Existence.ABSENT

        pathsOf(createWorkspace()).map { it.path.path } shouldBe listOf(EXTERNAL)
    }

    /** The paths are user 0's, so for another user their absence says nothing. */
    @Test
    fun `a work profile install keeps its rows`() = runTest {
        coEvery { gatewaySwitch.existsStrict(any()) } returns Existence.ABSENT

        pathsOf(createWorkspace(workProfileInstallId)).map { it.path.path } shouldBe listOf(INTERNAL, EXTERNAL)
    }

    @Test
    fun `a failed probe keeps the rows`() = runTest {
        coEvery { gatewaySwitch.existsStrict(any()) } throws IOException("nope")

        pathsOf(createWorkspace()).map { it.path.path } shouldBe listOf(INTERNAL, EXTERNAL)
    }

    /** Cancellation is not an answer, so it must not be caught and turned into one. */
    @Test
    fun `a cancelled probe resolves nothing`() = runTest {
        coEvery { gatewaySwitch.existsStrict(any()) } throws CancellationException("cancelled")

        pathsOf(createWorkspace()).map { it.path.path } shouldBe listOf(INTERNAL, EXTERNAL)

        coVerify(exactly = 0) { pathPermissionCheck.monitor(any<APath<*>>()) }
    }

    @Test
    fun `enabling root re-probes a row that was shown for lack of knowledge`() = runTest {
        useRootFlow.value = false
        coEvery { gatewaySwitch.existsStrict(match { it.path == INTERNAL }) } returns Existence.ABSENT
        val workspace = createWorkspace()

        val seen = mutableListOf<AppDetailsWorkspace.State>()
        val job = launch { workspace.state.collect { seen += it } }
        advanceUntilIdle()

        seen.last().availablePaths.map { it.path.path } shouldBe listOf(INTERNAL, EXTERNAL)

        useRootFlow.value = true
        advanceUntilIdle()

        seen.last().availablePaths.map { it.path.path } shouldBe listOf(EXTERNAL)

        job.cancel()
    }

    /**
     * A pause releases the instance and a resume builds a new one, so nothing may be remembered
     * across that boundary - the directory may have been created in the meantime.
     */
    @Test
    fun `a resumed workspace probes again`() = runTest {
        coEvery { gatewaySwitch.existsStrict(match { it.path == INTERNAL }) } returns Existence.ABSENT
        pathsOf(createWorkspace()).map { it.path.path } shouldBe listOf(EXTERNAL)

        coEvery { gatewaySwitch.existsStrict(any()) } returns Existence.PRESENT

        pathsOf(createWorkspace()).map { it.path.path } shouldBe listOf(INTERNAL, EXTERNAL)
    }

    @Test
    fun `a row that needs root says so`() = runTest {
        every { pathPermissionCheck.monitor(match<APath<*>> { it.path == INTERNAL }) } returns flowOf(
            PathRequirements(combos = setOf(setOf(SetupModule.Type.ROOT)))
        )

        val paths = pathsOf(createWorkspace())

        paths.first().requirement?.get(context) shouldBe "Requires root"
        paths.last().requirement shouldBe null
    }

    @Test
    fun `a row that needs root or Shizuku says so`() = runTest {
        every { pathPermissionCheck.monitor(match<APath<*>> { it.path == INTERNAL }) } returns flowOf(
            PathRequirements(
                combos = setOf(setOf(SetupModule.Type.ROOT), setOf(SetupModule.Type.SHIZUKU)),
            )
        )

        pathsOf(createWorkspace()).first().requirement?.get(context) shouldBe "Requires root or Shizuku"
    }

    /** An existing SAF grant makes the path accessible as it is, so there is nothing to announce. */
    @Test
    fun `a row with a working alternative says nothing`() = runTest {
        every { pathPermissionCheck.monitor(match<APath<*>> { it.path == EXTERNAL }) } returns flowOf(
            PathRequirements(
                combos = setOf(setOf(SetupModule.Type.ROOT)),
                alternativePath = LocalPath.build("/storage/emulated/0/Android/data/$PKG"),
            )
        )

        pathsOf(createWorkspace()).last().requirement shouldBe null
    }

    companion object {
        private const val PKG = "eu.darken.butler"
        private const val INTERNAL = "/data/data/$PKG"
        private const val EXTERNAL = "/storage/emulated/0/Android/data/$PKG"
    }
}
