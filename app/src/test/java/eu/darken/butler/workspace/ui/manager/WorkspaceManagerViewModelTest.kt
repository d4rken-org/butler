package eu.darken.butler.workspace.ui.manager

import eu.darken.butler.common.ca.toCaString
import eu.darken.butler.workspace.core.Workspace
import eu.darken.butler.workspace.core.WorkspaceAction
import eu.darken.butler.workspace.core.WorkspacePauseGate
import eu.darken.butler.workspace.core.WorkspaceRemote
import eu.darken.butler.workspace.core.WorkspaceRepo
import eu.darken.butler.workspace.core.WorkspaceSettings
import eu.darken.butler.workspace.ui.WorkspacePageManager
import io.kotest.matchers.shouldBe
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import testhelpers.BaseTest
import testhelpers.coroutine.TestDispatcherProvider

class WorkspaceManagerViewModelTest : BaseTest() {

    private val idA = Workspace.Id()
    private val idB = Workspace.Id()

    private val repoState = MutableStateFlow(WorkspaceRemote.State())
    private val pageState = MutableStateFlow(WorkspacePageManager.State())

    private lateinit var workspaceRepo: WorkspaceRepo
    private lateinit var workspaceSettings: WorkspaceSettings
    private lateinit var workspacePageManager: WorkspacePageManager
    private lateinit var pauseGate: WorkspacePauseGate

    @BeforeEach
    fun setup() {
        pauseGate = WorkspacePauseGate()
        workspaceRepo = mockk(relaxed = true) {
            every { state } returns repoState
        }
        workspaceSettings = mockk(relaxed = true) {
            every { showTipBadgeExplanation } returns mockk { every { flow } returns flowOf(false) }
            every { showTipFabLongPress } returns mockk { every { flow } returns flowOf(false) }
            every { livePreview } returns mockk { every { flow } returns flowOf(true) }
        }
        workspacePageManager = mockk(relaxed = true) {
            every { state } returns pageState
        }
    }

    private fun createViewModel() = WorkspaceManagerViewModel(
        dispatchers = TestDispatcherProvider(),
        workspaceRepo = workspaceRepo,
        workspaceSettings = workspaceSettings,
        workspacePageManager = workspacePageManager,
        workspacePauseGate = pauseGate,
        workspacePreviewManager = mockk(relaxed = true),
        workspaceTemplates = emptySet(),
    )

    private fun readyInfo(id: Workspace.Id) = Workspace.Info(
        id = id,
        type = Workspace.Type.EXPLORER,
        title = "Workspace".toCaString(),
        lifecycleState = Workspace.LifecycleState.Ready,
    )

    private suspend fun items() = createViewModel().state.filterNotNull().first().workspaces

    @Test
    fun `the focused workspace cannot be paused, an unfocused pane can`() = runTest {
        repoState.value = WorkspaceRemote.State(infos = listOf(readyInfo(idA), readyInfo(idB)))
        pageState.value = WorkspacePageManager.State(
            focusedWorkspaceId = idA,
            selectedWorkspaces = mapOf(0 to idA, 1 to idB),
            currentPaneCount = 2,
        )

        val items = items()

        items.single { it.id == idA }.let {
            it.isFocused shouldBe true
            it.canPause shouldBe false
        }
        items.single { it.id == idB }.let {
            it.isFocused shouldBe false
            it.isSelected shouldBe true
            it.canPause shouldBe true
        }
    }

    @Test
    fun `a hidden ready workspace can be paused`() = runTest {
        repoState.value = WorkspaceRemote.State(infos = listOf(readyInfo(idA), readyInfo(idB)))
        pageState.value = WorkspacePageManager.State(
            focusedWorkspaceId = idA,
            selectedWorkspaces = mapOf(0 to idA),
        )

        items().single { it.id == idB }.canPause shouldBe true
    }

    @Test
    fun `a manual pause waits for a running preview capture of the same workspace`() =
        runTest(UnconfinedTestDispatcher()) {
            val vm = createViewModel()
            val captureDone = CompletableDeferred<Unit>()
            // Stands in for WorkspacePreviewCaptureService, which takes the same lease
            val capture = launch { pauseGate.withLease(idA) { captureDone.await() } }

            vm.pauseWorkspace(idA)

            coVerify(exactly = 0) { workspaceRepo.execute(any()) }

            captureDone.complete(Unit)
            capture.join()

            coVerify(exactly = 1) { workspaceRepo.execute(WorkspaceAction.Pause(idA)) }
        }

    @Test
    fun `a manual pause of another workspace is not blocked by a capture`() =
        runTest(UnconfinedTestDispatcher()) {
            val vm = createViewModel()
            val captureDone = CompletableDeferred<Unit>()
            val capture = launch { pauseGate.withLease(idB) { captureDone.await() } }

            vm.pauseWorkspace(idA)

            coVerify(exactly = 1) { workspaceRepo.execute(WorkspaceAction.Pause(idA)) }

            captureDone.complete(Unit)
            capture.join()
        }

    @Test
    fun `a workspace that opted out of pausing cannot be paused`() = runTest {
        repoState.value = WorkspaceRemote.State(
            infos = listOf(readyInfo(idA), readyInfo(idB).copy(isPausable = false)),
        )
        pageState.value = WorkspacePageManager.State(focusedWorkspaceId = idA)

        items().single { it.id == idB }.canPause shouldBe false
    }
}
