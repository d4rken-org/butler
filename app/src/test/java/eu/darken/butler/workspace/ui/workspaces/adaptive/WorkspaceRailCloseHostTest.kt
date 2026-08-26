package eu.darken.butler.workspace.ui.workspaces.adaptive

import eu.darken.butler.common.ca.toCaString
import eu.darken.butler.workspace.core.Workspace
import eu.darken.butler.workspace.ui.workspaces.WorkspacePaneInfo
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import testhelpers.BaseTest

/**
 * The rail lists every tab, but a close confirmation can only be seen in a pane that is on screen.
 */
class WorkspaceRailCloseHostTest : BaseTest() {

    private val paneZero = Workspace.Id()
    private val paneOne = Workspace.Id()
    private val offScreen = Workspace.Id()

    private fun paneInfo(id: Workspace.Id) = WorkspacePaneInfo(
        id = id,
        type = Workspace.Type.EXPLORER,
        lifecycleState = Workspace.LifecycleState.Ready,
        title = "Explorer".toCaString(),
    )

    private val visible = mapOf(1 to paneInfo(paneOne), 0 to paneInfo(paneZero))

    @Test
    fun `a tab with a pane of its own is closed unanchored`() {
        railCloseHost(
            closingPaneIndex = 0,
            visibleAssignments = visible,
            focusedId = paneOne,
        ).shouldBeNull()
    }

    @Test
    fun `a tab without a pane borrows the focused one`() {
        railCloseHost(
            closingPaneIndex = null,
            visibleAssignments = visible,
            focusedId = paneOne,
        ) shouldBe paneOne
    }

    @Test
    fun `an unresolvable focus falls back to the first pane`() {
        // A stacked child, or nothing focused at all: neither names a tab that holds a pane, and
        // anchoring to the closing tab is what hid the dialog in the first place.
        railCloseHost(
            closingPaneIndex = null,
            visibleAssignments = visible,
            focusedId = offScreen,
        ) shouldBe paneZero

        railCloseHost(
            closingPaneIndex = null,
            visibleAssignments = visible,
            focusedId = null,
        ) shouldBe paneZero
    }

    @Test
    fun `nothing on screen leaves the close unanchored`() {
        railCloseHost(
            closingPaneIndex = null,
            visibleAssignments = emptyMap(),
            focusedId = offScreen,
        ).shouldBeNull()
    }
}
