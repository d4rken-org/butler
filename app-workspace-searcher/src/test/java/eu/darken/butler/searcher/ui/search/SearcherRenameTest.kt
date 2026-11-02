package eu.darken.butler.searcher.ui.search

import android.content.Context
import eu.darken.butler.common.files.LocalPath
import eu.darken.butler.common.files.local.LocalPathLookup
import eu.darken.butler.common.files.metadata.FileType
import eu.darken.butler.common.files.validation.FilenameValidator
import eu.darken.butler.common.flow.SingleEventFlow
import eu.darken.butler.common.serialization.SerializationIOModule
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
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.update
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
class SearcherRenameTest {

    private val workspaceId = Workspace.Id()
    private val parent = LocalPath.build("/storage/emulated/0/Download")

    private fun result(name: String): SearchItem = SearchItem.fromLookup(
        lookup = LocalPathLookup(
            lookedUp = parent.child(name),
            fileType = FileType.FILE,
            size = 1024L,
            modifiedAt = null,
        ),
        matchedQuery = "q",
    )

    private val workspaceState = MutableStateFlow(SearcherWorkspace.State())
    private val workspace = mockk<SearcherWorkspace>(relaxed = true).apply {
        every { state } returns workspaceState
    }

    @Before
    fun setup() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun teardown() {
        Dispatchers.resetMain()
    }

    private fun makeViewModel(): SearcherWorkspaceViewModel = SearcherWorkspaceViewModel(
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
            every { openFilesOnTap.flow } returns flowOf(false)
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
        trashSettings = mockk(relaxed = true) {
            every { enabled.flow } returns flowOf(false)
        },
        folderPreviewResolver = mockk(relaxed = true),
        appInstallLauncher = mockk(relaxed = true),
        apiLevel = mockk(relaxed = true),
        errorIncidentStore = recordingIncidentStore(),
        appAttributor = noAppAttributions(),
        filenameValidator = FilenameValidator(),
        itemSorterFactory = mockk {
            every { create(any()) } returns SearchItemSorter(workspaceId = workspaceId, context = mockk<Context>())
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

    private val SearcherWorkspaceViewModel.ready: SearcherWorkspaceViewModel.State.Ready
        get() = state.value.shouldBeInstanceOf<SearcherWorkspaceViewModel.State.Ready>()

    private fun SearcherWorkspaceViewModel.rename(item: SearchItem) =
        onPageAction(SearcherPageAction.WorkspaceAction(SearcherActionBarItem.Rename(item)))

    @Test
    fun `the rename action opens the rename dialog for the result`() = runTest2 {
        val notes = result("notes.txt")
        workspaceState.value = SearcherWorkspace.State(results = listOf(notes))
        val vm = makeViewModel()

        vm.rename(notes)

        vm.ready.dialogState shouldBe SearcherDialogState.Rename(notes)
    }

    @Test
    fun `confirming a new name renames the result in the workspace`() = runTest2 {
        val notes = result("notes.txt")
        workspaceState.value = SearcherWorkspace.State(results = listOf(notes))
        val vm = makeViewModel()
        vm.rename(notes)

        vm.onPageAction(SearcherPageAction.Dialogs.RenameConfirmed(notes, "journal.txt"))

        verify { workspace.execute(SearcherCommand.Rename(source = notes, newName = "journal.txt")) }
        vm.ready.dialogState shouldBe SearcherDialogState.None
    }

    @Test
    fun `the rename action is ignored while a search runs`() = runTest2 {
        val notes = result("notes.txt")
        workspaceState.value = SearcherWorkspace.State(
            searchStatus = SearcherWorkspace.State.SearchStatus.SEARCHING,
            results = listOf(notes),
        )
        val vm = makeViewModel()

        vm.rename(notes)

        vm.ready.dialogState shouldBe SearcherDialogState.None
    }

    @Test
    fun `a renamed result is shown under its new name in name order`() = runTest2 {
        val alpha = result("alpha.txt")
        val mike = result("mike.txt")
        workspaceState.value = SearcherWorkspace.State(
            searchStatus = SearcherWorkspace.State.SearchStatus.COMPLETED,
            results = listOf(alpha, mike),
        )
        every { workspace.execute(match { it is SearcherCommand.Rename }) } answers {
            val command = firstArg<SearcherCommand.Rename>()
            workspaceState.update { state ->
                state.copy(
                    results = state.results.map {
                        if (it == command.source) result(command.newName) else it
                    }
                )
            }
        }
        val vm = makeViewModel()
        vm.ready.workspaceState.results.map { it.name } shouldBe listOf("alpha.txt", "mike.txt")

        vm.rename(alpha)
        vm.onPageAction(SearcherPageAction.Dialogs.RenameConfirmed(alpha, "zulu.txt"))

        vm.ready.workspaceState.results.map { it.name } shouldBe listOf("mike.txt", "zulu.txt")
    }
}
