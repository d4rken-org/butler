package eu.darken.butler.searcher.ui.search

import eu.darken.butler.common.files.APathLookup
import eu.darken.butler.common.files.ArchivePath
import eu.darken.butler.common.files.LocalPath
import eu.darken.butler.common.files.archive.ArchivePathLookup
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
import eu.darken.butler.searcher.core.sorting.SearchItemSorter
import eu.darken.butler.searcher.ui.search.util.SearcherPageAction
import eu.darken.butler.workspace.contracts.viewer.ViewerArguments
import eu.darken.butler.workspace.core.OpenInNewTabsUseCase
import eu.darken.butler.workspace.core.OpenWithIntentUseCase
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
import io.mockk.verify
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.test.testTimeSource
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import testhelpers.coroutine.TestDispatcherProvider
import testhelpers.coroutine.runTest2
import testhelpers.error.recordingIncidentStore
import kotlin.time.Duration.Companion.milliseconds

/**
 * With "open files on tap" a tap on a file result opens it the way a tap on a file in the Explorer
 * does: in the viewer when Butler renders it, else in the user's default app, else the quick actions.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SearcherOpenOnTapTest {

    private val workspaceId = Workspace.Id()
    private val executed = mutableListOf<WorkspaceAction>()

    private fun result(lookup: APathLookup<*>): SearchItem = SearchItem.fromLookup(
        lookup = lookup,
        matchedQuery = "q",
    )

    private fun localResult(name: String, fileType: FileType = FileType.FILE): SearchItem = result(
        LocalPathLookup(
            lookedUp = LocalPath.build("/storage/emulated/0/Download/$name"),
            fileType = fileType,
            size = 1024L,
            modifiedAt = null,
        ),
    )

    private val photo = localResult("photo.jpg")
    private val notes = localResult("notes.txt")
    private val bundle = localResult("app.xapk")
    private val video = localResult("clip.mp4")
    private val folder = localResult("Pictures", FileType.DIRECTORY)
    private val archivedVideo = result(
        ArchivePathLookup(
            lookedUp = ArchivePath(LocalPath.build("/storage/emulated/0/Download/archive.zip"), listOf("clip.mp4")),
            fileType = FileType.FILE,
            size = 1024L,
            modifiedAt = null,
        ),
    )

    @Before
    fun setup() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun teardown() {
        Dispatchers.resetMain()
    }

    private fun makeViewModel(
        openOnTap: Boolean,
        handOff: Boolean = true,
        openWithIntentUseCase: OpenWithIntentUseCase = mockk {
            every { openWithDefault(any(), any(), any()) } returns handOff
        },
        createGate: CompletableDeferred<Unit>? = null,
        failFirstCreate: Boolean = false,
    ): SearcherWorkspaceViewModel {
        val results = listOf(photo, notes, bundle, video, folder, archivedVideo)
        val workspace = mockk<SearcherWorkspace>(relaxed = true).apply {
            every { state } returns MutableStateFlow(SearcherWorkspace.State(results = results))
        }
        var creates = 0
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
                every { openFilesOnTap.flow } returns flowOf(openOnTap)
            },
            tabViewStore = SearcherTabViewStore(WorkspaceViewPrefs(), SerializationIOModule().json()),
            clipboardRepo = mockk(relaxed = true),
            workspaceRemote = mockk<WorkspaceRemote>(relaxed = true).apply {
                every { events } returns emptyFlow()
                coEvery { execute(any()) } coAnswers {
                    executed.add(firstArg())
                    createGate?.await()
                    if (failFirstCreate && creates++ == 0) throw IllegalStateException("Workspace limit")
                    WorkspaceAction.Create.Result.Success(Workspace.Id())
                }
            },
            workspaceProvider = mockk<WorkspaceProvider>().apply {
                every { retrieve(workspaceId) } returns MutableStateFlow(workspace)
            },
            openInNewTabsUseCase = OpenInNewTabsUseCase(),
            shareIntentUseCase = mockk(relaxed = true),
            openWithIntentUseCase = openWithIntentUseCase,
            trashSettings = mockk(relaxed = true) {
                every { enabled.flow } returns flowOf(true)
                every { confirmTrashMove.flow } returns flowOf(true)
            },
            folderPreviewResolver = mockk(relaxed = true),
            appInstallLauncher = mockk(relaxed = true),
            apiLevel = mockk(relaxed = true),
            errorIncidentStore = recordingIncidentStore(),
            appAttributor = noAppAttributions(),
            filenameValidator = FilenameValidator(),
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

    private fun SearcherWorkspaceViewModel.tap(item: SearchItem) {
        onPageAction(SearcherPageAction.Results.Click(item))
    }

    private fun WorkspaceAction.shouldBeViewerDrillDownOf(item: SearchItem) {
        val create = shouldBeInstanceOf<WorkspaceAction.Create>()
        create.type shouldBe Workspace.Type.VIEWER
        val arguments = create.arguments.shouldBeInstanceOf<ViewerArguments.Default>()
        arguments.filePath shouldBe item.path
        arguments.callerWorkspaceId shouldBe workspaceId
    }

    @Test
    fun `with the setting off a tap shows the quick actions`() = runTest2 {
        val openWith = mockk<OpenWithIntentUseCase>(relaxed = true)
        val vm = makeViewModel(openOnTap = false, openWithIntentUseCase = openWith)

        vm.tap(photo)

        vm.ready.quickActionsResult shouldBe photo
        executed.shouldBeEmpty()
        verify(exactly = 0) { openWith.openWithDefault(any(), any(), any()) }
    }

    @Test
    fun `a file the viewer renders opens in the viewer over this search`() = runTest2 {
        val vm = makeViewModel(openOnTap = true)

        vm.tap(photo)

        executed.single().shouldBeViewerDrillDownOf(photo)
        vm.ready.quickActionsResult shouldBe null
    }

    @Test
    fun `an app bundle opens in the viewer`() = runTest2 {
        val openWith = mockk<OpenWithIntentUseCase>(relaxed = true)
        val vm = makeViewModel(openOnTap = true, openWithIntentUseCase = openWith)

        vm.tap(bundle)

        executed.single().shouldBeViewerDrillDownOf(bundle)
        vm.ready.quickActionsResult shouldBe null
        verify(exactly = 0) { openWith.openWithDefault(any(), any(), any()) }
    }

    @Test
    fun `a file the viewer does not render is handed to the default app`() = runTest2 {
        val openWith = mockk<OpenWithIntentUseCase> {
            every { openWithDefault(any(), any(), any()) } returns true
        }
        val vm = makeViewModel(openOnTap = true, openWithIntentUseCase = openWith)

        vm.tap(video)

        verify(exactly = 1) { openWith.openWithDefault(video.path, "video/mp4", any()) }
        executed.shouldBeEmpty()
        vm.ready.quickActionsResult shouldBe null
    }

    @Test
    fun `a file no other app takes shows the quick actions`() = runTest2 {
        val vm = makeViewModel(openOnTap = true, handOff = false)

        vm.tap(video)

        vm.ready.quickActionsResult shouldBe video
        executed.shouldBeEmpty()
    }

    @Test
    fun `a file that cannot be handed to another app shows the quick actions`() = runTest2 {
        val openWith = mockk<OpenWithIntentUseCase>(relaxed = true)
        val vm = makeViewModel(openOnTap = true, openWithIntentUseCase = openWith)

        vm.tap(archivedVideo)

        vm.ready.quickActionsResult shouldBe archivedVideo
        executed.shouldBeEmpty()
        verify(exactly = 0) { openWith.openWithDefault(any(), any(), any()) }
    }

    @Test
    fun `a folder shows the quick actions with the setting on`() = runTest2 {
        val vm = makeViewModel(openOnTap = true)

        vm.tap(folder)

        vm.ready.quickActionsResult shouldBe folder
        executed.shouldBeEmpty()
    }

    @Test
    fun `a second tap while an open is running is ignored`() = runTest2 {
        val gate = CompletableDeferred<Unit>()
        val vm = makeViewModel(openOnTap = true, createGate = gate)

        vm.tap(photo)
        vm.tap(notes)

        executed shouldHaveSize 1
        executed.single().shouldBeViewerDrillDownOf(photo)

        gate.complete(Unit)
        vm.tap(notes)

        executed shouldHaveSize 2
        executed[1].shouldBeViewerDrillDownOf(notes)
    }

    @Test
    fun `the same file tapped again within the cooldown is not opened twice`() = runTest2 {
        val vm = makeViewModel(openOnTap = true)
        vm.directOpenTimeSource = testTimeSource

        vm.tap(photo)
        advanceTimeBy(400.milliseconds)
        vm.tap(photo)

        executed shouldHaveSize 1

        advanceTimeBy(200.milliseconds)
        vm.tap(photo)

        executed shouldHaveSize 2
        executed[1].shouldBeViewerDrillDownOf(photo)
    }

    @Test
    fun `a hand-off is not repeated by a quick second tap`() = runTest2 {
        val openWith = mockk<OpenWithIntentUseCase> {
            every { openWithDefault(any(), any(), any()) } returns true
        }
        val vm = makeViewModel(openOnTap = true, openWithIntentUseCase = openWith)
        vm.directOpenTimeSource = testTimeSource

        vm.tap(video)
        vm.tap(video)

        verify(exactly = 1) { openWith.openWithDefault(any(), any(), any()) }

        advanceTimeBy(600.milliseconds)
        vm.tap(video)

        verify(exactly = 2) { openWith.openWithDefault(any(), any(), any()) }
    }

    @Test
    fun `a different file tapped right after an open still opens`() = runTest2 {
        val vm = makeViewModel(openOnTap = true)
        vm.directOpenTimeSource = testTimeSource

        vm.tap(photo)
        vm.tap(notes)

        executed shouldHaveSize 2
        executed[0].shouldBeViewerDrillDownOf(photo)
        executed[1].shouldBeViewerDrillDownOf(notes)
    }

    @Test
    fun `a failed open does not block the next tap`() = runTest2 {
        val vm = makeViewModel(openOnTap = true, failFirstCreate = true)

        vm.tap(photo)
        vm.tap(notes)

        executed shouldHaveSize 2
        executed[0].shouldBeViewerDrillDownOf(photo)
        executed[1].shouldBeViewerDrillDownOf(notes)
    }
}
