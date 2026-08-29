package eu.darken.butler.searcher.ui.search

import eu.darken.butler.common.error.ErrorIncident
import eu.darken.butler.common.flow.SingleEventFlow
import eu.darken.butler.searcher.core.SearchSortSettings
import eu.darken.butler.searcher.core.SearcherSettings
import eu.darken.butler.searcher.core.SearcherViewStyle
import eu.darken.butler.searcher.core.SearcherWorkspace
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
 * A search failure is frozen when it enters the state the page renders, so the report carries the
 * log trail from around the failure rather than from whenever the user tapped Share.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SearcherWorkspaceErrorIncidentTest {

    private val workspaceId = Workspace.Id()
    private val searchState = MutableStateFlow(SearcherWorkspace.State())
    private val incidentStore = recordingIncidentStore()

    /** What the share action handed to the chrome. */
    private val shared = mutableListOf<ErrorIncident>()

    @Before
    fun setup() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        shared.clear()
    }

    @After
    fun teardown() {
        Dispatchers.resetMain()
    }

    private fun makeViewModel(): SearcherWorkspaceViewModel {
        val workspace = mockk<SearcherWorkspace>(relaxed = true).apply {
            every { state } returns searchState
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
            trashSettings = mockk(relaxed = true),
            folderPreviewResolver = mockk(relaxed = true),
            apiLevel = mockk(relaxed = true),
            errorIncidentStore = incidentStore,
            itemSorterFactory = mockk {
                every { create(any()) } returns mockk(relaxed = true)
            },
            chromeFactory = mockk<WorkspacePageChrome.Factory>().apply {
                every { create(any(), any()) } returns mockk<WorkspacePageChrome>().apply {
                    every { shareIntentEvent } returns SingleEventFlow()
                    every { pendingErrorShare } returns MutableStateFlow(null)
                    every { pendingConflicts } returns flowOf(emptyMap())
                    every { clipboard } returns emptyFlow()
                    every { operations } returns emptyFlow()
                    every { shareWorkspaceError(any(), any()) } answers { shared += firstArg<ErrorIncident>() }
                }
            },
        )
    }

    @Test
    fun `sharing a search failure hands over the incident it was frozen into`() = runTest2 {
        val vm = makeViewModel()

        val sentinel = IllegalStateException("search blew up")
        searchState.value = SearcherWorkspace.State(
            searchStatus = SearcherWorkspace.State.SearchStatus.ERROR,
            error = sentinel,
        )

        vm.onPageAction(SearcherPageAction.Error.Share(sentinel))

        val incident = shared.single()
        (incident.error === sentinel) shouldBe true
        incident.context.containsKey("incident.frozenAtShare") shouldBe false
    }

    @Test
    fun `sharing a target failure hands over the incident the dialog was opened with`() = runTest2 {
        val vm = makeViewModel()

        val sentinel = IllegalStateException("target unreadable")
        vm.onPageAction(SearcherPageAction.Overlays.ShowTargetError("/sdcard/Android/data", sentinel))

        vm.onPageAction(SearcherPageAction.Error.Share(sentinel, "/sdcard/Android/data"))

        val incident = shared.single()
        (incident.error === sentinel) shouldBe true
        incident.context["search.targetPath"] shouldBe "/sdcard/Android/data"
        incident.context.containsKey("incident.frozenAtShare") shouldBe false
    }
}
