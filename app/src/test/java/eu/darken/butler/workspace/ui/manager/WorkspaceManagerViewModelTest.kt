package eu.darken.butler.workspace.ui.manager

import eu.darken.butler.common.ca.toCaString
import eu.darken.butler.workspace.core.Workspace
import eu.darken.butler.workspace.core.WorkspaceAction
import eu.darken.butler.workspace.core.WorkspacePauseGate
import eu.darken.butler.workspace.core.WorkspaceRemote
import eu.darken.butler.workspace.core.WorkspaceRepo
import eu.darken.butler.workspace.core.WorkspaceSettings
import eu.darken.butler.workspace.core.WorkspaceStacks
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
            // Pause leases are keyed on the ownership root; the flat topologies here are their own
            every { peekOwnershipRoot(any()) } answers { firstArg() }
            // selectWorkspace resolves the stack top synchronously, so ownership stays real here
            every { peekStacks() } answers { WorkspaceStacks(repoState.value.infos) }
        }
        workspaceSettings = mockk(relaxed = true) {
            every { showTipBadgeExplanation } returns mockk { every { flow } returns flowOf(false) }
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

    private fun childInfo(
        caller: Workspace.Id,
        pausableAsChild: Boolean,
        id: Workspace.Id = Workspace.Id(),
    ) = readyInfo(id).copy(
        type = Workspace.Type.APP_DETAILS,
        callerWorkspaceId = caller,
        pausableAsChild = pausableAsChild,
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
            it.isVisibleInPane shouldBe true
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

    @Test
    fun `a busy or unsaved workspace cannot be paused`() = runTest {
        val busyId = Workspace.Id()
        val attentionId = Workspace.Id()
        val dirtyId = Workspace.Id()
        val initializingId = Workspace.Id()
        repoState.value = WorkspaceRemote.State(
            infos = listOf(
                readyInfo(idA),
                readyInfo(busyId).copy(operationCount = 1),
                readyInfo(attentionId).copy(attentionCount = 1),
                readyInfo(dirtyId).copy(hasUnsavedChanges = true),
                readyInfo(initializingId).copy(lifecycleState = Workspace.LifecycleState.Initializing),
            ),
        )
        pageState.value = WorkspacePageManager.State(focusedWorkspaceId = idA)

        val items = items()
        items.single { it.id == busyId }.canPause shouldBe false
        items.single { it.id == attentionId }.canPause shouldBe false
        items.single { it.id == dirtyId }.canPause shouldBe false
        items.single { it.id == initializingId }.canPause shouldBe false
    }

    @Test
    fun `a tab with an overlay can be paused only when the whole unit may go`() = runTest {
        val goodOverlay = childInfo(caller = idB, pausableAsChild = true)
        val busyOverlay = childInfo(caller = idA, pausableAsChild = true).copy(operationCount = 1)
        val optedOutOverlay = childInfo(caller = idA, pausableAsChild = false)
        val focusedId = Workspace.Id()
        val pausableWithOverlayId = idB

        repoState.value = WorkspaceRemote.State(
            infos = listOf(readyInfo(focusedId), readyInfo(idA), readyInfo(idB), goodOverlay, busyOverlay),
        )
        pageState.value = WorkspacePageManager.State(focusedWorkspaceId = focusedId)

        items().let { items ->
            items.single { it.id == pausableWithOverlayId }.canPause shouldBe true
            // A busy overlay keeps its whole tab awake
            items.single { it.id == idA }.canPause shouldBe false
            // A resolvable overlay is collapsed into its tab's card, so it has no card of its own
            items.none { it.id == goodOverlay.id } shouldBe true
        }

        repoState.value = WorkspaceRemote.State(
            infos = listOf(readyInfo(focusedId), readyInfo(idA), optedOutOverlay),
        )

        // A picker-like overlay that owes a result never lets its caller be released
        items().single { it.id == idA }.canPause shouldBe false
    }

    @Test
    fun `a focused overlay keeps its owning tab from being paused`() = runTest {
        val overlay = childInfo(caller = idB, pausableAsChild = true)
        repoState.value = WorkspaceRemote.State(infos = listOf(readyInfo(idA), readyInfo(idB), overlay))
        pageState.value = WorkspacePageManager.State(focusedWorkspaceId = overlay.id)

        // Resume-on-focus would immediately undo it
        items().single { it.id == idB }.canPause shouldBe false
    }

    @Test
    fun `a broken ownership chain is never offered for pausing`() = runTest {
        val cycleA = Workspace.Id()
        val cycleB = Workspace.Id()
        val orphan = Workspace.Id()
        repoState.value = WorkspaceRemote.State(
            infos = listOf(
                readyInfo(idA),
                // Nothing validates caller ids at creation time, so both of these are reachable
                readyInfo(cycleA).copy(callerWorkspaceId = cycleB, pausableAsChild = true),
                readyInfo(cycleB).copy(callerWorkspaceId = cycleA, pausableAsChild = true),
                readyInfo(orphan).copy(callerWorkspaceId = Workspace.Id(), pausableAsChild = true),
            ),
        )
        pageState.value = WorkspacePageManager.State(focusedWorkspaceId = idA)

        val items = items()
        // The cycle is one recovery unit, so it gets a single card keyed on its first member
        items.none { it.id == cycleB } shouldBe true
        items.single { it.id == cycleA }.canPause shouldBe false
        items.single { it.id == orphan }.canPause shouldBe false
    }

    @Test
    fun `a tab with an overlay is one card, wearing the overlay's identity`() = runTest {
        val overlay = childInfo(caller = idA, pausableAsChild = true).copy(
            title = "Butler".toCaString(),
            subtitle = "eu.darken.butler".toCaString(),
        )
        repoState.value = WorkspaceRemote.State(infos = listOf(readyInfo(idA), overlay))
        pageState.value = WorkspacePageManager.State(
            focusedWorkspaceId = overlay.id,
            selectedWorkspaces = mapOf(0 to idA),
        )

        val items = items()

        items.map { it.id } shouldBe listOf(idA)
        items.single().let {
            it.topId shouldBe overlay.id
            it.type shouldBe Workspace.Type.APP_DETAILS
            it.autoTitle shouldBe overlay.title
            it.subtitle shouldBe overlay.subtitle
            it.stackDepth shouldBe 1
            // Focus sits on the overlay, but the pane holds the tab
            it.isFocused shouldBe true
            it.isVisibleInPane shouldBe true
            it.paneNumber shouldBe 0
        }
    }

    @Test
    fun `a custom tab name survives the collapse while the identity stays the overlay's`() = runTest {
        val overlay = childInfo(caller = idA, pausableAsChild = true)
        repoState.value = WorkspaceRemote.State(
            infos = listOf(readyInfo(idA).copy(customTitle = "Holiday photos"), overlay),
        )

        items().single().let {
            it.customTitle shouldBe "Holiday photos"
            it.type shouldBe Workspace.Type.APP_DETAILS
            it.autoTitle shouldBe overlay.title
        }
    }

    @Test
    fun `the card carries the counts of the whole unit`() = runTest {
        val overlay = childInfo(caller = idA, pausableAsChild = true).copy(
            operationCount = 2,
            attentionCount = 3,
        )
        repoState.value = WorkspaceRemote.State(
            infos = listOf(readyInfo(idA).copy(operationCount = 1, attentionCount = 4), overlay),
        )

        items().single().let {
            it.operationCount shouldBe 3
            it.attentionCount shouldBe 7
        }
    }

    @Test
    fun `a dirty member marks the whole unit unsaved`() = runTest {
        val overlay = childInfo(caller = idA, pausableAsChild = true).copy(hasUnsavedChanges = true)
        repoState.value = WorkspaceRemote.State(infos = listOf(readyInfo(idA), overlay))

        items().single().let {
            it.hasUnsavedChanges shouldBe true
            // Editing without saving yet is not a fault, so it must not inflate the attention count
            it.attentionCount shouldBe 0
        }
    }

    @Test
    fun `a clean unit is not marked unsaved`() = runTest {
        val overlay = childInfo(caller = idA, pausableAsChild = true).copy(attentionCount = 2)
        repoState.value = WorkspaceRemote.State(infos = listOf(readyInfo(idA), overlay))

        items().single().hasUnsavedChanges shouldBe false
    }

    @Test
    fun `an orphan subtree becomes a single recovery card`() = runTest {
        val orphan = readyInfo(Workspace.Id()).copy(callerWorkspaceId = Workspace.Id())
        val descendant = readyInfo(Workspace.Id()).copy(callerWorkspaceId = orphan.id)
        repoState.value = WorkspaceRemote.State(infos = listOf(readyInfo(idA), orphan, descendant))

        val items = items()

        items.map { it.id } shouldBe listOf(idA, orphan.id)
        items.single { it.id == orphan.id }.let {
            it.isRecovery shouldBe true
            it.isSubWorkspace shouldBe true
            it.stackDepth shouldBe 0
            it.canPause shouldBe false
        }
    }

    @Test
    fun `selecting a tab focuses whatever is on top of it right now`() = runTest(UnconfinedTestDispatcher()) {
        val overlay = childInfo(caller = idA, pausableAsChild = true)
        repoState.value = WorkspaceRemote.State(infos = listOf(readyInfo(idA), overlay))
        // Stands in for the page manager having processed the selection, which it does before the
        // manager closes itself
        pageState.value = WorkspacePageManager.State(focusedWorkspaceId = overlay.id)

        createViewModel().selectWorkspace(idA)

        coVerify(exactly = 1) { workspacePageManager.selectWorkspaceFromManager(overlay.id) }
        coVerify(exactly = 1) { workspacePageManager.hideManagerOverlay() }
    }

    @Test
    fun `a tap on a tab whose overlay already closed selects the tab itself`() =
        runTest(UnconfinedTestDispatcher()) {
            repoState.value = WorkspaceRemote.State(infos = listOf(readyInfo(idA)))
            pageState.value = WorkspacePageManager.State(focusedWorkspaceId = idA)

            createViewModel().selectWorkspace(idA)

            coVerify(exactly = 1) { workspacePageManager.selectWorkspaceFromManager(idA) }
            coVerify(exactly = 1) { workspacePageManager.hideManagerOverlay() }
        }

    private suspend fun WorkspaceManagerViewModel.currentState() = state.filterNotNull().first()

    @Test
    fun `selection mode is off until a card starts it`() = runTest(UnconfinedTestDispatcher()) {
        repoState.value = WorkspaceRemote.State(infos = listOf(readyInfo(idA), readyInfo(idB)))
        val vm = createViewModel()

        vm.currentState().let {
            it.selectedIds shouldBe null
            it.isSelectionActive shouldBe false
            it.selectedCount shouldBe 0
        }

        vm.startSelection(idA)

        vm.currentState().let {
            it.selectedIds shouldBe setOf(idA)
            it.isSelectionActive shouldBe true
            it.allSelected shouldBe false
        }
    }

    @Test
    fun `deselecting the last card leaves selection mode`() = runTest(UnconfinedTestDispatcher()) {
        repoState.value = WorkspaceRemote.State(infos = listOf(readyInfo(idA), readyInfo(idB)))
        val vm = createViewModel()

        vm.startSelection(idA)
        vm.toggleSelection(idB)
        vm.currentState().selectedIds shouldBe setOf(idA, idB)
        vm.currentState().allSelected shouldBe true

        vm.toggleSelection(idB)
        vm.toggleSelection(idA)

        vm.currentState().isSelectionActive shouldBe false
    }

    @Test
    fun `only unit owners survive a selection, so a stacked child never becomes its own entry`() =
        runTest(UnconfinedTestDispatcher()) {
            val overlay = childInfo(caller = idA, pausableAsChild = true)
            repoState.value =
                WorkspaceRemote.State(infos = listOf(readyInfo(idA), readyInfo(idB), overlay))
            val vm = createViewModel()

            vm.selectAllTabs()

            vm.currentState().let {
                it.selectedIds shouldBe setOf(idA, idB)
                it.allSelected shouldBe true
            }
        }

    /**
     * The chip that triggers this shows the unfiltered tab count, so it has to deliver that many.
     * Clearing the filters in the same step is what keeps the selection from holding cards the grid
     * is hiding.
     */
    @Test
    fun `select all clears the filters and takes every tab`() = runTest(UnconfinedTestDispatcher()) {
        val busy = readyInfo(idA).copy(operationCount = 1)
        repoState.value = WorkspaceRemote.State(infos = listOf(busy, readyInfo(idB)))
        val vm = createViewModel()
        vm.toggleOperationsFilter()
        vm.currentState().filteredWorkspaces.map { it.id } shouldBe listOf(idA)

        vm.selectAllTabs()

        vm.currentState().let {
            it.selectedIds shouldBe setOf(idA, idB)
            it.allSelected shouldBe true
            it.filterOperations shouldBe false
            it.filteredWorkspaces.map { w -> w.id } shouldBe listOf(idA, idB)
        }
    }

    /**
     * The focused tab is never pausable, and select-all always includes it, so a partly pausable
     * selection is the normal case - pausing has to act on the eligible subset rather than refuse.
     */
    @Test
    fun `pausing a selection pauses only the pausable tabs`() = runTest(UnconfinedTestDispatcher()) {
        repoState.value = WorkspaceRemote.State(infos = listOf(readyInfo(idA), readyInfo(idB)))
        pageState.value = WorkspacePageManager.State(focusedWorkspaceId = idA)
        val vm = createViewModel()

        vm.selectAllTabs()
        vm.currentState().let {
            it.selectedCount shouldBe 2
            it.selectionPausableCount shouldBe 1
        }

        vm.pauseSelectedWorkspaces()

        coVerify(exactly = 1) { workspaceRepo.execute(WorkspaceAction.Pause(idB)) }
        coVerify(exactly = 0) { workspaceRepo.execute(WorkspaceAction.Pause(idA)) }
    }

    @Test
    fun `a tab that closes elsewhere drops out of the exposed selection`() =
        runTest(UnconfinedTestDispatcher()) {
            repoState.value = WorkspaceRemote.State(infos = listOf(readyInfo(idA), readyInfo(idB)))
            val vm = createViewModel()

            vm.startSelection(idA)
            vm.toggleSelection(idB)

            repoState.value = WorkspaceRemote.State(infos = listOf(readyInfo(idA)))

            vm.currentState().let {
                it.selectedIds shouldBe setOf(idA)
                it.allSelected shouldBe true
            }
        }

    /**
     * The set is closed as one already-confirmed batch, never as per-tab [WorkspaceAction.Close] -
     * that path raises its own confirmation for an unsaved tab, which would re-ask after the user
     * already confirmed. Skipping ids whose tab has since closed is the repo's job.
     */
    @Test
    fun `closing a selection sends one confirmed batch, not per-tab closes`() =
        runTest(UnconfinedTestDispatcher()) {
            repoState.value = WorkspaceRemote.State(infos = listOf(readyInfo(idA), readyInfo(idB)))
            val vm = createViewModel()

            vm.startSelection(idA)
            vm.toggleSelection(idB)
            vm.closeSelectedWorkspaces()

            coVerify(exactly = 1) {
                workspaceRepo.execute(WorkspaceAction.CloseSelected(setOf(idA, idB)))
            }
            // Matched by type: building a Close with any() mints a Workspace.Id whose Uuid is null,
            // and MockK stringifies the call for its matcher log, which trips Id.shortTag.
            coVerify(exactly = 0) { workspaceRepo.execute(ofType<WorkspaceAction.Close>()) }
            vm.currentState().isSelectionActive shouldBe false
        }

    /**
     * The confirming dialog dismisses the moment it is tapped, so anything that happens between the
     * confirm and the coroutine must not change what gets closed.
     */
    @Test
    fun `the confirmed set is captured before the close suspends`() =
        runTest(UnconfinedTestDispatcher()) {
            repoState.value = WorkspaceRemote.State(infos = listOf(readyInfo(idA), readyInfo(idB)))
            val vm = createViewModel()

            vm.startSelection(idA)
            vm.closeSelectedWorkspaces()
            // A new selection started right after confirming must not join the batch
            vm.startSelection(idB)

            coVerify(exactly = 1) { workspaceRepo.execute(WorkspaceAction.CloseSelected(setOf(idA))) }
            vm.currentState().selectedIds shouldBe setOf(idB)
        }

    @Test
    fun `selection mode ends when every selected tab closes elsewhere`() =
        runTest(UnconfinedTestDispatcher()) {
            repoState.value = WorkspaceRemote.State(infos = listOf(readyInfo(idA), readyInfo(idB)))
            val vm = createViewModel()

            vm.startSelection(idA)
            repoState.value = WorkspaceRemote.State(infos = listOf(readyInfo(idB)))

            vm.currentState().let {
                it.selectedIds shouldBe null
                it.isSelectionActive shouldBe false
            }
        }

    @Test
    fun `back leaves selection mode first and dismisses the manager only after`() =
        runTest(UnconfinedTestDispatcher()) {
            repoState.value = WorkspaceRemote.State(infos = listOf(readyInfo(idA)))
            val vm = createViewModel()

            vm.clearSelectionIfActive() shouldBe false

            vm.startSelection(idA)
            vm.clearSelectionIfActive() shouldBe true
            vm.clearSelectionIfActive() shouldBe false
        }

    @Test
    fun `reopening the manager never lands in selection mode`() = runTest(UnconfinedTestDispatcher()) {
        repoState.value = WorkspaceRemote.State(infos = listOf(readyInfo(idA)))
        val vm = createViewModel()

        vm.startSelection(idA)
        vm.onScreenAppeared()

        vm.currentState().isSelectionActive shouldBe false
    }
}
