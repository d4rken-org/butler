package eu.darken.butler.apps.core

import eu.darken.butler.apps.core.engine.AppItem
import eu.darken.butler.apps.core.engine.AppsEngine
import eu.darken.butler.apps.core.engine.AppsState
import eu.darken.butler.common.adb.AdbManager
import eu.darken.butler.common.pkgs.pkgops.PkgOps
import eu.darken.butler.common.root.RootManager
import eu.darken.butler.workspace.contracts.apps.AppsArguments
import eu.darken.butler.workspace.contracts.apps.AppsViewStyle
import eu.darken.butler.workspace.contracts.apps.SortSettings
import eu.darken.butler.workspace.contracts.apps.TagFilterConfig
import eu.darken.butler.workspace.core.Workspace
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import testhelpers.BaseTest
import testhelpers.coroutine.TestDispatcherProvider

/**
 * Package operations don't go through OperationsManager, so [Workspace.Info.operationCount] stays 0
 * while apps are being uninstalled or cleared. [Workspace.Info.isPausable] is the only signal that
 * keeps the workspace from being released mid-operation.
 */
class AppsWorkspacePausableTest : BaseTest() {

    private fun createWorkspace(pkgOps: PkgOps): AppsWorkspace {
        val engine = mockk<AppsEngine>(relaxed = true) {
            every { state } returns MutableStateFlow(AppsState())
        }
        return AppsWorkspace(
            id = Workspace.Id(),
            creationArguments = AppsArguments.Default(
                filterConfig = TagFilterConfig(),
                sortSettings = SortSettings(),
                viewStyle = AppsViewStyle.default(),
            ),
            dispatcherProvider = TestDispatcherProvider(),
            appsEngineFactory = mockk<AppsEngine.Factory> { every { create(any(), any()) } returns engine },
            appsSettings = mockk(relaxed = true),
            pkgOps = pkgOps,
            rootManager = mockk<RootManager> { every { useRoot } returns flowOf(false) },
            adbManager = mockk<AdbManager> { every { useAdb } returns flowOf(false) },
        )
    }

    @Test
    fun `an idle apps workspace can be paused`() = runTest(UnconfinedTestDispatcher()) {
        val workspace = createWorkspace(mockk(relaxed = true))

        workspace.info.value.isPausable shouldBe true
    }

    @Test
    fun `an in-flight package operation blocks pausing`() = runTest(UnconfinedTestDispatcher()) {
        val gate = CompletableDeferred<Unit>()
        val pkgOps = mockk<PkgOps>(relaxed = true) {
            coEvery { uninstall(any(), any()) } coAnswers {
                gate.await()
                true
            }
        }
        val workspace = createWorkspace(pkgOps)

        val uninstalling = launch { workspace.uninstallApps(listOf(mockk<AppItem>(relaxed = true))) }
        workspace.info.value.isPausable shouldBe false

        gate.complete(Unit)
        uninstalling.join()

        workspace.info.value.isPausable shouldBe true
    }
}
