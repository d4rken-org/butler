package eu.darken.butler.workspace.ui

import eu.darken.butler.workspace.core.Workspace
import io.kotest.matchers.shouldBe
import org.junit.Test
import testhelpers.BaseTest

/**
 * Pane assignments outlive layout changes on purpose, so the raw map can hold indices no pane
 * renders. Everything that tells the user where a workspace is has to ask the layout, not the map.
 */
class VisiblePaneAssignmentsTest : BaseTest() {

    private val first = Workspace.Id()
    private val second = Workspace.Id()
    private val third = Workspace.Id()
    private val fourth = Workspace.Id()

    private fun state(
        assignments: Map<Int, Workspace.Id>,
        paneCount: Int,
    ) = WorkspacePageManager.State(
        selectedWorkspaces = assignments,
        currentPaneCount = paneCount,
    )

    @Test
    fun `assignments within the layout are all visible`() {
        val state = state(mapOf(0 to first, 1 to second), paneCount = 2)

        state.visiblePaneAssignments shouldBe mapOf(0 to first, 1 to second)
    }

    /** The reported case: a quad arrangement collapsed to two panes. */
    @Test
    fun `assignments beyond the pane count are hidden`() {
        val state = state(mapOf(0 to first, 1 to second, 3 to fourth), paneCount = 2)

        state.visiblePaneAssignments shouldBe mapOf(0 to first, 1 to second)
    }

    /** Collapsing must not destroy the arrangement - expanding again has to bring it back. */
    @Test
    fun `hidden assignments survive in the raw map and return when the layout grows`() {
        val assignments = mapOf(0 to first, 1 to second, 2 to third, 3 to fourth)

        val collapsed = state(assignments, paneCount = 2)
        collapsed.visiblePaneAssignments shouldBe mapOf(0 to first, 1 to second)
        collapsed.selectedWorkspaces shouldBe assignments

        val expanded = collapsed.copy(currentPaneCount = 4)
        expanded.visiblePaneAssignments shouldBe assignments
    }

    @Test
    fun `a single pane layout shows only the first pane`() {
        val state = state(mapOf(0 to first, 1 to second, 3 to fourth), paneCount = 1)

        state.visiblePaneAssignments shouldBe mapOf(0 to first)
    }

    @Test
    fun `negative indices are never visible`() {
        val state = state(mapOf(-1 to first, 0 to second), paneCount = 2)

        state.visiblePaneAssignments shouldBe mapOf(0 to second)
    }

    @Test
    fun `no assignments means nothing visible`() {
        state(emptyMap(), paneCount = 4).visiblePaneAssignments shouldBe emptyMap()
    }
}
