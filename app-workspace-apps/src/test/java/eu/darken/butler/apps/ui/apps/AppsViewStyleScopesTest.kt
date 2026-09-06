package eu.darken.butler.apps.ui.apps

import eu.darken.butler.apps.core.AppsSettings
import eu.darken.butler.apps.core.AppsTabViewStore
import eu.darken.butler.apps.core.AppsWorkspace
import eu.darken.butler.common.datastore.DataStoreValue
import eu.darken.butler.common.serialization.SerializationIOModule
import eu.darken.butler.workspace.contracts.apps.AppsViewStyle
import eu.darken.butler.workspace.core.Workspace
import eu.darken.butler.workspace.core.WorkspaceProvider
import eu.darken.butler.workspace.core.WorkspaceRemote
import eu.darken.butler.workspace.ui.restore.WorkspaceViewPrefs
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import testhelpers.BaseTest
import testhelpers.coroutine.TestDispatcherProvider

/**
 * The view options sheet has three write scopes and each one has to touch exactly its own storage:
 * live changes belong to the tab, "apply to all tabs" to every Apps tab's slot, and only
 * "set as default" moves the setting a new tab starts from.
 */
class AppsViewStyleScopesTest : BaseTest() {

    private val id = Workspace.Id()
    private val otherAppsTab = Workspace.Id()
    private val explorerTab = Workspace.Id()

    private val viewPrefs = WorkspaceViewPrefs()
    private val tabViewStore = AppsTabViewStore(viewPrefs, SerializationIOModule().json())

    private val globalStyle = MutableStateFlow(AppsViewStyle.default())
    private val styleStore = mockk<DataStoreValue<AppsViewStyle>>().apply {
        every { flow } returns globalStyle
        coEvery { update(any()) } answers {
            val old = globalStyle.value
            val new = firstArg<(AppsViewStyle) -> AppsViewStyle?>().invoke(old) ?: old
            globalStyle.value = new
            DataStoreValue.Updated(old, new)
        }
    }

    private fun tabInfo(tabId: Workspace.Id, type: Workspace.Type) = mockk<Workspace.Info>().apply {
        every { this@apply.id } returns tabId
        every { this@apply.type } returns type
    }

    private fun createVM(): AppsWorkspaceViewModel = AppsWorkspaceViewModel(
        id = id,
        context = mockk(relaxed = true),
        dispatchers = TestDispatcherProvider(),
        workspaceProvider = mockk<WorkspaceProvider> {
            every { retrieve(id) } returns flowOf(mockk<AppsWorkspace>(relaxed = true))
        },
        workspaceRemote = mockk<WorkspaceRemote>(relaxed = true).apply {
            every { state } returns flowOf(
                WorkspaceRemote.State(
                    listOf(
                        tabInfo(id, Workspace.Type.APPS),
                        tabInfo(otherAppsTab, Workspace.Type.APPS),
                        tabInfo(explorerTab, Workspace.Type.EXPLORER),
                    )
                )
            )
        },
        appsSettings = mockk<AppsSettings>(relaxed = true).apply {
            every { defaultViewStyle } returns styleStore
        },
        appSizeCache = mockk(relaxed = true),
        tabViewStore = tabViewStore,
    )

    @Test
    fun `the live path writes the tab slot and leaves the default alone`() = runTest {
        val vm = createVM()
        val grid = AppsViewStyle(mode = AppsViewStyle.Mode.GRID)

        vm.onPageAction(AppsPageAction.ViewStyle.ApplyToTab(grid))

        tabViewStore.currentViewStyle(id) shouldBe grid
        globalStyle.value shouldBe AppsViewStyle.default()
        coVerify(exactly = 0) { styleStore.update(any()) }
    }

    @Test
    fun `applying to all tabs writes every apps tab's slot`() = runTest {
        val vm = createVM()
        val detailed = AppsViewStyle(density = AppsViewStyle.Density.DETAILED)

        vm.onPageAction(AppsPageAction.ViewStyle.ApplyToAllTabs(detailed))

        tabViewStore.currentViewStyle(id) shouldBe detailed
        tabViewStore.currentViewStyle(otherAppsTab) shouldBe detailed
        tabViewStore.currentViewStyle(explorerTab) shouldBe null
    }

    @Test
    fun `setting the default writes only the setting`() = runTest {
        val vm = createVM()
        val grid = AppsViewStyle(mode = AppsViewStyle.Mode.GRID)

        vm.onPageAction(AppsPageAction.ViewStyle.SetAsDefault(grid))

        globalStyle.value shouldBe grid
        tabViewStore.currentViewStyle(id) shouldBe null
    }
}
