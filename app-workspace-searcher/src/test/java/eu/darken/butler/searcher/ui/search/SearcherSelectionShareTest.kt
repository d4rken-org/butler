package eu.darken.butler.searcher.ui.search

import eu.darken.butler.common.files.LocalPath
import eu.darken.butler.common.files.SAFPath
import eu.darken.butler.common.files.local.LocalPathLookup
import eu.darken.butler.common.files.metadata.FileType
import eu.darken.butler.common.files.saf.SAFPathLookup
import eu.darken.butler.common.flow.SingleEventFlow
import eu.darken.butler.searcher.core.SearchItem
import eu.darken.butler.searcher.core.SearchSortSettings
import eu.darken.butler.searcher.core.SearcherSettings
import eu.darken.butler.searcher.core.SearcherViewStyle
import eu.darken.butler.searcher.core.SearcherWorkspace
import eu.darken.butler.searcher.core.history.SearchHistory
import eu.darken.butler.searcher.core.sorting.SearchItemSorter
import eu.darken.butler.searcher.ui.search.util.SearcherActionBarItem
import eu.darken.butler.searcher.ui.search.util.SearcherPageAction
import eu.darken.butler.workspace.core.Workspace
import eu.darken.butler.workspace.core.WorkspaceProvider
import eu.darken.butler.workspace.core.WorkspaceRemote
import eu.darken.butler.workspace.ui.page.WorkspacePageChrome
import io.kotest.matchers.shouldBe
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
 * Sharing a selection needs a URI the system can resolve for every single result, so the action bar
 * only offers it when the whole selection is made of local files. A mixed selection is the one that
 * would fail quietly: only the local part of it would ever reach the other app.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SearcherSelectionShareTest {

    private val workspaceId = Workspace.Id()

    private fun localResult(name: String): SearchItem = SearchItem.fromLookup(
        lookup = LocalPathLookup(
            lookedUp = LocalPath.build("/storage/emulated/0/Download/$name"),
            fileType = FileType.FILE,
            size = 1024L,
            modifiedAt = null,
        ),
        matchedQuery = "config",
    )

    private fun safResult(name: String): SearchItem = SearchItem.fromLookup(
        lookup = SAFPathLookup(
            lookedUp = SAFPath.build(
                "content://com.android.externalstorage.documents/tree/primary%3A",
                "Download",
                name,
            ),
            fileType = FileType.FILE,
            size = 1024L,
            modifiedAt = null,
        ),
        matchedQuery = "config",
    )

    @Before
    fun setup() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun teardown() {
        Dispatchers.resetMain()
    }

    private fun makeViewModel(results: List<SearchItem>): SearcherWorkspaceViewModel {
        val workspace = mockk<SearcherWorkspace>(relaxed = true).apply {
            every { state } returns MutableStateFlow(SearcherWorkspace.State(results = results))
        }
        return SearcherWorkspaceViewModel(
            id = workspaceId,
            appContext = mockk(relaxed = true),
            dispatchers = TestDispatcherProvider(),
            searchHistory = mockk<SearchHistory>(relaxed = true).apply {
                every { getSearches(any()) } returns flowOf(emptyList())
            },
            searcherSettings = mockk<SearcherSettings>(relaxed = true).apply {
                every { defaultSort.flow } returns flowOf(SearchSortSettings())
                every { defaultViewStyle.flow } returns flowOf(SearcherViewStyle.default())
                every { maxHistoryItems.flow } returns flowOf(50)
            },
            clipboardRepo = mockk(relaxed = true),
            workspaceRemote = mockk<WorkspaceRemote>(relaxed = true).apply {
                every { events } returns emptyFlow()
            },
            workspaceProvider = mockk<WorkspaceProvider>().apply {
                every { retrieve(workspaceId) } returns MutableStateFlow(workspace)
            },
            openInNewTabsUseCase = mockk(relaxed = true),
            shareIntentUseCase = mockk(relaxed = true),
            openWithIntentUseCase = mockk(relaxed = true),
            trashSettings = mockk(relaxed = true) {
                every { enabled.flow } returns flowOf(true)
            },
            folderPreviewResolver = mockk(relaxed = true),
            appInstallLauncher = mockk(relaxed = true),
            apiLevel = mockk(relaxed = true),
            errorIncidentStore = recordingIncidentStore(),
            itemSorterFactory = mockk {
                every { create(any()) } returns mockk<SearchItemSorter> {
                    every { sortItems(any(), any()) } answers { firstArg() }
                }
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

    private fun sharesOffered(results: List<SearchItem>, selection: List<SearchItem>): Boolean {
        val vm = makeViewModel(results)
        selection.forEach { vm.onPageAction(SearcherPageAction.Results.ToggleSelection(it)) }
        val ready = vm.state.value as SearcherWorkspaceViewModel.State.Ready
        return ready.availableActions.any { it is SearcherActionBarItem.Share }
    }

    @Test
    fun `a selection of local files can be shared`() = runTest2 {
        val results = listOf(localResult("config-a.txt"), localResult("config-b.txt"))

        sharesOffered(results, results) shouldBe true
    }

    @Test
    fun `a selection reached through SAF is not offered for sharing`() = runTest2 {
        val results = listOf(safResult("config-a.txt"), safResult("config-b.txt"))

        sharesOffered(results, results) shouldBe false
    }

    @Test
    fun `a selection mixing local and SAF results is not offered for sharing`() = runTest2 {
        val results = listOf(localResult("config-a.txt"), safResult("config-b.txt"))

        sharesOffered(results, results) shouldBe false
    }
}
