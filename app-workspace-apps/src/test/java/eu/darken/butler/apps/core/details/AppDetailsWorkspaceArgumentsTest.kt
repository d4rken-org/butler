package eu.darken.butler.apps.core.details

import eu.darken.butler.common.adb.AdbManager
import eu.darken.butler.common.root.RootManager
import eu.darken.butler.workspace.contracts.apps.AppDetailsArguments
import eu.darken.butler.workspace.contracts.apps.DetailTab
import eu.darken.butler.workspace.core.Workspace
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
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

    private fun createWorkspace(arguments: AppDetailsArguments) = AppDetailsWorkspace(
        id = Workspace.Id(),
        creationArguments = arguments,
        dispatcherProvider = TestDispatcherProvider(),
        pkgRepo = mockk(relaxed = true),
        rootManager = mockk<RootManager> { every { useRoot } returns flowOf(false) },
        adbManager = mockk<AdbManager> { every { useAdb } returns flowOf(false) },
        workspaceRemote = mockk(relaxed = true),
    )

    @Test
    fun `a modal keeps the sub-tab the user was on`() = runTest(UnconfinedTestDispatcher()) {
        val workspace = createWorkspace(
            AppDetailsArguments(packageName = "eu.darken.butler", callerWorkspaceId = Workspace.Id())
        )
        workspace.updateSelectedTab(DetailTab.COMPONENTS)

        workspace.createArguments().initialTab shouldBe DetailTab.COMPONENTS
    }

    @Test
    fun `a tab always comes back on the overview`() = runTest(UnconfinedTestDispatcher()) {
        val workspace = createWorkspace(
            AppDetailsArguments(packageName = "eu.darken.butler", callerWorkspaceId = null)
        )
        workspace.updateSelectedTab(DetailTab.COMPONENTS)

        workspace.createArguments().initialTab shouldBe DetailTab.OVERVIEW
    }

    @Test
    fun `an app details overlay may be released with the tab that opened it`() {
        val arguments = AppDetailsArguments(packageName = "eu.darken.butler", callerWorkspaceId = Workspace.Id())

        arguments.pausableAsChild shouldBe true
        createWorkspace(arguments).info.value.pausableAsChild shouldBe true
    }
}
