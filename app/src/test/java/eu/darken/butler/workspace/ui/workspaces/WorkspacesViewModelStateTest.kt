package eu.darken.butler.workspace.ui.workspaces

import eu.darken.butler.common.ca.toCaString
import eu.darken.butler.workspace.core.Workspace
import eu.darken.butler.workspace.core.WorkspaceRemote
import eu.darken.butler.workspace.ui.WorkspacePageManager
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
 * - [WorkspacesViewModel.State.paneLocalModalChains] - pane-scoped modal stacks
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

    private fun paneLocalModal(
        id: Workspace.Id = Workspace.Id(),
        caller: Workspace.Id,
    ) = createWorkspaceInfo(
        id = id,
        callerWorkspaceId = caller,
        modalPresentation = Workspace.ModalPresentationMode.PANE_LOCAL,
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
        // Derived exactly as the page manager derives it, so tests cannot drift from production.
        visiblePaneSelections = WorkspacePageManager.State(
            selectedWorkspaces = selectedWorkspaces,
            currentPaneCount = currentPaneCount,
        ).visiblePaneAssignments,
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
    fun `fullScreenModalWorkspace - excludes PANE_LOCAL modal in single pane`() {
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

        // A single-pane layout has one pane, not none: the chain stacks inside its owning tab's page
        state.fullScreenModalWorkspace shouldBe null
        state.paneLocalModalChains[explorer]?.map { it.id } shouldBe listOf(details)
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

    // region paneLocalModalChains tests

    @Test
    fun `paneLocalModalChains - maps parent to child in single pane mode`() {
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

        state.paneLocalModalChains.size shouldBe 1
        state.paneLocalModalChains[explorer]?.map { it.id } shouldBe listOf(details)
    }

    @Test
    fun `paneLocalModalChains - maps parent to child in multi-pane mode`() {
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

        state.paneLocalModalChains.size shouldBe 1
        state.paneLocalModalChains[explorer]?.map { it.id } shouldBe listOf(details)
    }

    @Test
    fun `paneLocalModalChains - excludes FULL_SCREEN modals`() {
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
        state.paneLocalModalChains shouldBe emptyMap()
    }

    @Test
    fun `paneLocalModalChains - multiple panes with their own modals`() {
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

        state.paneLocalModalChains.size shouldBe 2
        state.paneLocalModalChains[explorer1]?.map { it.id } shouldBe listOf(details1)
        state.paneLocalModalChains[explorer2]?.map { it.id } shouldBe listOf(details2)
    }

    @Test
    fun `paneLocalModalChains - stacks a three-deep chain under its root tab`() {
        val apps = Workspace.Id()
        val details = Workspace.Id()
        val saver = Workspace.Id()
        val picker = Workspace.Id()

        val state = createState(
            infos = listOf(
                createWorkspaceInfo(id = apps),
                paneLocalModal(id = details, caller = apps),
                paneLocalModal(id = saver, caller = details),
                paneLocalModal(id = picker, caller = saver),
            ),
            currentPaneCount = 2,
        )

        // Nearest-tab-first, so the pane can stack it straight onto its own workspace
        state.paneLocalModalChains.keys shouldBe setOf(apps)
        state.paneLocalModalChains[apps]?.map { it.id } shouldBe listOf(details, saver, picker)
        state.fullScreenModalWorkspace shouldBe null
    }

    @Test
    fun `paneLocalModalChains - focus on a shared ancestor picks the newest branch`() {
        val tab = Workspace.Id()
        val shared = Workspace.Id()
        val branch1 = Workspace.Id()
        val branch2 = Workspace.Id()

        val state = createState(
            infos = listOf(
                createWorkspaceInfo(id = tab),
                paneLocalModal(id = shared, caller = tab),
                paneLocalModal(id = branch1, caller = shared),
                paneLocalModal(id = branch2, caller = shared),
            ),
            // Focus identifies the branch only down to the shared ancestor
            focusedWorkspace = shared,
            currentPaneCount = 2,
        )

        state.paneLocalModalChains[tab]?.map { it.id } shouldBe listOf(shared, branch2)
    }

    @Test
    fun `paneLocalModalChains - focus on one leaf picks that branch`() {
        val tab = Workspace.Id()
        val shared = Workspace.Id()
        val branch1 = Workspace.Id()
        val branch2 = Workspace.Id()

        val state = createState(
            infos = listOf(
                createWorkspaceInfo(id = tab),
                paneLocalModal(id = shared, caller = tab),
                paneLocalModal(id = branch1, caller = shared),
                paneLocalModal(id = branch2, caller = shared),
            ),
            focusedWorkspace = branch1,
            currentPaneCount = 2,
        )

        state.paneLocalModalChains[tab]?.map { it.id } shouldBe listOf(shared, branch1)
    }

    @Test
    fun `paneLocalModalChains - a full-screen ancestor keeps the whole chain out of the panes`() {
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
                paneLocalModal(id = paneLocalChild, caller = fullScreenParent),
            ),
            currentPaneCount = 2,
        )

        // The two slots are mutually exclusive: the chain renders once, above all panes
        state.paneLocalModalChains shouldBe emptyMap()
        state.fullScreenModalWorkspace?.id shouldBe paneLocalChild
    }

    @Test
    fun `paneLocalModalChains - a full-screen and a pane-local chain resolve side by side`() {
        val tab1 = Workspace.Id()
        val tab2 = Workspace.Id()
        val fullScreenModal = Workspace.Id()
        val paneLocal = Workspace.Id()

        val state = createState(
            infos = listOf(
                createWorkspaceInfo(id = tab1),
                createWorkspaceInfo(
                    id = fullScreenModal,
                    callerWorkspaceId = tab1,
                    modalPresentation = Workspace.ModalPresentationMode.FULL_SCREEN,
                ),
                createWorkspaceInfo(id = tab2),
                paneLocalModal(id = paneLocal, caller = tab2),
            ),
            currentPaneCount = 2,
        )

        state.fullScreenModalWorkspace?.id shouldBe fullScreenModal
        state.paneLocalModalChains.keys shouldBe setOf(tab2)
        state.paneLocalModalChains[tab2]?.map { it.id } shouldBe listOf(paneLocal)
    }

    // endregion

    // region focusedRootId

    @Test
    fun `focusedRootId - a focused tab is its own root`() {
        val tab = Workspace.Id()

        val state = createState(
            infos = listOf(createWorkspaceInfo(id = tab)),
            focusedWorkspace = tab,
        )

        state.focusedRootId shouldBe tab
    }

    @Test
    fun `focusedRootId - a focused child resolves to the tab that owns it`() {
        val tab = Workspace.Id()
        val details = Workspace.Id()
        val saver = Workspace.Id()

        val state = createState(
            infos = listOf(
                createWorkspaceInfo(id = tab),
                paneLocalModal(id = details, caller = tab),
                paneLocalModal(id = saver, caller = details),
            ),
            // A tab-manager selection or createAndFocus puts focus on the child itself
            focusedWorkspace = saver,
        )

        // The raw id names no tab at all, which is what makes resolving through the chain necessary
        state.tabWorkspaces.map { it.id } shouldContainExactly listOf(tab)
        state.focusedRootId shouldBe tab
    }

    @Test
    fun `focusedRootId - null without focus`() {
        val state = createState(infos = listOf(createWorkspaceInfo()))

        state.focusedRootId shouldBe null
    }

    @Test
    fun `focusedRootId - null for a dangling or cyclic chain`() {
        val orphan = Workspace.Id()
        val a = Workspace.Id()
        val b = Workspace.Id()

        createState(
            infos = listOf(paneLocalModal(id = orphan, caller = Workspace.Id())),
            focusedWorkspace = orphan,
        ).focusedRootId shouldBe null

        createState(
            infos = listOf(
                paneLocalModal(id = a, caller = b),
                paneLocalModal(id = b, caller = a),
            ),
            focusedWorkspace = a,
        ).focusedRootId shouldBe null
    }

    // endregion

    // region chain resolution guards

    @Test
    fun `single pane stacks a deep pane-local chain under its root tab`() {
        val tab = Workspace.Id()
        val first = Workspace.Id()
        val second = Workspace.Id()
        val third = Workspace.Id()

        val state = createState(
            infos = listOf(
                createWorkspaceInfo(id = tab),
                paneLocalModal(id = first, caller = tab),
                paneLocalModal(id = second, caller = first),
                paneLocalModal(id = third, caller = second),
            ),
            currentPaneCount = 1,
        )

        state.fullScreenModalWorkspace shouldBe null
        state.paneLocalModalChains.keys shouldBe setOf(tab)
        state.paneLocalModalChains[tab]?.map { it.id } shouldBe listOf(first, second, third)
    }

    @Test
    fun `a modal whose caller no longer exists is dropped from both slots`() {
        val tab = Workspace.Id()
        val orphan = Workspace.Id()

        listOf(1, 2).forEach { paneCount ->
            val state = createState(
                infos = listOf(
                    createWorkspaceInfo(id = tab),
                    // Caller closed without taking its child with it
                    paneLocalModal(id = orphan, caller = Workspace.Id()),
                ),
                currentPaneCount = paneCount,
            )

            state.fullScreenModalWorkspace shouldBe null
            state.paneLocalModalChains shouldBe emptyMap()
        }
    }

    @Test
    fun `a leaf whose ancestry enters a cycle is dropped from both slots`() {
        val leaf = Workspace.Id()
        val a = Workspace.Id()
        val b = Workspace.Id()

        listOf(1, 2).forEach { paneCount ->
            val state = createState(
                infos = listOf(
                    // leaf -> a -> b -> a: a real leaf hanging off a cycle, so the leaf filter
                    // alone does not catch it and the walk must terminate on its own.
                    paneLocalModal(id = leaf, caller = a),
                    paneLocalModal(id = a, caller = b),
                    paneLocalModal(id = b, caller = a),
                ),
                currentPaneCount = paneCount,
            )

            state.fullScreenModalWorkspace shouldBe null
            state.paneLocalModalChains shouldBe emptyMap()
        }
    }

    // endregion

    // region pane assignment visibility

    /**
     * A workspace parked on a pane index a narrower layout no longer renders is open but invisible.
     * Pane badges must not claim it occupies a pane that is not on screen, while pane assignment
     * still sees it - narrowing that would drop the arrangement a wider layout left behind.
     */
    @Test
    fun `visibleSelected drops panes the layout does not render, selected keeps them`() {
        val paneOne = Workspace.Id()
        val paneTwo = Workspace.Id()
        val hidden = Workspace.Id()

        val state = createState(
            infos = listOf(
                createWorkspaceInfo(id = paneOne),
                createWorkspaceInfo(id = paneTwo),
                createWorkspaceInfo(id = hidden),
            ),
            selectedWorkspaces = mapOf(0 to paneOne, 1 to paneTwo, 3 to hidden),
            currentPaneCount = 2,
        )

        state.visibleSelected.keys.sorted() shouldContainExactly listOf(0, 1)
        state.visibleSelected.values.map { it.id } shouldContainExactly listOf(paneOne, paneTwo)

        // Retained for assignment and for the layout growing back.
        state.selected.keys.sorted() shouldContainExactly listOf(0, 1, 3)
        state.selected[3]?.id shouldBe hidden
    }

    @Test
    fun `visibleSelected matches selected when every pane is rendered`() {
        val paneOne = Workspace.Id()
        val paneTwo = Workspace.Id()

        val state = createState(
            infos = listOf(createWorkspaceInfo(id = paneOne), createWorkspaceInfo(id = paneTwo)),
            selectedWorkspaces = mapOf(0 to paneOne, 1 to paneTwo),
            currentPaneCount = 2,
        )

        // Whole maps, not just keys - over-filtering would drop a value while keeping its key.
        state.visibleSelected shouldBe state.selected
    }

    @Test
    fun `expanding the layout reveals the retained assignment`() {
        val paneOne = Workspace.Id()
        val hidden = Workspace.Id()
        val infos = listOf(createWorkspaceInfo(id = paneOne), createWorkspaceInfo(id = hidden))
        val assignments = mapOf(0 to paneOne, 3 to hidden)

        createState(infos, selectedWorkspaces = assignments, currentPaneCount = 2)
            .visibleSelected.keys.sorted() shouldContainExactly listOf(0)

        createState(infos, selectedWorkspaces = assignments, currentPaneCount = 4)
            .visibleSelected.keys.sorted() shouldContainExactly listOf(0, 3)
    }

    // endregion
}
