package eu.darken.butler.searcher.ui.search

import eu.darken.butler.common.files.LocalPath
import eu.darken.butler.common.files.local.LocalPathLookup
import eu.darken.butler.common.files.metadata.FileType
import eu.darken.butler.common.flow.SingleEventFlow
import eu.darken.butler.common.serialization.SerializationIOModule
import eu.darken.butler.common.trash.TrashSettings
import eu.darken.butler.searcher.core.SearchItem
import eu.darken.butler.searcher.core.SearchSortSettings
import eu.darken.butler.searcher.core.SearcherSettings
import eu.darken.butler.searcher.core.SearcherTabViewStore
import eu.darken.butler.searcher.core.SearcherViewStyle
import eu.darken.butler.searcher.core.SearcherWorkspace
import eu.darken.butler.searcher.core.history.SearchHistory
import eu.darken.butler.searcher.core.operations.SearcherCommand
import eu.darken.butler.searcher.core.sorting.SearchItemSorter
import eu.darken.butler.searcher.ui.search.dialogs.SearcherDialogState
import eu.darken.butler.searcher.ui.search.util.SearcherActionBarItem
import eu.darken.butler.searcher.ui.search.util.SearcherPageAction
import eu.darken.butler.workspace.core.Workspace
import eu.darken.butler.workspace.core.WorkspaceProvider
import eu.darken.butler.workspace.core.WorkspaceRemote
import eu.darken.butler.workspace.ui.page.WorkspacePageChrome
import eu.darken.butler.workspace.ui.restore.WorkspaceViewPrefs
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
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
import testhelpers.mockDataStoreValue

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SearcherTrashMoveConfirmationTest {

    private val workspaceId = Workspace.Id()

    private fun result(name: String): SearchItem = SearchItem.fromLookup(
        lookup = LocalPathLookup(
            lookedUp = LocalPath.build("/storage/emulated/0/Download/$name"),
            fileType = FileType.FILE,
            size = 1024L,
            modifiedAt = null,
        ),
        matchedQuery = "config",
    )

    private val commands = mutableListOf<SearcherCommand>()

    @Before
    fun setup() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        commands.clear()
    }

    @After
    fun teardown() {
        Dispatchers.resetMain()
    }

    private fun makeViewModel(
        results: List<SearchItem>,
        confirmTrashMove: Boolean,
    ): SearcherWorkspaceViewModel {
        val workspace = mockk<SearcherWorkspace>(relaxed = true).apply {
            every { state } returns MutableStateFlow(SearcherWorkspace.State(results = results))
            every { execute(any()) } answers { commands += firstArg<SearcherCommand>() }
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
            tabViewStore = SearcherTabViewStore(WorkspaceViewPrefs(), SerializationIOModule().json()),
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
            trashSettings = mockk<TrashSettings>().apply {
                every { enabled } returns mockDataStoreValue(true)
                every { this@apply.confirmTrashMove } returns mockDataStoreValue(confirmTrashMove)
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

    private val SearcherWorkspaceViewModel.ready: SearcherWorkspaceViewModel.State.Ready
        get() = state.value.shouldBeInstanceOf<SearcherWorkspaceViewModel.State.Ready>()

    private fun SearcherWorkspaceViewModel.select(vararg items: SearchItem) {
        items.forEach { onPageAction(SearcherPageAction.Results.ToggleSelection(it)) }
    }

    private fun SearcherWorkspaceViewModel.delete() {
        val action = ready.availableActions.filterIsInstance<SearcherActionBarItem.Delete>().single()
        onPageAction(SearcherPageAction.WorkspaceAction(action))
    }

    @Test
    fun `with the confirmation off a trash move runs without the dialog`() = runTest2 {
        val a = result("a.txt")
        val b = result("b.txt")
        val vm = makeViewModel(listOf(a, b), confirmTrashMove = false)
        vm.select(a, b)

        vm.delete()

        commands shouldBe listOf(
            SearcherCommand.Delete(
                targets = setOf(a.path, b.path),
                options = SearcherCommand.Delete.Options(forcePermDelete = false),
            ),
        )
        vm.ready.dialogState shouldBe SearcherDialogState.None
        vm.ready.selectionState.selectedResultIds.shouldBeEmpty()
    }

    @Test
    fun `with the confirmation on the dialog asks first`() = runTest2 {
        val a = result("a.txt")
        val vm = makeViewModel(listOf(a), confirmTrashMove = true)
        vm.select(a)

        vm.delete()

        commands.shouldBeEmpty()
        val dialog = vm.ready.dialogState.shouldBeInstanceOf<SearcherDialogState.DeleteConfirmation>()
        dialog.paths shouldBe setOf(a.path)
        dialog.initialPermanentDelete shouldBe false
    }
}
