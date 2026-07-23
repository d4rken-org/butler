package eu.darken.butler.workspace.ui.workspaces

import eu.darken.butler.common.ca.toCaString
import eu.darken.butler.workspace.core.Workspace
import eu.darken.butler.workspace.core.WorkspaceRemote
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import testhelpers.BaseTest

/**
 * Tests for [WorkspacesViewModel.State] computed properties.
 *
 * These tests verify the filtering logic used by UI containers:
 * - [WorkspacesViewModel.State.tabWorkspaces] - workspaces shown in pager/tabs
 * - [WorkspacesViewModel.State.fullScreenModalWorkspace] - modal overlay workspace
 * - [WorkspacesViewModel.State.paneLocalModals] - pane-specific modal overlays
 */
class WorkspacesViewModelStateTest : BaseTest() {

    private fun createWorkspaceInfo(
        id: Workspace.Id = Workspace.Id(),
        type: Workspace.Type = Workspace.Type.EXPLORER,
        callerWorkspaceId: Workspace.Id? = null,
        modalPresentation: Workspace.ModalPresentationMode = Workspace.ModalPresentationMode.FULL_SCREEN,
    ) = Workspace.Info(
        id = id,
        type = type,
        title = "Workspace ${id.shortTag}".toCaString(),
        callerWorkspaceId = callerWorkspaceId,
        modalPresentation = modalPresentation,
    )

    private fun createState(
        infos: List<Workspace.Info> = emptyList(),
        focusedWorkspace: Workspace.Id? = null,
        selectedWorkspaces: Map<Int, Workspace.Id> = emptyMap(),
        currentPaneCount: Int = 1,
    ) = WorkspacesViewModel.State(
        state = WorkspaceRemote.State(infos = infos),
        focusedWorkspace = focusedWorkspace,
        selectedWorkspaces = selectedWorkspaces,
        isUpgraded = false,
        currentPaneCount = currentPaneCount,
    )

    // region tabWorkspaces tests

    @Test
    fun `tabWorkspaces - includes only normal workspaces`() {
        val ws1 = Workspace.Id()
        val ws2 = Workspace.Id()

        val state = createState(
            infos = listOf(
                createWorkspaceInfo(id = ws1),
                createWorkspaceInfo(id = ws2),
            ),
        )

        state.tabWorkspaces.map { it.id } shouldContainExactly listOf(ws1, ws2)
    }

    @Test
    fun `tabWorkspaces - excludes sub-workspaces`() {
        val explorer = Workspace.Id()
        val picker = Workspace.Id()

        val state = createState(
            infos = listOf(
                createWorkspaceInfo(id = explorer),
                createWorkspaceInfo(id = picker, callerWorkspaceId = explorer), // sub-workspace
            ),
        )

        state.tabWorkspaces.map { it.id } shouldContainExactly listOf(explorer)
    }

    @Test
    fun `tabWorkspaces - mixed workspaces filters correctly`() {
        val explorer1 = Workspace.Id()
        val explorer2 = Workspace.Id()
        val picker1 = Workspace.Id()
        val explorer3 = Workspace.Id()
        val picker2 = Workspace.Id()

        val state = createState(
            infos = listOf(
                createWorkspaceInfo(id = explorer1),
                createWorkspaceInfo(id = picker1, callerWorkspaceId = explorer1),
                createWorkspaceInfo(id = explorer2),
                createWorkspaceInfo(id = picker2, callerWorkspaceId = explorer2),
                createWorkspaceInfo(id = explorer3),
            ),
        )

        state.tabWorkspaces.map { it.id } shouldContainExactly listOf(explorer1, explorer2, explorer3)
    }

    @Test
    fun `tabWorkspaces - empty when only sub-workspaces exist`() {
        val parent = Workspace.Id()
        val child = Workspace.Id()

        val state = createState(
            infos = listOf(
                createWorkspaceInfo(id = child, callerWorkspaceId = parent),
            ),
        )

        state.tabWorkspaces shouldBe emptyList()
    }

    @Test
    fun `tabWorkspaces - empty infos returns empty list`() {
        val state = createState(infos = emptyList())

        state.tabWorkspaces shouldBe emptyList()
    }

    // endregion

    // region fullScreenModalWorkspace tests

    @Test
    fun `fullScreenModalWorkspace - returns FULL_SCREEN modal in single pane`() {
        val explorer = Workspace.Id()
        val picker = Workspace.Id()

        val state = createState(
            infos = listOf(
                createWorkspaceInfo(id = explorer),
                createWorkspaceInfo(
                    id = picker,
                    callerWorkspaceId = explorer,
                    modalPresentation = Workspace.ModalPresentationMode.FULL_SCREEN,
                ),
            ),
            currentPaneCount = 1,
        )

        state.fullScreenModalWorkspace?.id shouldBe picker
    }

    @Test
    fun `fullScreenModalWorkspace - returns PANE_LOCAL modal in single pane as dialog`() {
        val explorer = Workspace.Id()
        val details = Workspace.Id()

        val state = createState(
            infos = listOf(
                createWorkspaceInfo(id = explorer),
                createWorkspaceInfo(
                    id = details,
                    callerWorkspaceId = explorer,
                    modalPresentation = Workspace.ModalPresentationMode.PANE_LOCAL,
                ),
            ),
            currentPaneCount = 1,
        )

        // In single pane, PANE_LOCAL modals render as full-screen dialog
        state.fullScreenModalWorkspace?.id shouldBe details
    }

    @Test
    fun `fullScreenModalWorkspace - excludes PANE_LOCAL modal in multi-pane`() {
        val explorer = Workspace.Id()
        val details = Workspace.Id()

        val state = createState(
            infos = listOf(
                createWorkspaceInfo(id = explorer),
                createWorkspaceInfo(
                    id = details,
                    callerWorkspaceId = explorer,
                    modalPresentation = Workspace.ModalPresentationMode.PANE_LOCAL,
                ),
            ),
            currentPaneCount = 2,
        )

        // In multi-pane, PANE_LOCAL modals render within parent pane, not as dialog
        state.fullScreenModalWorkspace shouldBe null
    }

    @Test
    fun `fullScreenModalWorkspace - returns FULL_SCREEN modal in multi-pane`() {
        val explorer = Workspace.Id()
        val picker = Workspace.Id()

        val state = createState(
            infos = listOf(
                createWorkspaceInfo(id = explorer),
                createWorkspaceInfo(
                    id = picker,
                    callerWorkspaceId = explorer,
                    modalPresentation = Workspace.ModalPresentationMode.FULL_SCREEN,
                ),
            ),
            currentPaneCount = 2,
        )

        // FULL_SCREEN modals always render as dialog, regardless of pane count
        state.fullScreenModalWorkspace?.id shouldBe picker
    }

    @Test
    fun `fullScreenModalWorkspace - null when no sub-workspaces`() {
        val explorer = Workspace.Id()

        val state = createState(
            infos = listOf(createWorkspaceInfo(id = explorer)),
            currentPaneCount = 1,
        )

        state.fullScreenModalWorkspace shouldBe null
    }

    @Test
    fun `fullScreenModalWorkspace - returns deepest modal in a nested chain`() {
        val tab = Workspace.Id()
        val saver = Workspace.Id()
        val picker = Workspace.Id()

        val state = createState(
            infos = listOf(
                createWorkspaceInfo(id = tab),
                createWorkspaceInfo(id = saver, callerWorkspaceId = tab),
                createWorkspaceInfo(id = picker, callerWorkspaceId = saver),
            ),
            focusedWorkspace = tab,
            currentPaneCount = 1,
        )

        state.fullScreenModalWorkspace?.id shouldBe picker
    }

    @Test
    fun `fullScreenModalWorkspace - reveals parent modal after deepest is removed`() {
        val tab = Workspace.Id()
        val saver = Workspace.Id()

        val state = createState(
            infos = listOf(
                createWorkspaceInfo(id = tab),
                createWorkspaceInfo(id = saver, callerWorkspaceId = tab),
            ),
            focusedWorkspace = tab,
            currentPaneCount = 1,
        )

        state.fullScreenModalWorkspace?.id shouldBe saver
    }

    @Test
    fun `fullScreenModalWorkspace - prefers the leaf rooted at the focused workspace`() {
        val tab1 = Workspace.Id()
        val tab2 = Workspace.Id()
        val modal1 = Workspace.Id()
        val modal2 = Workspace.Id()

        val state = createState(
            infos = listOf(
                createWorkspaceInfo(id = tab1),
                createWorkspaceInfo(id = modal1, callerWorkspaceId = tab1),
                createWorkspaceInfo(id = tab2),
                createWorkspaceInfo(id = modal2, callerWorkspaceId = tab2),
            ),
            focusedWorkspace = tab1,
            currentPaneCount = 1,
        )

        state.fullScreenModalWorkspace?.id shouldBe modal1
    }

    @Test
    fun `fullScreenModalWorkspace - matches focus on the sub-workspace itself, not the newest leaf`() {
        val tab1 = Workspace.Id()
        val tab2 = Workspace.Id()
        val modal1 = Workspace.Id()
        val modal2 = Workspace.Id()

        val state = createState(
            infos = listOf(
                createWorkspaceInfo(id = tab1),
                createWorkspaceInfo(id = modal1, callerWorkspaceId = tab1),
                createWorkspaceInfo(id = tab2),
                createWorkspaceInfo(id = modal2, callerWorkspaceId = tab2),
            ),
            // createAndFocus focuses the sub-workspace itself, not its owning tab.
            focusedWorkspace = modal1,
            currentPaneCount = 1,
        )

        state.fullScreenModalWorkspace?.id shouldBe modal1
    }

    @Test
    fun `fullScreenModalWorkspace - falls back to newest leaf when focus has no modal`() {
        val tab1 = Workspace.Id()
        val tab2 = Workspace.Id()
        val modal1 = Workspace.Id()
        val modal2 = Workspace.Id()

        val state = createState(
            infos = listOf(
                createWorkspaceInfo(id = tab1),
                createWorkspaceInfo(id = modal1, callerWorkspaceId = tab1),
                createWorkspaceInfo(id = tab2),
                createWorkspaceInfo(id = modal2, callerWorkspaceId = tab2),
            ),
            // Focus on a tab that has no modal chain -> newest (last) leaf wins deterministically.
            focusedWorkspace = Workspace.Id(),
            currentPaneCount = 1,
        )

        state.fullScreenModalWorkspace?.id shouldBe modal2
    }

    @Test
    fun `fullScreenModalWorkspace - pane-local descendant of full-screen parent renders in multi-pane`() {
        val tab = Workspace.Id()
        val fullScreenParent = Workspace.Id()
        val paneLocalChild = Workspace.Id()

        val state = createState(
            infos = listOf(
                createWorkspaceInfo(id = tab),
                createWorkspaceInfo(
                    id = fullScreenParent,
                    callerWorkspaceId = tab,
                    modalPresentation = Workspace.ModalPresentationMode.FULL_SCREEN,
                ),
                createWorkspaceInfo(
                    id = paneLocalChild,
                    callerWorkspaceId = fullScreenParent,
                    modalPresentation = Workspace.ModalPresentationMode.PANE_LOCAL,
                ),
            ),
            focusedWorkspace = tab,
            currentPaneCount = 2,
        )

        // Even though the leaf is PANE_LOCAL on multi-pane, a full-screen ancestor keeps it visible.
        state.fullScreenModalWorkspace?.id shouldBe paneLocalChild
    }

    @Test
    fun `fullScreenModalWorkspace - survives a caller cycle without crashing`() {
        val a = Workspace.Id()
        val b = Workspace.Id()

        val state = createState(
            infos = listOf(
                createWorkspaceInfo(id = a, callerWorkspaceId = b),
                createWorkspaceInfo(id = b, callerWorkspaceId = a),
            ),
            currentPaneCount = 1,
        )

        // No leaf exists in a pure cycle; must not loop forever, just resolve to null.
        state.fullScreenModalWorkspace shouldBe null
    }

    // endregion

    // region paneLocalModals tests

    @Test
    fun `paneLocalModals - empty in single pane mode`() {
        val explorer = Workspace.Id()
        val details = Workspace.Id()

        val state = createState(
            infos = listOf(
                createWorkspaceInfo(id = explorer),
                createWorkspaceInfo(
                    id = details,
                    callerWorkspaceId = explorer,
                    modalPresentation = Workspace.ModalPresentationMode.PANE_LOCAL,
                ),
            ),
            currentPaneCount = 1,
        )

        // In single pane, paneLocalModals is empty (modals render as dialog instead)
        state.paneLocalModals shouldBe emptyMap()
    }

    @Test
    fun `paneLocalModals - maps parent to child in multi-pane mode`() {
        val explorer = Workspace.Id()
        val details = Workspace.Id()

        val state = createState(
            infos = listOf(
                createWorkspaceInfo(id = explorer),
                createWorkspaceInfo(
                    id = details,
                    callerWorkspaceId = explorer,
                    modalPresentation = Workspace.ModalPresentationMode.PANE_LOCAL,
                ),
            ),
            currentPaneCount = 2,
        )

        state.paneLocalModals.size shouldBe 1
        state.paneLocalModals[explorer]?.id shouldBe details
    }

    @Test
    fun `paneLocalModals - excludes FULL_SCREEN modals`() {
        val explorer = Workspace.Id()
        val picker = Workspace.Id()

        val state = createState(
            infos = listOf(
                createWorkspaceInfo(id = explorer),
                createWorkspaceInfo(
                    id = picker,
                    callerWorkspaceId = explorer,
                    modalPresentation = Workspace.ModalPresentationMode.FULL_SCREEN,
                ),
            ),
            currentPaneCount = 2,
        )

        // FULL_SCREEN modals render as dialog, not pane-local overlay
        state.paneLocalModals shouldBe emptyMap()
    }

    @Test
    fun `paneLocalModals - multiple panes with their own modals`() {
        val explorer1 = Workspace.Id()
        val explorer2 = Workspace.Id()
        val details1 = Workspace.Id()
        val details2 = Workspace.Id()

        val state = createState(
            infos = listOf(
                createWorkspaceInfo(id = explorer1),
                createWorkspaceInfo(id = explorer2),
                createWorkspaceInfo(
                    id = details1,
                    callerWorkspaceId = explorer1,
                    modalPresentation = Workspace.ModalPresentationMode.PANE_LOCAL,
                ),
                createWorkspaceInfo(
                    id = details2,
                    callerWorkspaceId = explorer2,
                    modalPresentation = Workspace.ModalPresentationMode.PANE_LOCAL,
                ),
            ),
            currentPaneCount = 2,
        )

        state.paneLocalModals.size shouldBe 2
        state.paneLocalModals[explorer1]?.id shouldBe details1
        state.paneLocalModals[explorer2]?.id shouldBe details2
    }

    // endregion
}
