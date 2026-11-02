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
import eu.darken.butler.searcher.ui.search.util.SearcherActionBarItem
import eu.darken.butler.searcher.ui.search.util.SearcherPageAction
import eu.darken.butler.workspace.contracts.explorer.ExplorerArguments
import eu.darken.butler.workspace.core.Workspace
import eu.darken.butler.workspace.core.WorkspaceAction
import eu.darken.butler.workspace.core.WorkspaceProvider
import eu.darken.butler.workspace.core.WorkspaceRemote
import eu.darken.butler.workspace.ui.page.WorkspacePageChrome
import eu.darken.butler.workspace.ui.restore.WorkspaceViewPrefs
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

/** "Open in explorer" on a file lands on the file, not just on the folder it is in. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SearcherOpenInExplorerRevealTest {

    private val workspaceId = Workspace.Id()
    private val executed = mutableListOf<WorkspaceAction>()

    private fun result(path: String, fileType: FileType): SearchItem = SearchItem.fromLookup(
        lookup = LocalPathLookup(
            lookedUp = LocalPath.build(path),
            fileType = fileType,
            size = 1024L,
            modifiedAt = null,
        ),
        matchedQuery = "report",
    )

    @Before
    fun setup() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun teardown() {
        Dispatchers.resetMain()
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
                every { defaultViewStyle.flow } returns flowOf(SearcherViewStyle.default())
            },
            tabViewStore = SearcherTabViewStore(WorkspaceViewPrefs(), SerializationIOModule().json()),
            clipboardRepo = mockk(relaxed = true),
            workspaceRemote = mockk<WorkspaceRemote>(relaxed = true).apply {
                every { events } returns emptyFlow()
                coEvery { execute(any()) } answers {
                    executed.add(firstArg())
                    WorkspaceAction.Create.Result.Success(Workspace.Id())
                }
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
            appAttributor = noAppAttributions(),
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

    private fun createdExplorerArguments(): ExplorerArguments.Default {
        val create = executed.single().shouldBeInstanceOf<WorkspaceAction.Create>()
        create.type shouldBe Workspace.Type.EXPLORER
        create.sourceWorkspaceId shouldBe workspaceId
        return create.arguments.shouldBeInstanceOf<ExplorerArguments.Default>()
    }

    @Test
    fun `a file opens its folder and reveals the file`() = runTest2 {
        val file = result("/storage/emulated/0/Documents/report.pdf", FileType.FILE)
        val vm = makeViewModel()

        vm.onPageAction(SearcherPageAction.WorkspaceAction(SearcherActionBarItem.OpenInExplorer(file)))

        val arguments = createdExplorerArguments()
        arguments.startPath shouldBe LocalPath.build("/storage/emulated/0/Documents")
        arguments.revealPath shouldBe file.path
    }

    @Test
    fun `a folder opens the folder itself without a reveal`() = runTest2 {
        val folder = result("/storage/emulated/0/Documents/Reports", FileType.DIRECTORY)
        val vm = makeViewModel()

        vm.onPageAction(SearcherPageAction.WorkspaceAction(SearcherActionBarItem.OpenInExplorer(folder)))

        val arguments = createdExplorerArguments()
        arguments.startPath shouldBe folder.path
        arguments.revealPath shouldBe null
    }
}
