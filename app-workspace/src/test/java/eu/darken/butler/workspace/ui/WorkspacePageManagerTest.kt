package eu.darken.butler.workspace.ui

import eu.darken.butler.common.ca.toCaString
import eu.darken.butler.workspace.core.Workspace
import eu.darken.butler.workspace.core.WorkspaceEvent
import eu.darken.butler.workspace.core.WorkspaceRemote
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

    @BeforeEach
    fun setup() {
        eventsFlow = MutableSharedFlow()
        stateFlow = MutableStateFlow(WorkspaceRemote.State())

        workspaceRemote = mockk {
            every { state } returns stateFlow
            every { events } returns eventsFlow
        }

        testScope = TestScope(UnconfinedTestDispatcher())
        pageManager = WorkspacePageManager(
            appScope = testScope,
            workspaceRemote = workspaceRemote,
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
