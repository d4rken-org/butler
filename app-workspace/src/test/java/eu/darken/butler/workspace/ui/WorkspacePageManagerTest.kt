package eu.darken.butler.workspace.ui

import eu.darken.butler.common.ca.toCaString
import eu.darken.butler.workspace.core.Workspace
import eu.darken.butler.workspace.core.WorkspaceEvent
import eu.darken.butler.workspace.core.WorkspaceRemote
import eu.darken.butler.workspace.ui.scroll.WorkspaceScrollPosition
import eu.darken.butler.workspace.ui.scroll.WorkspaceScrollPositions
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import testhelpers.BaseTest

class WorkspacePageManagerTest : BaseTest() {

    private lateinit var workspaceRemote: WorkspaceRemote
    private lateinit var eventsFlow: MutableSharedFlow<WorkspaceEvent>
    private lateinit var stateFlow: MutableStateFlow<WorkspaceRemote.State>
    private lateinit var testScope: TestScope
    private lateinit var pageManager: WorkspacePageManager
    private lateinit var scrollPositions: WorkspaceScrollPositions

    @BeforeEach
    fun setup() {
        eventsFlow = MutableSharedFlow()
        stateFlow = MutableStateFlow(WorkspaceRemote.State())

        workspaceRemote = mockk {
            every { state } returns stateFlow
            every { events } returns eventsFlow
        }

        testScope = TestScope(UnconfinedTestDispatcher())
        scrollPositions = WorkspaceScrollPositions()
        pageManager = WorkspacePageManager(
            appScope = testScope,
            workspaceRemote = workspaceRemote,
            scrollPositions = scrollPositions,
        )
    }

    private fun createWorkspaceInfo(
        id: Workspace.Id = Workspace.Id(),
        type: Workspace.Type = Workspace.Type.EXPLORER,
        callerWorkspaceId: Workspace.Id? = null,
    ) = Workspace.Info(
        id = id,
        type = type,
        title = "Workspace ${id.shortTag}".toCaString(),
        callerWorkspaceId = callerWorkspaceId,
    )

    @Test
    fun `sub-workspace selection only updates focus without modifying pane selections`() = runTest {
        val explorer1 = Workspace.Id()
        val apps = Workspace.Id()
        val explorer2 = Workspace.Id()
        val appDetails = Workspace.Id()

        stateFlow.value = WorkspaceRemote.State(
            infos = listOf(
                createWorkspaceInfo(id = explorer1),
                createWorkspaceInfo(id = apps),
                createWorkspaceInfo(id = explorer2),
                createWorkspaceInfo(id = appDetails, callerWorkspaceId = apps),
            )
        )

        pageManager.setPaneCount(3)
        pageManager.handleWorkspaceSelection(explorer1)
        pageManager.handleWorkspaceSelection(apps)
        pageManager.handleWorkspaceSelection(explorer2)

        val selectionsBefore = pageManager.state.value.selectedWorkspaces.toMap()

        pageManager.handleWorkspaceSelection(appDetails)

        pageManager.state.value.focusedWorkspaceId shouldBe appDetails
        pageManager.state.value.selectedWorkspaces shouldBe selectionsBefore
    }

    @Test
    fun `selecting a newly created workspace with all panes full keeps it visible and focused`() = runTest {
        // Guards the butler-button quick-create path: createAndFocus emits SelectionRequested for the
        // new workspace, which must land it in a pane (not just focus it) so it can't become
        // focused-but-invisible when every pane is occupied.
        val paneA = Workspace.Id()
        val paneB = Workspace.Id()
        val created = Workspace.Id()

        stateFlow.value = WorkspaceRemote.State(
            infos = listOf(
                createWorkspaceInfo(id = paneA),
                createWorkspaceInfo(id = paneB),
                createWorkspaceInfo(id = created),
            )
        )

        pageManager.setPaneCount(2)
        pageManager.handleWorkspaceSelection(paneA)
        pageManager.handleWorkspaceSelection(paneB)
        pageManager.state.value.selectedWorkspaces.values.toSet() shouldBe setOf(paneA, paneB)

        pageManager.handleWorkspaceSelection(created)

        val state = pageManager.state.value
        state.focusedWorkspaceId shouldBe created
        // Focus is visible: the newly focused workspace occupies a pane (pane 0 is replaced).
        state.selectedWorkspaces.containsValue(created) shouldBe true
        state.selectedWorkspaces[0] shouldBe created
    }

    @Test
    fun `sub-workspace selection updates MRU timestamp`() = runTest {
        val parentWorkspace = Workspace.Id()
        val subWorkspace = Workspace.Id()

        stateFlow.value = WorkspaceRemote.State(
            infos = listOf(
                createWorkspaceInfo(id = parentWorkspace),
                createWorkspaceInfo(id = subWorkspace, callerWorkspaceId = parentWorkspace),
            )
        )

        val accessTimesBefore = pageManager.state.value.workspaceAccessTimes[subWorkspace]

        pageManager.handleWorkspaceSelection(subWorkspace)

        val accessTimesAfter = pageManager.state.value.workspaceAccessTimes[subWorkspace]
        accessTimesAfter shouldNotBe null
        if (accessTimesBefore != null) {
            accessTimesAfter!! shouldNotBe accessTimesBefore
        }
    }

    @Test
    fun `regular workspace selection in single pane mode replaces current selection`() = runTest {
        val workspace1 = Workspace.Id()
        val workspace2 = Workspace.Id()

        stateFlow.value = WorkspaceRemote.State(
            infos = listOf(
                createWorkspaceInfo(id = workspace1),
                createWorkspaceInfo(id = workspace2),
            )
        )

        pageManager.setPaneCount(1)
        pageManager.handleWorkspaceSelection(workspace1)

        pageManager.handleWorkspaceSelection(workspace2)

        pageManager.state.value.selectedWorkspaces shouldBe mapOf(0 to workspace2)
        pageManager.state.value.focusedWorkspaceId shouldBe workspace2
    }

    @Test
    fun `regular workspace selection in multi-pane mode uses empty pane`() = runTest {
        val workspace1 = Workspace.Id()
        val workspace2 = Workspace.Id()

        stateFlow.value = WorkspaceRemote.State(
            infos = listOf(
                createWorkspaceInfo(id = workspace1),
                createWorkspaceInfo(id = workspace2),
            )
        )

        pageManager.setPaneCount(2)
        pageManager.handleWorkspaceSelection(workspace1)

        pageManager.handleWorkspaceSelection(workspace2)

        pageManager.state.value.selectedWorkspaces shouldBe mapOf(
            0 to workspace1,
            1 to workspace2,
        )
        pageManager.state.value.focusedWorkspaceId shouldBe workspace2
    }

    @Test
    fun `regular workspace selection when all panes full replaces pane 0`() = runTest {
        val workspace1 = Workspace.Id()
        val workspace2 = Workspace.Id()
        val workspace3 = Workspace.Id()

        stateFlow.value = WorkspaceRemote.State(
            infos = listOf(
                createWorkspaceInfo(id = workspace1),
                createWorkspaceInfo(id = workspace2),
                createWorkspaceInfo(id = workspace3),
            )
        )

        pageManager.setPaneCount(2)
        pageManager.handleWorkspaceSelection(workspace1)
        pageManager.handleWorkspaceSelection(workspace2)

        pageManager.handleWorkspaceSelection(workspace3)

        pageManager.state.value.selectedWorkspaces shouldBe mapOf(
            0 to workspace3,
            1 to workspace2,
        )
    }

    @Test
    fun `selecting already-selected workspace just updates focus`() = runTest {
        val workspace1 = Workspace.Id()
        val workspace2 = Workspace.Id()

        stateFlow.value = WorkspaceRemote.State(
            infos = listOf(
                createWorkspaceInfo(id = workspace1),
                createWorkspaceInfo(id = workspace2),
            )
        )

        pageManager.setPaneCount(2)
        pageManager.handleWorkspaceSelection(workspace1)
        pageManager.handleWorkspaceSelection(workspace2)

        pageManager.state.value.focusedWorkspaceId shouldBe workspace2

        pageManager.handleWorkspaceSelection(workspace1)

        pageManager.state.value.focusedWorkspaceId shouldBe workspace1
        pageManager.state.value.selectedWorkspaces shouldBe mapOf(
            0 to workspace1,
            1 to workspace2,
        )
    }

    @Test
    fun `closing sub-workspace returns to caller workspace`() = runTest {
        val callerWorkspace = Workspace.Id()
        val subWorkspace = Workspace.Id()

        stateFlow.value = WorkspaceRemote.State(
            infos = listOf(
                createWorkspaceInfo(id = callerWorkspace),
                createWorkspaceInfo(id = subWorkspace, callerWorkspaceId = callerWorkspace),
            )
        )

        pageManager.setPaneCount(1)
        pageManager.handleWorkspaceSelection(callerWorkspace)
        pageManager.handleWorkspaceSelection(subWorkspace)

        eventsFlow.emit(
            WorkspaceEvent.Closed(
                workspaceId = subWorkspace,
                callerWorkspaceId = callerWorkspace,
            )
        )
        testScope.testScheduler.advanceUntilIdle()

        pageManager.state.value.focusedWorkspaceId shouldBe callerWorkspace
    }

    @Test
    fun `sub-workspace does not replace pane when all panes are full`() = runTest {
        val ws1 = Workspace.Id()
        val ws2 = Workspace.Id()
        val ws3 = Workspace.Id()
        stateFlow.value = WorkspaceRemote.State(
            infos = listOf(
                createWorkspaceInfo(id = ws1),
                createWorkspaceInfo(id = ws2),
                createWorkspaceInfo(id = ws3),
            )
        )
        pageManager.setPaneCount(3)
        pageManager.handleWorkspaceSelection(ws1)
        pageManager.handleWorkspaceSelection(ws2)
        pageManager.handleWorkspaceSelection(ws3)

        val subWs = Workspace.Id()
        // Emit Created — handleWorkspaceCreated suspends waiting for subWs in state
        eventsFlow.emit(WorkspaceEvent.Created(subWs))
        // Add subWs to state — unblocks handleWorkspaceCreated which sees isSubWorkspace=true
        stateFlow.value = WorkspaceRemote.State(
            infos = listOf(
                createWorkspaceInfo(id = ws1),
                createWorkspaceInfo(id = ws2),
                createWorkspaceInfo(id = ws3),
                createWorkspaceInfo(id = subWs, callerWorkspaceId = ws1),
            )
        )
        eventsFlow.emit(WorkspaceEvent.SelectionRequested(subWs))
        testScope.testScheduler.advanceUntilIdle()

        pageManager.state.value.selectedWorkspaces shouldBe mapOf(0 to ws1, 1 to ws2, 2 to ws3)
        pageManager.state.value.focusedWorkspaceId shouldBe subWs
    }

    @Test
    fun `sub-workspace is not assigned to empty pane`() = runTest {
        val ws1 = Workspace.Id()
        val ws2 = Workspace.Id()
        val subWs = Workspace.Id()
        pageManager.setPaneCount(3)
        stateFlow.value = WorkspaceRemote.State(
            infos = listOf(
                createWorkspaceInfo(id = ws1),
                createWorkspaceInfo(id = ws2),
            )
        )
        pageManager.handleWorkspaceSelection(ws1)
        pageManager.handleWorkspaceSelection(ws2)
        // Pane 2 is empty

        eventsFlow.emit(WorkspaceEvent.Created(subWs))
        stateFlow.value = WorkspaceRemote.State(
            infos = listOf(
                createWorkspaceInfo(id = ws1),
                createWorkspaceInfo(id = ws2),
                createWorkspaceInfo(id = subWs, callerWorkspaceId = ws1),
            )
        )
        testScope.testScheduler.advanceUntilIdle()

        pageManager.state.value.selectedWorkspaces shouldBe mapOf(0 to ws1, 1 to ws2)
    }

    @Test
    fun `closing focused workspace focuses surviving MRU not the closed tab`() = runTest {
        val ws1 = Workspace.Id()
        val ws2 = Workspace.Id()
        val ws3 = Workspace.Id()

        stateFlow.value = WorkspaceRemote.State(
            infos = listOf(
                createWorkspaceInfo(id = ws1),
                createWorkspaceInfo(id = ws2),
                createWorkspaceInfo(id = ws3),
            )
        )

        pageManager.setPaneCount(1)
        pageManager.handleWorkspaceSelection(ws1)
        pageManager.handleWorkspaceSelection(ws2)
        pageManager.handleWorkspaceSelection(ws3)

        // Race: Closed arrives while the exported state still replays the pre-removal snapshot
        // (ws3 — the just-focused MRU tab — is still present).
        eventsFlow.emit(WorkspaceEvent.Closed(workspaceId = ws3, callerWorkspaceId = null))
        testScope.testScheduler.advanceUntilIdle()

        // Then the repo state catches up and drops ws3.
        stateFlow.value = WorkspaceRemote.State(
            infos = listOf(
                createWorkspaceInfo(id = ws1),
                createWorkspaceInfo(id = ws2),
            )
        )
        testScope.testScheduler.advanceUntilIdle()

        // Must land on a survivor (MRU = ws2), never null (which would arm placeholder auto-create).
        pageManager.state.value.focusedWorkspaceId shouldBe ws2
    }

    @Test
    fun `closing focused workspace recovers focus when cleanup strands it first`() = runTest {
        val ws1 = Workspace.Id()
        val ws2 = Workspace.Id()
        val ws3 = Workspace.Id()

        stateFlow.value = WorkspaceRemote.State(
            infos = listOf(
                createWorkspaceInfo(id = ws1),
                createWorkspaceInfo(id = ws2),
                createWorkspaceInfo(id = ws3),
            )
        )

        pageManager.setPaneCount(1)
        pageManager.handleWorkspaceSelection(ws1)
        pageManager.handleWorkspaceSelection(ws2)
        pageManager.handleWorkspaceSelection(ws3)

        // Cleanup-first ordering: repo state drops ws3 (nulls focus via cleanup observer) BEFORE the
        // Closed event reaches the manager.
        stateFlow.value = WorkspaceRemote.State(
            infos = listOf(
                createWorkspaceInfo(id = ws1),
                createWorkspaceInfo(id = ws2),
            )
        )
        testScope.testScheduler.advanceUntilIdle()
        pageManager.state.value.focusedWorkspaceId shouldBe null // stranded by cleanup

        // Closed event arrives late and must still recover focus to a survivor (never leave it null).
        eventsFlow.emit(WorkspaceEvent.Closed(workspaceId = ws3, callerWorkspaceId = null))
        testScope.testScheduler.advanceUntilIdle()

        pageManager.state.value.focusedWorkspaceId shouldBe ws2
        pageManager.state.value.selectedWorkspaces shouldBe mapOf(0 to ws2)
    }

    @Test
    fun `closing sub-workspace returns to caller even when cleanup strands focus first`() = runTest {
        val callerWorkspace = Workspace.Id()
        val otherWorkspace = Workspace.Id()
        val subWorkspace = Workspace.Id()

        stateFlow.value = WorkspaceRemote.State(
            infos = listOf(
                createWorkspaceInfo(id = callerWorkspace),
                createWorkspaceInfo(id = otherWorkspace),
                createWorkspaceInfo(id = subWorkspace, callerWorkspaceId = callerWorkspace),
            )
        )

        pageManager.setPaneCount(1)
        pageManager.handleWorkspaceSelection(callerWorkspace)
        pageManager.handleWorkspaceSelection(otherWorkspace) // newer MRU than the caller
        pageManager.handleWorkspaceSelection(subWorkspace)

        // Cleanup strands focus before the Closed event arrives.
        stateFlow.value = WorkspaceRemote.State(
            infos = listOf(
                createWorkspaceInfo(id = callerWorkspace),
                createWorkspaceInfo(id = otherWorkspace),
            )
        )
        testScope.testScheduler.advanceUntilIdle()

        // The late Closed event carries callerWorkspaceId; focus must return to the caller, not the
        // global MRU (otherWorkspace).
        eventsFlow.emit(WorkspaceEvent.Closed(workspaceId = subWorkspace, callerWorkspaceId = callerWorkspace))
        testScope.testScheduler.advanceUntilIdle()

        pageManager.state.value.focusedWorkspaceId shouldBe callerWorkspace
    }

    @Test
    fun `closing an unrelated workspace does not steal focus from an open sub-workspace`() = runTest {
        val ws1 = Workspace.Id()
        val ws2 = Workspace.Id()
        val subWorkspace = Workspace.Id()

        stateFlow.value = WorkspaceRemote.State(
            infos = listOf(
                createWorkspaceInfo(id = ws1),
                createWorkspaceInfo(id = ws2),
                createWorkspaceInfo(id = subWorkspace, callerWorkspaceId = ws1),
            )
        )

        pageManager.setPaneCount(1)
        pageManager.handleWorkspaceSelection(ws1)
        pageManager.handleWorkspaceSelection(ws2)
        pageManager.handleWorkspaceSelection(subWorkspace) // modal sub-workspace is focused

        // An unrelated normal workspace closes while the sub-workspace is focused. The sub-workspace
        // is still valid focus and must not be treated as stranded.
        eventsFlow.emit(WorkspaceEvent.Closed(workspaceId = ws1, callerWorkspaceId = null))
        testScope.testScheduler.advanceUntilIdle()

        pageManager.state.value.focusedWorkspaceId shouldBe subWorkspace
    }

    @Test
    fun `closing the last workspace clears focus`() = runTest {
        val ws1 = Workspace.Id()

        stateFlow.value = WorkspaceRemote.State(
            infos = listOf(createWorkspaceInfo(id = ws1)),
        )

        pageManager.setPaneCount(1)
        pageManager.handleWorkspaceSelection(ws1)

        eventsFlow.emit(WorkspaceEvent.Closed(workspaceId = ws1, callerWorkspaceId = null))
        testScope.testScheduler.advanceUntilIdle()

        stateFlow.value = WorkspaceRemote.State(infos = emptyList())
        testScope.testScheduler.advanceUntilIdle()

        pageManager.state.value.focusedWorkspaceId shouldBe null
    }

    @Test
    fun `setLayout applies selections and focus atomically with an MRU stamp`() = runTest {
        val ws1 = Workspace.Id()
        val ws2 = Workspace.Id()

        pageManager.setLayout(mapOf(0 to ws1), focusedId = ws1)
        pageManager.setLayout(mapOf(0 to ws2), focusedId = ws2)

        val state = pageManager.state.value
        state.selectedWorkspaces shouldBe mapOf(0 to ws2)
        state.focusedWorkspaceId shouldBe ws2
        state.workspaceAccessTimes[ws2] shouldNotBe null
    }

    @Test
    fun `setLayout falls back when requested focus is not in the selections`() = runTest {
        val ws1 = Workspace.Id()
        val ws2 = Workspace.Id()

        pageManager.setLayout(mapOf(0 to ws1), focusedId = ws1)
        pageManager.setLayout(mapOf(0 to ws1), focusedId = ws2)

        pageManager.state.value.focusedWorkspaceId shouldBe ws1
    }

    @Test
    fun `setLayout keeps a newly created workspace focused when panes are full`() = runTest {
        // Guards the CreateForPane path: the old setFocusedWorkspace()+setSelectedWorkspaces()
        // two-step no-opped the focus change (new id not yet selected) and then kept the old
        // focus (old id still among the new selections), so the fresh pane never got focus.
        val paneA = Workspace.Id()
        val paneB = Workspace.Id()
        val created = Workspace.Id()

        pageManager.setPaneCount(2)
        pageManager.setLayout(mapOf(0 to paneA, 1 to paneB), focusedId = paneA)

        pageManager.setLayout(mapOf(0 to paneA, 1 to created), focusedId = created)

        val state = pageManager.state.value
        state.focusedWorkspaceId shouldBe created
        state.selectedWorkspaces shouldBe mapOf(0 to paneA, 1 to created)
    }

    @Test
    fun `MRU refocus after close honors selection recency from setLayout`() = runTest {
        // Swipe-selects go through setLayout, so a tab visited via swipe must outrank
        // earlier-visited tabs when the closed workspace's focus is reassigned.
        val ws1 = Workspace.Id()
        val ws2 = Workspace.Id()
        val ws3 = Workspace.Id()

        stateFlow.value = WorkspaceRemote.State(
            infos = listOf(
                createWorkspaceInfo(id = ws1),
                createWorkspaceInfo(id = ws2),
                createWorkspaceInfo(id = ws3),
            )
        )

        pageManager.setPaneCount(1)
        // Visit order: ws2, then ws1, then ws3 — most recent survivor of a ws3 close is ws1.
        pageManager.setLayout(mapOf(0 to ws2), focusedId = ws2)
        pageManager.setLayout(mapOf(0 to ws1), focusedId = ws1)
        pageManager.setLayout(mapOf(0 to ws3), focusedId = ws3)

        eventsFlow.emit(WorkspaceEvent.Closed(workspaceId = ws3, callerWorkspaceId = null))
        testScope.testScheduler.advanceUntilIdle()

        stateFlow.value = WorkspaceRemote.State(
            infos = listOf(
                createWorkspaceInfo(id = ws1),
                createWorkspaceInfo(id = ws2),
            )
        )
        testScope.testScheduler.advanceUntilIdle()

        pageManager.state.value.focusedWorkspaceId shouldBe ws1
    }

    private fun recordScroll(id: Workspace.Id, slot: String = "list") {
        scrollPositions.record(scrollPositions.positionFor(id, slot), WorkspaceScrollPosition(7, 3))
    }

    @Test
    fun `closing a workspace forgets its scroll positions`() = runTest {
        val kept = Workspace.Id()
        val closed = Workspace.Id()
        recordScroll(kept)
        recordScroll(closed)

        eventsFlow.emit(WorkspaceEvent.Closed(workspaceId = closed))
        testScope.testScheduler.advanceUntilIdle()

        scrollPositions.snapshot().keys shouldBe setOf(kept)
    }

    @Test
    fun `closing all workspaces clears every scroll position`() = runTest {
        recordScroll(Workspace.Id())
        recordScroll(Workspace.Id())

        eventsFlow.emit(WorkspaceEvent.AllClosed)
        testScope.testScheduler.advanceUntilIdle()

        scrollPositions.snapshot() shouldBe emptyMap()
    }

    /**
     * A replace (Templates morphing a tab into another type) retires the old workspace without ever
     * emitting Closed, so this is the only place its slots can be dropped.
     */
    @Test
    fun `replacing a workspace forgets the replaced scroll positions`() = runTest {
        val replaced = Workspace.Id()
        val replacement = Workspace.Id()
        recordScroll(replaced)
        recordScroll(replacement)

        stateFlow.value = WorkspaceRemote.State(infos = listOf(createWorkspaceInfo(id = replacement)))
        eventsFlow.emit(WorkspaceEvent.Created(workspaceId = replacement, replacedId = replaced))
        testScope.testScheduler.advanceUntilIdle()

        scrollPositions.snapshot().keys shouldBe setOf(replacement)
    }

    @Test
    fun `a plain creation keeps the new workspace's scroll positions`() = runTest {
        val created = Workspace.Id()
        recordScroll(created)

        stateFlow.value = WorkspaceRemote.State(infos = listOf(createWorkspaceInfo(id = created)))
        eventsFlow.emit(WorkspaceEvent.Created(workspaceId = created))
        testScope.testScheduler.advanceUntilIdle()

        scrollPositions.snapshot().keys shouldBe setOf(created)
    }

    @Test
    fun `manager overlay is hidden by default`() = runTest {
        pageManager.state.value.isManagerOverlayVisible shouldBe false
    }

    @Test
    fun `showManagerOverlay sets visibility to true`() = runTest {
        pageManager.showManagerOverlay()
        pageManager.state.value.isManagerOverlayVisible shouldBe true
    }

    @Test
    fun `hideManagerOverlay sets visibility to false`() = runTest {
        pageManager.showManagerOverlay()
        pageManager.state.value.isManagerOverlayVisible shouldBe true

        pageManager.hideManagerOverlay()
        pageManager.state.value.isManagerOverlayVisible shouldBe false
    }
}
