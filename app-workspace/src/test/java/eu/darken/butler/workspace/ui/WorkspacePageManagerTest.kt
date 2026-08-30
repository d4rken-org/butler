package eu.darken.butler.workspace.ui

import androidx.lifecycle.SavedStateHandle
import eu.darken.butler.common.ca.toCaString
import eu.darken.butler.workspace.core.Workspace
import eu.darken.butler.workspace.core.WorkspaceEvent
import eu.darken.butler.workspace.core.WorkspaceRemote
import eu.darken.butler.workspace.core.undo.ClosedWorkspaceStash
import eu.darken.butler.workspace.ui.floatingbar.WorkspaceBarCollapseStates
import eu.darken.butler.workspace.ui.restore.WorkspaceViewPrefs
import eu.darken.butler.workspace.ui.scroll.WorkspaceScrollPosition
import eu.darken.butler.workspace.ui.scroll.WorkspaceScrollPositions
import io.kotest.matchers.shouldBe
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldNotBe
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
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
    private lateinit var barCollapseStates: WorkspaceBarCollapseStates
    private lateinit var viewPrefs: WorkspaceViewPrefs
    private lateinit var closedStash: ClosedWorkspaceStash

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
        barCollapseStates = WorkspaceBarCollapseStates()
        viewPrefs = WorkspaceViewPrefs()
        closedStash = ClosedWorkspaceStash(testScope)
        pageManager = WorkspacePageManager(
            appScope = testScope,
            workspaceRemote = workspaceRemote,
            scrollPositions = scrollPositions,
            barCollapseStates = barCollapseStates,
            viewPrefs = viewPrefs,
            closedStash = closedStash,
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

    /**
     * Selecting a child from the tab manager focuses the child but has to put its OWNING TAB on
     * screen: the classic pager renders the child inside that tab's page, and the adaptive layout
     * renders only assigned roots. Leaving the assignment alone would make the selection name a
     * different tab than the one the user is looking at - and make the child vanish on the next
     * layout change.
     */
    @Test
    fun `selecting an off-screen child brings its root into a pane`() = runTest {
        val visibleTab = Workspace.Id()
        val offScreenTab = Workspace.Id()
        val child = Workspace.Id()

        stateFlow.value = WorkspaceRemote.State(
            infos = listOf(
                createWorkspaceInfo(id = visibleTab),
                createWorkspaceInfo(id = offScreenTab),
                createWorkspaceInfo(id = child, callerWorkspaceId = offScreenTab),
            )
        )

        pageManager.setPaneCount(1)
        pageManager.handleWorkspaceSelection(visibleTab)

        pageManager.handleWorkspaceSelection(child)

        // Focus stays on the child - that is what was selected - but pane 0 now names its tab
        pageManager.state.value.focusedWorkspaceId shouldBe child
        pageManager.state.value.selectedWorkspaces shouldBe mapOf(0 to offScreenTab)
    }

    @Test
    fun `selecting a child stamps its root's MRU too`() = runTest {
        val visibleTab = Workspace.Id()
        val offScreenTab = Workspace.Id()
        val child = Workspace.Id()

        stateFlow.value = WorkspaceRemote.State(
            infos = listOf(
                createWorkspaceInfo(id = visibleTab),
                createWorkspaceInfo(id = offScreenTab),
                createWorkspaceInfo(id = child, callerWorkspaceId = offScreenTab),
            )
        )

        pageManager.setPaneCount(1)
        pageManager.handleWorkspaceSelection(visibleTab)
        pageManager.handleWorkspaceSelection(child)

        // Without the root's own stamp, auto-fill would rank the tab the user is working in last
        pageManager.state.value.workspaceAccessTimes[offScreenTab] shouldNotBe null
        pageManager.state.value.workspaceAccessTimes[child] shouldNotBe null
    }

    @Test
    fun `a child's root survives a widening layout`() = runTest {
        val visibleTab = Workspace.Id()
        val offScreenTab = Workspace.Id()
        val child = Workspace.Id()

        stateFlow.value = WorkspaceRemote.State(
            infos = listOf(
                createWorkspaceInfo(id = visibleTab),
                createWorkspaceInfo(id = offScreenTab),
                createWorkspaceInfo(id = child, callerWorkspaceId = offScreenTab),
            )
        )

        pageManager.setPaneCount(1)
        pageManager.handleWorkspaceSelection(visibleTab)
        pageManager.handleWorkspaceSelection(child)

        // A rotation into two panes: the adaptive layout renders assigned roots only, so the focused
        // child would disappear if its tab were not among them.
        pageManager.setPaneCount(2)

        pageManager.state.value.visiblePaneAssignments.values shouldContain offScreenTab
        pageManager.state.value.focusedWorkspaceId shouldBe child
    }

    @Test
    fun `a child's root is placed in a pane on multi-pane layouts too`() = runTest {
        val paneA = Workspace.Id()
        val paneB = Workspace.Id()
        val offScreenTab = Workspace.Id()
        val child = Workspace.Id()

        stateFlow.value = WorkspaceRemote.State(
            infos = listOf(
                createWorkspaceInfo(id = paneA),
                createWorkspaceInfo(id = paneB),
                createWorkspaceInfo(id = offScreenTab),
                createWorkspaceInfo(id = child, callerWorkspaceId = offScreenTab),
            )
        )

        pageManager.setPaneCount(2)
        pageManager.handleWorkspaceSelection(paneA)
        pageManager.handleWorkspaceSelection(paneB)

        pageManager.handleWorkspaceSelection(child)

        // Both panes were taken, so this evicts exactly as a manager selection of the tab would
        pageManager.state.value.visiblePaneAssignments.values shouldContain offScreenTab
        pageManager.state.value.focusedWorkspaceId shouldBe child
    }

    @Test
    fun `selecting a child whose root is already visible leaves the panes alone`() = runTest {
        val paneA = Workspace.Id()
        val paneB = Workspace.Id()
        val child = Workspace.Id()

        stateFlow.value = WorkspaceRemote.State(
            infos = listOf(
                createWorkspaceInfo(id = paneA),
                createWorkspaceInfo(id = paneB),
                createWorkspaceInfo(id = child, callerWorkspaceId = paneA),
            )
        )

        pageManager.setPaneCount(2)
        pageManager.handleWorkspaceSelection(paneA)
        pageManager.handleWorkspaceSelection(paneB)
        val before = pageManager.state.value.selectedWorkspaces.toMap()

        pageManager.handleWorkspaceSelection(child)

        pageManager.state.value.selectedWorkspaces shouldBe before
        pageManager.state.value.focusedWorkspaceId shouldBe child
    }

    @Test
    fun `a dangling child is focused without being placed anywhere`() = runTest {
        val visibleTab = Workspace.Id()
        val orphan = Workspace.Id()

        stateFlow.value = WorkspaceRemote.State(
            infos = listOf(
                createWorkspaceInfo(id = visibleTab),
                // Its caller is gone, so there is no tab to place. The UI falls back on its own.
                createWorkspaceInfo(id = orphan, callerWorkspaceId = Workspace.Id()),
            )
        )

        pageManager.setPaneCount(1)
        pageManager.handleWorkspaceSelection(visibleTab)

        pageManager.handleWorkspaceSelection(orphan)

        pageManager.state.value.selectedWorkspaces shouldBe mapOf(0 to visibleTab)
        pageManager.state.value.focusedWorkspaceId shouldBe orphan
    }

    @Test
    fun `a recreated page manager restores the child's root, not the previous tab`() = runTest {
        val visibleTab = Workspace.Id()
        val offScreenTab = Workspace.Id()
        val child = Workspace.Id()

        stateFlow.value = WorkspaceRemote.State(
            infos = listOf(
                createWorkspaceInfo(id = visibleTab),
                createWorkspaceInfo(id = offScreenTab),
                createWorkspaceInfo(id = child, callerWorkspaceId = offScreenTab),
            )
        )

        pageManager.setPaneCount(1)
        pageManager.handleWorkspaceSelection(visibleTab)
        pageManager.handleWorkspaceSelection(child)

        // What the ViewModel mirrors into the SavedStateHandle every time the state changes
        val saved = pageManager.state.value
        val savedStateHandle = mockk<SavedStateHandle> {
            every { get<WorkspacePageManager.State>("workspaceUIState") } returns saved
        }

        val recreated = WorkspacePageManager(
            appScope = testScope,
            workspaceRemote = workspaceRemote,
            scrollPositions = scrollPositions,
            barCollapseStates = barCollapseStates,
            viewPrefs = viewPrefs,
            closedStash = closedStash,
        )
        recreated.initializeFromSavedState(savedStateHandle)

        recreated.state.value.selectedWorkspaces shouldBe mapOf(0 to offScreenTab)
        recreated.state.value.focusedWorkspaceId shouldBe child
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
    fun `regular workspace selection when all panes full replaces the least recently used pane`() = runTest {
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

        // workspace1 is the older of the two, so its pane makes way.
        pageManager.state.value.selectedWorkspaces shouldBe mapOf(
            0 to workspace3,
            1 to workspace2,
        )
    }

    // ==================== Pane placement policy ====================

    /**
     * Quad panes are column-major (see WorkspaceDesign.forPane), so 1 borders 0 and 3 while 2 sits
     * diagonally across. Each case leaves an empty pane that is a neighbour and one that isn't, and
     * the non-neighbour is the one plain ascending order would pick.
     */
    private suspend fun selectWithSourcePane(
        occupied: Map<Int, Workspace.Id>,
        source: Workspace.Id,
        target: Workspace.Id,
    ): Map<Int, Workspace.Id> {
        stateFlow.value = WorkspaceRemote.State(
            infos = (occupied.values + target).map { createWorkspaceInfo(id = it) },
        )
        pageManager.setPaneCount(4)
        pageManager.applyRestoredUIState(null, occupied)

        pageManager.handleWorkspaceSelection(target, sourceWorkspaceId = source)

        return pageManager.state.value.selectedWorkspaces
    }

    @Test
    fun `an empty pane adjacent to pane 0 is preferred`() = runTest {
        val source = Workspace.Id()
        val target = Workspace.Id()

        val selections = selectWithSourcePane(
            occupied = mapOf(0 to source),
            source = source,
            target = target,
        )

        selections[1] shouldBe target
    }

    @Test
    fun `an empty pane adjacent to pane 1 is preferred over the lowest empty index`() = runTest {
        val source = Workspace.Id()
        val other = Workspace.Id()
        val target = Workspace.Id()

        val selections = selectWithSourcePane(
            occupied = mapOf(0 to other, 1 to source),
            source = source,
            target = target,
        )

        // 3 neighbours 1; 2 is the diagonal and would win on index order.
        selections[3] shouldBe target
    }

    @Test
    fun `an empty pane adjacent to pane 2 is preferred over the lowest empty index`() = runTest {
        val source = Workspace.Id()
        val other = Workspace.Id()
        val target = Workspace.Id()

        val selections = selectWithSourcePane(
            occupied = mapOf(0 to other, 2 to source),
            source = source,
            target = target,
        )

        selections[3] shouldBe target
    }

    @Test
    fun `an empty pane adjacent to pane 3 is preferred over the lowest empty index`() = runTest {
        val source = Workspace.Id()
        val other = Workspace.Id()
        val target = Workspace.Id()

        val selections = selectWithSourcePane(
            occupied = mapOf(1 to other, 3 to source),
            source = source,
            target = target,
        )

        selections[2] shouldBe target
    }

    @Test
    fun `all panes full evicts the least recently used pane but never the invoking one`() = runTest {
        val pane0 = Workspace.Id()
        val pane1 = Workspace.Id()
        val pane2 = Workspace.Id()
        val pane3 = Workspace.Id()
        val target = Workspace.Id()

        stateFlow.value = WorkspaceRemote.State(
            infos = listOf(pane0, pane1, pane2, pane3, target).map { createWorkspaceInfo(id = it) },
        )
        pageManager.setPaneCount(4)
        // Visited in ascending order, so pane 0 holds the oldest and pane 3 the newest.
        pageManager.handleWorkspaceSelection(pane0)
        pageManager.handleWorkspaceSelection(pane1)
        pageManager.handleWorkspaceSelection(pane2)
        pageManager.handleWorkspaceSelection(pane3)

        pageManager.handleWorkspaceSelection(target, sourceWorkspaceId = pane0)

        val after = pageManager.state.value
        // The list the user acted from stays put, and the next-oldest pane makes way instead.
        after.selectedWorkspaces[0] shouldBe pane0
        after.selectedWorkspaces[1] shouldBe target
        after.focusedWorkspaceId shouldBe target
    }

    /**
     * Explicitly NOT about the pager double-settle: eviction lives in assignPane, which is only
     * reachable from handleWorkspaceSelection - the sub-workspace/manager path a swipe never takes -
     * and the Classic phone layout is single-pane anyway. Kept as page-manager hardening that
     * nothing else currently pins: a repeated selection must not move the eviction victim.
     */
    @Test
    fun `all panes full still evicts the least recently used pane after a repeated selection`() = runTest {
        val pane0 = Workspace.Id()
        val pane1 = Workspace.Id()
        val pane2 = Workspace.Id()
        val pane3 = Workspace.Id()
        val target = Workspace.Id()

        stateFlow.value = WorkspaceRemote.State(
            infos = listOf(pane0, pane1, pane2, pane3, target).map { createWorkspaceInfo(id = it) },
        )
        pageManager.setPaneCount(4)
        pageManager.handleWorkspaceSelection(pane0)
        pageManager.handleWorkspaceSelection(pane1)
        pageManager.handleWorkspaceSelection(pane2)
        pageManager.handleWorkspaceSelection(pane3)
        // Selecting what is already selected and focused: only its MRU stamp is refreshed.
        pageManager.handleWorkspaceSelection(pane3)

        pageManager.handleWorkspaceSelection(target, sourceWorkspaceId = pane0)

        val after = pageManager.state.value
        // Unchanged by the duplicate: pane 0 is the protected source, pane 1 the oldest of the rest.
        after.selectedWorkspaces shouldBe mapOf(
            0 to pane0,
            1 to target,
            2 to pane2,
            3 to pane3,
        )
        after.focusedWorkspaceId shouldBe target
    }

    /** A modal occupies no pane of its own, so the raw id would protect nothing. */
    @Test
    fun `a modal source protects the pane of the tab that owns it`() = runTest {
        val owner = Workspace.Id()
        val other = Workspace.Id()
        val modal = Workspace.Id()
        val target = Workspace.Id()

        stateFlow.value = WorkspaceRemote.State(
            infos = listOf(
                createWorkspaceInfo(id = owner),
                createWorkspaceInfo(id = other),
                createWorkspaceInfo(id = modal, callerWorkspaceId = owner),
                createWorkspaceInfo(id = target),
            )
        )
        pageManager.setPaneCount(2)
        pageManager.handleWorkspaceSelection(owner)
        pageManager.handleWorkspaceSelection(other)

        pageManager.handleWorkspaceSelection(target, sourceWorkspaceId = modal)

        val after = pageManager.state.value
        // Without rootOf() the owner's pane would be the LRU victim.
        after.selectedWorkspaces[0] shouldBe owner
        after.selectedWorkspaces[1] shouldBe target
    }

    @Test
    fun `a null source does not prefer adjacency`() = runTest {
        val pane0 = Workspace.Id()
        val pane1 = Workspace.Id()
        val target = Workspace.Id()

        stateFlow.value = WorkspaceRemote.State(
            infos = listOf(pane0, pane1, target).map { createWorkspaceInfo(id = it) },
        )
        pageManager.setPaneCount(4)
        pageManager.applyRestoredUIState(null, mapOf(1 to pane0, 3 to pane1))

        pageManager.handleWorkspaceSelection(target)

        // Plain ascending order: pane 0, not a neighbour of anything in particular.
        pageManager.state.value.selectedWorkspaces[0] shouldBe target
    }

    @Test
    fun `an auto-focused create with all panes full still lands in a pane`() = runTest {
        val pane0 = Workspace.Id()
        val pane1 = Workspace.Id()
        val created = Workspace.Id()

        stateFlow.value = WorkspaceRemote.State(
            infos = listOf(createWorkspaceInfo(id = pane0), createWorkspaceInfo(id = pane1)),
        )
        pageManager.setPaneCount(2)
        pageManager.handleWorkspaceSelection(pane0)
        pageManager.handleWorkspaceSelection(pane1)

        eventsFlow.emit(
            WorkspaceEvent.Created(workspaceId = created, autoFocus = true, sourceWorkspaceId = pane0)
        )
        stateFlow.value = WorkspaceRemote.State(
            infos = listOf(pane0, pane1, created).map { createWorkspaceInfo(id = it) },
        )
        testScope.testScheduler.advanceUntilIdle()

        val after = pageManager.state.value
        after.focusedWorkspaceId shouldBe created
        // A focused workspace that occupies no pane is invisible - the Editor-tab defect.
        after.selectedWorkspaces.values shouldContain created
        after.selectedWorkspaces[0] shouldBe pane0
    }

    @Test
    fun `a background create with all panes full evicts nothing`() = runTest {
        val pane0 = Workspace.Id()
        val pane1 = Workspace.Id()
        val created = Workspace.Id()

        stateFlow.value = WorkspaceRemote.State(
            infos = listOf(createWorkspaceInfo(id = pane0), createWorkspaceInfo(id = pane1)),
        )
        pageManager.setPaneCount(2)
        pageManager.handleWorkspaceSelection(pane0)
        pageManager.handleWorkspaceSelection(pane1)

        // Batch "open in new tabs", session restore and the tab manager all create without asking
        // for focus; replacing visible content for them would move a pane the user isn't looking at.
        eventsFlow.emit(
            WorkspaceEvent.Created(workspaceId = created, autoFocus = false, sourceWorkspaceId = pane0)
        )
        stateFlow.value = WorkspaceRemote.State(
            infos = listOf(pane0, pane1, created).map { createWorkspaceInfo(id = it) },
        )
        testScope.testScheduler.advanceUntilIdle()

        val after = pageManager.state.value
        after.selectedWorkspaces shouldBe mapOf(0 to pane0, 1 to pane1)
        after.focusedWorkspaceId shouldBe pane1
    }

    @Test
    fun `a background create with a null source and all panes full evicts nothing`() = runTest {
        val pane0 = Workspace.Id()
        val pane1 = Workspace.Id()
        val created = Workspace.Id()

        stateFlow.value = WorkspaceRemote.State(
            infos = listOf(createWorkspaceInfo(id = pane0), createWorkspaceInfo(id = pane1)),
        )
        pageManager.setPaneCount(2)
        pageManager.handleWorkspaceSelection(pane0)
        pageManager.handleWorkspaceSelection(pane1)

        eventsFlow.emit(WorkspaceEvent.Created(workspaceId = created))
        stateFlow.value = WorkspaceRemote.State(
            infos = listOf(pane0, pane1, created).map { createWorkspaceInfo(id = it) },
        )
        testScope.testScheduler.advanceUntilIdle()

        pageManager.state.value.selectedWorkspaces shouldBe mapOf(0 to pane0, 1 to pane1)
    }

    /**
     * A replace races the stale-ID cleanup observer: when the repo publishes the replacement before
     * the Created event is handled, cleanup has already dropped the old workspace from its pane and
     * nulled focus. The replace then falls through to the "not in any pane" branch, which still owes
     * the new workspace the focus the create asked for - otherwise the tab opens unfocused and the
     * first typed characters go nowhere.
     */
    @Test
    fun `a replace whose old workspace was already cleaned up still auto-focuses`() = runTest {
        val old = Workspace.Id()
        val replacement = Workspace.Id()

        stateFlow.value = WorkspaceRemote.State(infos = listOf(createWorkspaceInfo(id = old)))
        pageManager.setPaneCount(1)
        pageManager.handleWorkspaceSelection(old)

        // Cleanup wins the race: the old workspace is gone from the repo before Created arrives
        stateFlow.value = WorkspaceRemote.State(infos = listOf(createWorkspaceInfo(id = replacement)))
        pageManager.state.value.focusedWorkspaceId shouldBe null
        pageManager.state.value.selectedWorkspaces shouldBe emptyMap()

        eventsFlow.emit(
            WorkspaceEvent.Created(workspaceId = replacement, replacedId = old, autoFocus = true)
        )
        testScope.testScheduler.advanceUntilIdle()

        val after = pageManager.state.value
        after.focusedWorkspaceId shouldBe replacement
        after.selectedWorkspaces shouldBe mapOf(0 to replacement)
    }

    @Test
    fun `a cleaned-up replace without auto-focus still takes the vacant focus`() = runTest {
        val old = Workspace.Id()
        val replacement = Workspace.Id()

        stateFlow.value = WorkspaceRemote.State(infos = listOf(createWorkspaceInfo(id = old)))
        pageManager.setPaneCount(1)
        pageManager.handleWorkspaceSelection(old)

        stateFlow.value = WorkspaceRemote.State(infos = listOf(createWorkspaceInfo(id = replacement)))

        eventsFlow.emit(
            WorkspaceEvent.Created(workspaceId = replacement, replacedId = old, autoFocus = false)
        )
        testScope.testScheduler.advanceUntilIdle()

        // Nothing else is focused after cleanup, so the fallback focuses the new workspace
        pageManager.state.value.focusedWorkspaceId shouldBe replacement
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

    /** Keys by recency, most recent first. Deliberately an order, not the raw stamps - see below. */
    private fun WorkspacePageManager.State.mruRank(): List<Workspace.Id> =
        workspaceAccessTimes.entries.sortedByDescending { it.value }.map { it.key }

    /**
     * A tab swipe settles into setLayout, and one swipe used to apply the identical layout twice.
     * The invariant pinned here: a repeated identical apply preserves focus, pane assignment and MRU
     * rank, while permitting a timestamp refresh. It is deliberately NOT a strict-equality claim over
     * workspaceAccessTimes - the second apply legitimately re-stamps the focused workspace with a
     * later Clock.System.now().
     */
    @Test
    fun `a repeated layout apply leaves selections, focus and MRU rank unchanged`() = runTest {
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
        // Definite access order: ws1 oldest, ws3 newest.
        pageManager.setLayout(mapOf(0 to ws1), focusedId = ws1)
        pageManager.setLayout(mapOf(0 to ws2), focusedId = ws2)
        pageManager.setLayout(mapOf(0 to ws3), focusedId = ws3)

        val before = pageManager.state.value

        pageManager.setLayout(mapOf(0 to ws3), focusedId = ws3)

        val after = pageManager.state.value
        after.selectedWorkspaces shouldBe before.selectedWorkspaces
        after.focusedWorkspaceId shouldBe before.focusedWorkspaceId
        after.mruRank() shouldBe before.mruRank()
    }

    @Test
    fun `a repeated layout apply does not change the MRU successor when that tab closes`() = runTest {
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
        pageManager.setLayout(mapOf(0 to ws1), focusedId = ws1)
        pageManager.setLayout(mapOf(0 to ws2), focusedId = ws2)
        pageManager.setLayout(mapOf(0 to ws3), focusedId = ws3)
        // The duplicate has to sit on the tab that closes: handleWorkspaceClosed drops the closing
        // workspace from the successor candidates, so duplicating a survivor could not fail here.
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

        // The extra stamp only touched ws3, so the surviving MRU is still ws2.
        pageManager.state.value.focusedWorkspaceId shouldBe ws2
    }

    private fun recordScroll(id: Workspace.Id, slot: String = "list") {
        scrollPositions.record(scrollPositions.positionFor(id, slot), WorkspaceScrollPosition(7, 3))
    }

    private fun recordViewPref(id: Workspace.Id, slot: String = "explorer.sort") {
        viewPrefs.mutateSlot(id, slot) { JsonPrimitive("pref") }
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
    fun `closing a workspace forgets its view prefs`() = runTest {
        val kept = Workspace.Id()
        val closed = Workspace.Id()
        recordViewPref(kept)
        recordViewPref(closed)

        eventsFlow.emit(WorkspaceEvent.Closed(workspaceId = closed))
        testScope.testScheduler.advanceUntilIdle()

        viewPrefs.snapshot().keys shouldBe setOf(kept)
    }

    @Test
    fun `replacing a workspace forgets the replaced view prefs`() = runTest {
        val replaced = Workspace.Id()
        val replacement = Workspace.Id()
        recordViewPref(replaced)
        recordViewPref(replacement)

        stateFlow.value = WorkspaceRemote.State(infos = listOf(createWorkspaceInfo(id = replacement)))
        eventsFlow.emit(WorkspaceEvent.Created(workspaceId = replacement, replacedId = replaced))
        testScope.testScheduler.advanceUntilIdle()

        viewPrefs.snapshot().keys shouldBe setOf(replacement)
    }

    /** Without this, every tab's prefs would survive into the next session. */
    @Test
    fun `closing all workspaces clears every view pref`() = runTest {
        recordViewPref(Workspace.Id())
        recordViewPref(Workspace.Id())

        eventsFlow.emit(WorkspaceEvent.AllClosed)
        testScope.testScheduler.advanceUntilIdle()

        viewPrefs.snapshot() shouldBe emptyMap()
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
    /**
     * A workspace parked on a pane index a narrower layout no longer renders is invisible, and the
     * tab manager now presents it as unselected. Selecting it must therefore put it on screen -
     * treating the stale assignment as "already selected" would close the manager and change
     * nothing the user can see.
     */
    @Test
    fun `selecting a workspace stranded on a hidden pane brings it on screen`() = runTest {
        val paneOne = Workspace.Id()
        val paneTwo = Workspace.Id()
        val stranded = Workspace.Id()

        stateFlow.value = WorkspaceRemote.State(
            infos = listOf(
                createWorkspaceInfo(id = paneOne),
                createWorkspaceInfo(id = paneTwo),
                createWorkspaceInfo(id = stranded),
            )
        )
        pageManager.setPaneCount(4)
        pageManager.applyRestoredUIState(null, mapOf(0 to paneOne, 1 to paneTwo, 3 to stranded))
        pageManager.setPaneCount(2)

        pageManager.state.value.visiblePaneAssignments.values shouldNotContain stranded

        pageManager.handleWorkspaceSelection(stranded)

        val after = pageManager.state.value
        after.focusedWorkspaceId shouldBe stranded
        // Now actually on screen...
        after.visiblePaneAssignments.values shouldContain stranded
        // ...and only in one place, not both its old hidden pane and a new one.
        after.selectedWorkspaces.values.count { it == stranded } shouldBe 1
    }

    /** Another workspace's retained assignment must survive that relocation. */
    @Test
    fun `relocating a stranded workspace leaves other retained assignments alone`() = runTest {
        val paneOne = Workspace.Id()
        val stranded = Workspace.Id()
        val otherHidden = Workspace.Id()

        stateFlow.value = WorkspaceRemote.State(
            infos = listOf(
                createWorkspaceInfo(id = paneOne),
                createWorkspaceInfo(id = stranded),
                createWorkspaceInfo(id = otherHidden),
            )
        )
        pageManager.setPaneCount(4)
        pageManager.applyRestoredUIState(null, mapOf(0 to paneOne, 2 to stranded, 3 to otherHidden))
        pageManager.setPaneCount(2)

        pageManager.handleWorkspaceSelection(stranded)

        val after = pageManager.state.value
        after.visiblePaneAssignments.values shouldContain stranded
        // The unrelated hidden arrangement is retained for when the layout grows back.
        after.selectedWorkspaces[3] shouldBe otherHidden
    }

    /** A workspace already in a rendered pane is only focused - no reshuffling. */
    @Test
    fun `selecting a visible workspace does not move it`() = runTest {
        val paneOne = Workspace.Id()
        val paneTwo = Workspace.Id()

        stateFlow.value = WorkspaceRemote.State(
            infos = listOf(createWorkspaceInfo(id = paneOne), createWorkspaceInfo(id = paneTwo))
        )
        pageManager.setPaneCount(2)
        pageManager.applyRestoredUIState(null, mapOf(0 to paneOne, 1 to paneTwo))

        pageManager.handleWorkspaceSelection(paneTwo)

        val after = pageManager.state.value
        after.focusedWorkspaceId shouldBe paneTwo
        after.selectedWorkspaces shouldBe mapOf(0 to paneOne, 1 to paneTwo)
    }

    /**
     * A session restore landing while [WorkspacePageManager.setPaneCount] is suspended on the
     * workspace list must survive. The stub flow performs the restore and only then emits, so the
     * write is guaranteed to land inside the suspension window.
     *
     * The candidates are ordered ahead of the restored ids on purpose: with them behind, the
     * auto-fill's own map would coincide with the restored one and the test could not tell a
     * discarded restore from a preserved one.
     */
    @Test
    fun `a restore during the auto-fill suspension is not discarded`() = runTest {
        val restoredA = Workspace.Id()
        val restoredB = Workspace.Id()
        val candidateC = Workspace.Id()
        val candidateD = Workspace.Id()

        val infos = listOf(
            createWorkspaceInfo(id = candidateC),
            createWorkspaceInfo(id = candidateD),
            createWorkspaceInfo(id = restoredA),
            createWorkspaceInfo(id = restoredB),
        )
        stateFlow.value = WorkspaceRemote.State(infos = infos)

        every { workspaceRemote.state } returns flow {
            pageManager.applyRestoredUIState(
                focusedId = restoredA,
                selectedWorkspaces = mapOf(0 to restoredA, 1 to restoredB),
            )
            emit(WorkspaceRemote.State(infos = infos))
        }

        pageManager.setPaneCount(2)

        pageManager.state.value.selectedWorkspaces shouldBe mapOf(0 to restoredA, 1 to restoredB)
    }

    /** The workspace a concurrent write just placed must not be handed a second pane as well. */
    @Test
    fun `the auto-fill does not duplicate a workspace placed during its suspension`() = runTest {
        val placed = Workspace.Id()
        val candidate = Workspace.Id()

        val infos = listOf(createWorkspaceInfo(id = placed), createWorkspaceInfo(id = candidate))
        stateFlow.value = WorkspaceRemote.State(infos = infos)

        every { workspaceRemote.state } returns flow {
            pageManager.applyRestoredUIState(focusedId = placed, selectedWorkspaces = mapOf(0 to placed))
            emit(WorkspaceRemote.State(infos = infos))
        }

        pageManager.setPaneCount(2)

        pageManager.state.value.selectedWorkspaces shouldBe mapOf(0 to placed, 1 to candidate)
    }

    /** Respecting that write must not degrade into filling nothing at all. */
    @Test
    fun `the auto-fill still fills panes the concurrent write left empty`() = runTest {
        val placed = Workspace.Id()
        val candidate = Workspace.Id()

        val infos = listOf(createWorkspaceInfo(id = placed), createWorkspaceInfo(id = candidate))
        stateFlow.value = WorkspaceRemote.State(infos = infos)

        every { workspaceRemote.state } returns flow {
            pageManager.applyRestoredUIState(focusedId = placed, selectedWorkspaces = mapOf(0 to placed))
            emit(WorkspaceRemote.State(infos = infos))
        }

        pageManager.setPaneCount(2)

        val after = pageManager.state.value.selectedWorkspaces
        after shouldBe mapOf(0 to placed, 1 to candidate)
        after[1] shouldNotBe after[0]
    }

    /**
     * Rotating, folding or resizing can complete a narrower pane count while a wider one is still
     * suspended. The superseded request must not park workspaces on indices the current layout does
     * not render - they are retained rather than pruned, so they would surface pre-filled the next
     * time the user expands - and it must still fill the panes the layout does have, or the user is
     * left staring at empty panes with nothing scheduled to fill them.
     */
    @Test
    fun `a superseded pane count request does not populate panes the layout lost`() = runTest {
        val infos = List(4) { createWorkspaceInfo() }
        stateFlow.value = WorkspaceRemote.State(infos = infos)

        var narrowed = false
        every { workspaceRemote.state } returns flow {
            if (!narrowed) {
                narrowed = true
                pageManager.setPaneCount(2)
            }
            emit(WorkspaceRemote.State(infos = infos))
        }

        pageManager.setPaneCount(4)

        val after = pageManager.state.value
        after.currentPaneCount shouldBe 2
        after.selectedWorkspaces[2] shouldBe null
        after.selectedWorkspaces[3] shouldBe null
        after.selectedWorkspaces[0] shouldNotBe null
        after.selectedWorkspaces[1] shouldNotBe null
        after.selectedWorkspaces[1] shouldNotBe after.selectedWorkspaces[0]
    }

    /** Growing back into panes that still hold their retained assignments needs no workspace list. */
    @Test
    fun `a growth into fully assigned panes does not collect the workspace list`() = runTest {
        val paneOne = Workspace.Id()
        val paneTwo = Workspace.Id()

        val infos = listOf(createWorkspaceInfo(id = paneOne), createWorkspaceInfo(id = paneTwo))
        stateFlow.value = WorkspaceRemote.State(infos = infos)
        pageManager.applyRestoredUIState(null, mapOf(0 to paneOne, 1 to paneTwo))

        var collections = 0
        every { workspaceRemote.state } returns flow {
            collections++
            emit(WorkspaceRemote.State(infos = infos))
        }

        pageManager.setPaneCount(2)

        collections shouldBe 0
        pageManager.state.value.selectedWorkspaces shouldBe mapOf(0 to paneOne, 1 to paneTwo)
    }
}
