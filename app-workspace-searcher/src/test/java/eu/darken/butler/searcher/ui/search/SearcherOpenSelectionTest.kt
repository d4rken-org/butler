package eu.darken.butler.searcher.ui.search

import eu.darken.butler.common.files.LocalPath
import eu.darken.butler.common.files.local.LocalPathLookup
import eu.darken.butler.common.files.metadata.FileType
import eu.darken.butler.common.flow.SingleEventFlow
import eu.darken.butler.common.serialization.SerializationIOModule
import eu.darken.butler.searcher.core.SearchItem
import eu.darken.butler.searcher.core.SearchSortSettings
import eu.darken.butler.searcher.core.SearcherSettings
import eu.darken.butler.searcher.core.SearcherTabViewStore
import eu.darken.butler.searcher.core.SearcherViewStyle
import eu.darken.butler.searcher.core.SearcherWorkspace
import eu.darken.butler.searcher.core.history.SearchHistory
import eu.darken.butler.searcher.core.sorting.SearchItemSorter
import eu.darken.butler.searcher.ui.search.dialogs.SearcherDialogState
import eu.darken.butler.searcher.ui.search.util.SearcherActionBarItem
import eu.darken.butler.searcher.ui.search.util.SearcherPageAction
import eu.darken.butler.workspace.contracts.viewer.ViewerArguments
import eu.darken.butler.workspace.core.OpenInNewTabsUseCase
import eu.darken.butler.workspace.core.OpenSelectionMode
import eu.darken.butler.workspace.core.Workspace
import eu.darken.butler.workspace.core.WorkspaceAction
import eu.darken.butler.workspace.core.WorkspaceProvider
import eu.darken.butler.workspace.core.WorkspaceRemote
import eu.darken.butler.workspace.ui.page.WorkspacePageChrome
import eu.darken.butler.workspace.ui.restore.WorkspaceViewPrefs
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.coEvery
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

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SearcherOpenSelectionTest {

    private val workspaceId = Workspace.Id()

    private fun result(name: String, fileType: FileType = FileType.FILE): SearchItem = SearchItem.fromLookup(
        lookup = LocalPathLookup(
            lookedUp = LocalPath.build("/storage/emulated/0/Download/$name"),
            fileType = fileType,
            size = 1024L,
            modifiedAt = null,
        ),
        matchedQuery = "config",
    )

    private val executed = mutableListOf<WorkspaceAction>()

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
            tabViewStore = SearcherTabViewStore(WorkspaceViewPrefs(), SerializationIOModule().json()),
            clipboardRepo = mockk(relaxed = true),
            workspaceRemote = mockk<WorkspaceRemote>(relaxed = true).apply {
                every { events } returns emptyFlow()
                coEvery { execute(any()) } answers {
                    val action = firstArg<WorkspaceAction>()
                    executed.add(action)
                    when (action) {
                        is WorkspaceAction.CreateBatch -> WorkspaceAction.CreateBatch.Result.Cancelled
                        else -> WorkspaceAction.Create.Result.Success(Workspace.Id())
                    }
                }
            },
            workspaceProvider = mockk<WorkspaceProvider>().apply {
                every { retrieve(workspaceId) } returns MutableStateFlow(workspace)
            },
            openInNewTabsUseCase = OpenInNewTabsUseCase(),
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

    private val SearcherWorkspaceViewModel.ready: SearcherWorkspaceViewModel.State.Ready
        get() = state.value.shouldBeInstanceOf<SearcherWorkspaceViewModel.State.Ready>()

    private fun SearcherWorkspaceViewModel.select(vararg items: SearchItem) {
        items.forEach { onPageAction(SearcherPageAction.Results.ToggleSelection(it)) }
    }

    private fun SearcherWorkspaceViewModel.showChooser(): SearcherDialogState.OpenSelection {
        val action = ready.availableActions.filterIsInstance<SearcherActionBarItem.OpenSelection>().single()
        onPageAction(SearcherPageAction.WorkspaceAction(action))
        return ready.dialogState.shouldBeInstanceOf<SearcherDialogState.OpenSelection>()
    }

    private fun SearcherWorkspaceViewModel.pick(state: SearcherDialogState.OpenSelection, mode: OpenSelectionMode) {
        onPageAction(SearcherPageAction.Dialogs.OpenSelectionModePicked(state, mode))
    }

    @Test
    fun `the chooser holds the selected files in display order`() = runTest2 {
        val a = result("a.txt")
        val b = result("b.txt")
        val vm = makeViewModel(listOf(a, b))
        vm.select(b, a)

        val state = vm.showChooser()

        state.results shouldBe listOf(a, b)
        state.viewerModesAvailable shouldBe true
    }

    @Test
    fun `a folder in the selection disables the viewer modes`() = runTest2 {
        val file = result("a.txt")
        val folder = result("folder", FileType.DIRECTORY)
        val vm = makeViewModel(listOf(file, folder))
        vm.select(file, folder)

        val state = vm.showChooser()

        state.results shouldBe listOf(file, folder)
        state.viewerModesAvailable shouldBe false
    }

    @Test
    fun `viewing in a tab opens one viewer stepping through the selection`() = runTest2 {
        val a = result("a.txt")
        val b = result("b.txt")
        val vm = makeViewModel(listOf(a, b))
        vm.select(a, b)

        vm.pick(vm.showChooser(), OpenSelectionMode.VIEW_IN_TAB)

        val create = executed.single().shouldBeInstanceOf<WorkspaceAction.Create>()
        create.skipContentDedup shouldBe true
        val arguments = create.arguments.shouldBeInstanceOf<ViewerArguments.Default>()
        arguments.filePath shouldBe a.path
        arguments.stepPaths shouldBe listOf(a.path, b.path)
        arguments.callerWorkspaceId shouldBe null
        arguments.listingSourceId shouldBe null
        vm.ready.dialogState shouldBe SearcherDialogState.None
        vm.ready.selectionState.selectedResultIds.shouldBeEmpty()
    }

    @Test
    fun `viewing here opens the viewer as an overlay of this searcher`() = runTest2 {
        val a = result("a.txt")
        val b = result("b.txt")
        val vm = makeViewModel(listOf(a, b))
        vm.select(a, b)

        vm.pick(vm.showChooser(), OpenSelectionMode.VIEW_HERE)

        val create = executed.single().shouldBeInstanceOf<WorkspaceAction.Create>()
        create.skipContentDedup shouldBe false
        val arguments = create.arguments.shouldBeInstanceOf<ViewerArguments.Default>()
        arguments.callerWorkspaceId shouldBe workspaceId
        arguments.stepPaths shouldBe listOf(a.path, b.path)
        vm.ready.selectionState.selectedResultIds.shouldBeEmpty()
    }

    @Test
    fun `each in a tab opens a batch`() = runTest2 {
        val file = result("a.txt")
        val folder = result("folder", FileType.DIRECTORY)
        val vm = makeViewModel(listOf(file, folder))
        vm.select(file, folder)

        vm.pick(vm.showChooser(), OpenSelectionMode.EACH_IN_TAB)

        val batch = executed.single().shouldBeInstanceOf<WorkspaceAction.CreateBatch>()
        batch.requests shouldHaveSize 2
        batch.sourceWorkspaceId shouldBe workspaceId
    }

    @Test
    fun `a second pick on a dismissed chooser issues nothing`() = runTest2 {
        val a = result("a.txt")
        val b = result("b.txt")
        val vm = makeViewModel(listOf(a, b))
        vm.select(a, b)
        val state = vm.showChooser()

        vm.pick(state, OpenSelectionMode.VIEW_IN_TAB)
        vm.pick(state, OpenSelectionMode.VIEW_IN_TAB)

        executed shouldHaveSize 1
    }

    @Test
    fun `dismissing the chooser keeps the selection`() = runTest2 {
        val a = result("a.txt")
        val b = result("b.txt")
        val vm = makeViewModel(listOf(a, b))
        vm.select(a, b)
        vm.showChooser()

        vm.onPageAction(SearcherPageAction.Dialogs.Dismiss)

        vm.ready.dialogState shouldBe SearcherDialogState.None
        vm.ready.selectionState.selectedResults shouldBe listOf(a, b)
        executed.shouldBeEmpty()
    }
}
