package eu.darken.butler.apps.core

import eu.darken.butler.apps.core.engine.AppsEngine
import eu.darken.butler.apps.core.engine.AppsState
import eu.darken.butler.common.adb.AdbManager
import eu.darken.butler.common.datastore.DataStoreValue
import eu.darken.butler.common.root.RootManager
import eu.darken.butler.common.serialization.SerializationIOModule
import eu.darken.butler.workspace.contracts.apps.AppsArguments
import eu.darken.butler.workspace.contracts.apps.AppsViewStyle
import eu.darken.butler.workspace.contracts.apps.SortSettings
import eu.darken.butler.workspace.contracts.apps.TagFilterConfig
import eu.darken.butler.workspace.core.Workspace
import eu.darken.butler.workspace.ui.restore.WorkspaceViewPrefs
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import testhelpers.BaseTest
import testhelpers.coroutine.TestDispatcherProvider

/**
 * Resolution order of a resumed tab's view style: its own slot first, then the arguments it was
 * created or restored with, then the global default.
 */
class AppsWorkspaceViewStyleTest : BaseTest() {

    private val id = Workspace.Id()
    private val viewPrefs = WorkspaceViewPrefs()
    private val tabViewStore = AppsTabViewStore(viewPrefs, SerializationIOModule().json())

    private val globalDefault = AppsViewStyle(density = AppsViewStyle.Density.COMPACT)

    private fun createWorkspace(arguments: AppsArguments): AppsWorkspace {
        val engine = mockk<AppsEngine>(relaxed = true) {
            every { state } returns MutableStateFlow(AppsState())
        }
        return AppsWorkspace(
            id = id,
            creationArguments = arguments,
            dispatcherProvider = TestDispatcherProvider(),
            appsEngineFactory = mockk<AppsEngine.Factory> { every { create(any(), any()) } returns engine },
            appsSettings = mockk<AppsSettings>(relaxed = true).apply {
                every { defaultViewStyle } returns mockk<DataStoreValue<AppsViewStyle>>().apply {
                    every { flow } returns flowOf(globalDefault)
                }
            },
            tabViewStore = tabViewStore,
            appSizeCache = mockk(relaxed = true),
            pkgOps = mockk(relaxed = true),
            rootManager = mockk<RootManager> { every { useRoot } returns flowOf(false) },
            adbManager = mockk<AdbManager> { every { useAdb } returns flowOf(false) },
        )
    }

    private val heldArguments = AppsArguments.Default(
        filterConfig = TagFilterConfig(),
        sortSettings = SortSettings(),
        viewStyle = AppsViewStyle(mode = AppsViewStyle.Mode.GRID),
    )

    private suspend fun AppsWorkspace.readyViewStyle() =
        state.filterIsInstance<AppsWorkspace.State.Ready>().first().viewStyle

    @Test
    fun `a resumed tab keeps the style in its slot over the one in its arguments`() =
        runTest(UnconfinedTestDispatcher()) {
            val stored = AppsViewStyle(density = AppsViewStyle.Density.DETAILED)
            tabViewStore.setViewStyle(id, stored)

            createWorkspace(heldArguments).readyViewStyle() shouldBe stored
        }

    @Test
    fun `a tab without a slot falls back to its arguments`() = runTest(UnconfinedTestDispatcher()) {
        createWorkspace(heldArguments).readyViewStyle() shouldBe heldArguments.viewStyle
    }

    @Test
    fun `a tab without a slot or arguments falls back to the global default`() =
        runTest(UnconfinedTestDispatcher()) {
            createWorkspace(AppsArguments.Default()).readyViewStyle() shouldBe globalDefault
        }

    @Test
    fun `a style written to the slot reaches the workspace state`() = runTest(UnconfinedTestDispatcher()) {
        val workspace = createWorkspace(AppsArguments.Default())
        workspace.readyViewStyle()

        val detailedGrid = AppsViewStyle(
            mode = AppsViewStyle.Mode.GRID,
            density = AppsViewStyle.Density.DETAILED,
        )
        tabViewStore.setViewStyle(id, detailedGrid)

        workspace.readyViewStyle() shouldBe detailedGrid
    }
}
