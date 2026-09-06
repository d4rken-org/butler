package eu.darken.butler.searcher.ui.search

import eu.darken.butler.common.datastore.DataStoreValue
import eu.darken.butler.common.flow.SingleEventFlow
import eu.darken.butler.common.serialization.SerializationIOModule
import eu.darken.butler.searcher.core.SearchSortSettings
import eu.darken.butler.searcher.core.SearcherSettings
import eu.darken.butler.searcher.core.SearcherTabViewStore
import eu.darken.butler.searcher.core.SearcherViewStyle
import eu.darken.butler.searcher.core.SearcherWorkspace
import eu.darken.butler.searcher.ui.search.util.SearcherPageAction
import eu.darken.butler.workspace.core.Workspace
import eu.darken.butler.workspace.core.WorkspaceProvider
import eu.darken.butler.workspace.core.WorkspaceRemote
import eu.darken.butler.workspace.ui.page.WorkspacePageChrome
import eu.darken.butler.workspace.ui.restore.WorkspaceViewPrefs
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import testhelpers.coroutine.TestDispatcherProvider
import testhelpers.coroutine.runTest2
import testhelpers.error.recordingIncidentStore

/**
 * The view options sheet has three write scopes and each one has to touch exactly its own storage:
 * live changes belong to the tab, "apply to all tabs" to every Searcher tab's slot, and only
 * "set as default" moves the setting a new tab starts from.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SearcherViewStyleScopesTest {

    private val workspaceId = Workspace.Id()
    private val otherSearcherTab = Workspace.Id()
    private val explorerTab = Workspace.Id()

    private val viewPrefs = WorkspaceViewPrefs()
    private val tabViewStore = SearcherTabViewStore(viewPrefs, SerializationIOModule().json())

    private val globalStyle = MutableStateFlow(SearcherViewStyle.default())
    private val styleStore = mockk<DataStoreValue<SearcherViewStyle>>().apply {
        every { flow } returns globalStyle
        coEvery { update(any()) } answers {
            val old = globalStyle.value
            val new = firstArg<(SearcherViewStyle) -> SearcherViewStyle?>().invoke(old) ?: old
            globalStyle.value = new
            DataStoreValue.Updated(old, new)
        }
    }

    @Before
    fun setup() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After
    fun teardown() = Dispatchers.resetMain()

    private fun tabInfo(id: Workspace.Id, type: Workspace.Type) = mockk<Workspace.Info>().apply {
        every { this@apply.id } returns id
        every { this@apply.type } returns type
    }

    private fun makeViewModel(): SearcherWorkspaceViewModel {
        val workspace = mockk<SearcherWorkspace>(relaxed = true).apply {
            every { state } returns MutableStateFlow(SearcherWorkspace.State())
        }
        return SearcherWorkspaceViewModel(
            id = workspaceId,
            appContext = mockk(relaxed = true),
            dispatchers = TestDispatcherProvider(),
            searchHistory = mockk(relaxed = true),
            searcherSettings = mockk<SearcherSettings>(relaxed = true).apply {
                every { defaultSort.flow } returns flowOf(SearchSortSettings())
                every { defaultViewStyle } returns styleStore
            },
            tabViewStore = tabViewStore,
            clipboardRepo = mockk(relaxed = true),
            workspaceRemote = mockk<WorkspaceRemote>(relaxed = true).apply {
                every { events } returns emptyFlow()
                every { state } returns flowOf(
                    WorkspaceRemote.State(
                        listOf(
                            tabInfo(workspaceId, Workspace.Type.SEARCHER),
                            tabInfo(otherSearcherTab, Workspace.Type.SEARCHER),
                            tabInfo(explorerTab, Workspace.Type.EXPLORER),
                        )
                    )
                )
            },
            workspaceProvider = mockk<WorkspaceProvider>().apply {
                every { retrieve(workspaceId) } returns MutableStateFlow(workspace)
            },
            openInNewTabsUseCase = mockk(relaxed = true),
            shareIntentUseCase = mockk(relaxed = true),
            openWithIntentUseCase = mockk(relaxed = true),
            trashSettings = mockk(relaxed = true),
            folderPreviewResolver = mockk(relaxed = true),
            appInstallLauncher = mockk(relaxed = true),
            apiLevel = mockk(relaxed = true),
            errorIncidentStore = recordingIncidentStore(),
            itemSorterFactory = mockk {
                every { create(any()) } returns mockk(relaxed = true)
            },
            chromeFactory = mockk<WorkspacePageChrome.Factory>().apply {
                every { create(any(), any()) } returns mockk<WorkspacePageChrome>(relaxed = true).apply {
                    every { shareIntentEvent } returns SingleEventFlow()
                    every { pendingErrorShare } returns MutableStateFlow(null)
                    every { pendingConflicts } returns flowOf(emptyMap())
                    every { clipboard } returns emptyFlow()
                    every { operations } returns emptyFlow()
                }
            },
        )
    }

    @Test
    fun `the live path writes the tab slot and leaves the default alone`() = runTest2 {
        val vm = makeViewModel()
        val grid = SearcherViewStyle(mode = SearcherViewStyle.Mode.GRID)

        vm.onPageAction(SearcherPageAction.ViewStyle.ApplyToTab(grid))

        tabViewStore.currentViewStyle(workspaceId) shouldBe grid
        globalStyle.value shouldBe SearcherViewStyle.default()
        coVerify(exactly = 0) { styleStore.update(any()) }
    }

    @Test
    fun `applying to all tabs writes every searcher tab's slot`() = runTest2 {
        val vm = makeViewModel()
        val detailed = SearcherViewStyle(density = SearcherViewStyle.Density.DETAILED)

        vm.onPageAction(SearcherPageAction.ViewStyle.ApplyToAllTabs(detailed))

        tabViewStore.currentViewStyle(workspaceId) shouldBe detailed
        tabViewStore.currentViewStyle(otherSearcherTab) shouldBe detailed
        tabViewStore.currentViewStyle(explorerTab) shouldBe null
    }

    @Test
    fun `setting the default writes only the setting`() = runTest2 {
        val vm = makeViewModel()
        val grid = SearcherViewStyle(mode = SearcherViewStyle.Mode.GRID)

        vm.onPageAction(SearcherPageAction.ViewStyle.SetAsDefault(grid))

        globalStyle.value shouldBe grid
        tabViewStore.currentViewStyle(workspaceId) shouldBe SearcherViewStyle.default()
    }
}
