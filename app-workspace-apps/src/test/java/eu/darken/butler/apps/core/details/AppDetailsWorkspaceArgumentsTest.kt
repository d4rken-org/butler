package eu.darken.butler.apps.core.details

import eu.darken.butler.apps.core.AppSizeCache
import eu.darken.butler.common.adb.AdbManager
import eu.darken.butler.common.files.Existence
import eu.darken.butler.common.files.APath
import eu.darken.butler.common.pkgs.Pkg
import eu.darken.butler.common.pkgs.features.InstallId
import eu.darken.butler.common.root.RootManager
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
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import testhelpers.BaseTest
import testhelpers.coroutine.TestDispatcherProvider

/**
 * [AppDetailsWorkspace.createArguments] serves two callers with opposite needs, decided by whether
 * this is a modal (a caller is set) or a tab of its own:
 * - as a modal it is only ever captured because the owning tab was paused, and the user expects the
 *   sub-tab they were on to come back;
 * - as a tab it IS what session save persists, where the Components sub-screen is transient
 *   navigation state that must not survive a restart.
 */
class AppDetailsWorkspaceArgumentsTest : BaseTest() {

    private val installId = InstallId(Pkg.Id("eu.darken.butler"), UserHandle2(0))

    private fun createWorkspace(arguments: AppDetailsArguments) = AppDetailsWorkspace(
        id = Workspace.Id(),
        creationArguments = arguments,
        context = mockk(relaxed = true),
        dispatcherProvider = TestDispatcherProvider(),
        pkgRepo = mockk(relaxed = true),
        pkgOps = mockk(relaxed = true),
        apkArchiveParser = mockk(relaxed = true),
        appSizeCache = mockk(relaxed = true) {
            every { snapshot } returns MutableStateFlow(AppSizeCache.Snapshot())
            every { isAvailable } returns MutableStateFlow(false)
        },
        // Explicit, not relaxed: an ABSENT answer would silently drop rows.
        gatewaySwitch = mockk { coEvery { existsStrict(any()) } returns Existence.PRESENT },
        pathPermissionCheck = mockk { every { monitor(any<APath<*>>()) } returns flowOf(PathRequirements()) },
        rootManager = mockk<RootManager> { every { useRoot } returns flowOf(false) },
        adbManager = mockk<AdbManager> { every { useAdb } returns flowOf(false) },
        workspaceRemote = mockk(relaxed = true),
    )

    @Test
    fun `a modal keeps the sub-tab the user was on`() = runTest(UnconfinedTestDispatcher()) {
        val workspace = createWorkspace(
            AppDetailsArguments(installId = installId, callerWorkspaceId = Workspace.Id())
        )
        workspace.updateSelectedTab(DetailTab.COMPONENTS)

        workspace.createArguments().initialTab shouldBe DetailTab.COMPONENTS
    }

    @Test
    fun `a tab always comes back on the overview`() = runTest(UnconfinedTestDispatcher()) {
        val workspace = createWorkspace(
            AppDetailsArguments(installId = installId, callerWorkspaceId = null)
        )
        workspace.updateSelectedTab(DetailTab.COMPONENTS)

        workspace.createArguments().initialTab shouldBe DetailTab.OVERVIEW
    }

    @Test
    fun `an app details overlay may be released with the tab that opened it`() {
        val arguments = AppDetailsArguments(installId = installId, callerWorkspaceId = Workspace.Id())

        arguments.pausableAsChild shouldBe true
        createWorkspace(arguments).info.value.pausableAsChild shouldBe true
    }
}
