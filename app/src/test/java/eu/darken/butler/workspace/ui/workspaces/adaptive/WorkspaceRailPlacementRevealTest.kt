package eu.darken.butler.workspace.ui.workspaces.adaptive

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.filter
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import eu.darken.butler.common.ca.toCaString
import eu.darken.butler.common.compose.PreviewWrapper
import eu.darken.butler.workspace.core.Workspace
import eu.darken.butler.workspace.ui.manager.WorkspaceDesign
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import org.junit.Test
import org.robolectric.annotation.Config
import testhelpers.ComposeTest

/**
 * The two rail placements have viewports of very different length: on a phone-sized window the start
 * edge shows the whole tab list at once, the bottom edge shows three entries. An entry that was
 * comfortably on screen along one axis can therefore be far outside the other without focus or tab
 * order changing, so the reveal has to run against the axis the rail actually ended up on.
 */
@Config(qualifiers = "w411dp-h891dp")
class WorkspaceRailPlacementRevealTest : ComposeTest() {

    private val tabs = (0 until TAB_COUNT).map {
        Workspace.Info(
            id = Workspace.Id(),
            type = Workspace.Type.EXPLORER,
            title = title(it).toCaString(),
            lifecycleState = Workspace.LifecycleState.Ready,
        )
    }

    private val focusedTab = tabs.last()
    private val focusedTitle = title(TAB_COUNT - 1)

    private var placement by mutableStateOf(WorkspaceDesign.RailPlacement.START)

    private fun setRail() {
        // The mascot on the Butler button animates on an endless loop, which never lets the clock idle.
        composeTestRule.mainClock.autoAdvance = false
        composeTestRule.setContent {
            PreviewWrapper {
                Box(modifier = Modifier.fillMaxSize()) {
                    WorkspaceNavigationRail(
                        modifier = Modifier.align(
                            when (placement) {
                                WorkspaceDesign.RailPlacement.START -> Alignment.CenterStart
                                WorkspaceDesign.RailPlacement.BOTTOM -> Alignment.BottomCenter
                            },
                        ),
                        workspaces = tabs,
                        selected = emptyMap(),
                        focusedId = focusedTab.id,
                        design = WorkspaceDesign(
                            layout = WorkspaceDesign.Layout.DUAL_HORIZONTAL,
                            railPlacement = placement,
                        ),
                        onTabAction = {},
                        onPaneAssignment = { _, _ -> },
                        onPaneUnassign = {},
                    )
                }
            }
        }
        settle()
    }

    /** Enough frames for a reveal animation to run to completion, without an auto-advancing clock. */
    private fun settle(rounds: Int = 30) {
        repeat(rounds) {
            composeTestRule.mainClock.advanceTimeBy(64)
            composeTestRule.waitForIdle()
        }
    }

    private fun focusedEntry() = composeTestRule.onAllNodesWithTag(ITEM_TAG).filter(hasText(focusedTitle))

    private fun listBounds() = composeTestRule.onNodeWithTag(LIST_TAG).getUnclippedBoundsInRoot()

    private fun describeFocusedEntry(): String {
        val list = listBounds()
        val entries = focusedEntry()
        if (entries.fetchSemanticsNodes().isEmpty()) {
            return "it is not composed at all, and the list spans " + list.left + " to " + list.right
        }
        val bounds = entries[0].getUnclippedBoundsInRoot()
        return "it spans " + bounds.left + " to " + bounds.right +
            " while the list spans " + list.left + " to " + list.right
    }

    private fun focusedEntryIsInsideList(): Boolean {
        val list = listBounds()
        val entries = focusedEntry()
        if (entries.fetchSemanticsNodes().isEmpty()) return false
        val bounds = entries[0].getUnclippedBoundsInRoot()
        if (bounds.left.value.compareTo(list.left.value) < 0) return false
        return bounds.right.value.compareTo(list.right.value) <= 0
    }

    /**
     * The control: the focused entry is the last of [TAB_COUNT] and the start-edge list fits every
     * one of them, so nothing has to scroll for it to be on screen. Without this the horizontal case
     * would prove nothing - an entry already out of view before the switch could be left out of view
     * for reasons that have nothing to do with the placement.
     */
    @Test
    fun `the last tab is already on screen along the start edge`() {
        setRail()

        val list = listBounds()
        val entry = focusedEntry()[0].getUnclippedBoundsInRoot()

        withClue("The vertical rail has to fit every tab for the horizontal case to mean anything") {
            (entry.top.value.compareTo(list.top.value) >= 0) shouldBe true
            (entry.bottom.value.compareTo(list.bottom.value) <= 0) shouldBe true
        }
    }

    @Test
    fun `switching to the bottom edge reveals the focused tab on the new axis`() {
        setRail()

        composeTestRule.runOnIdle { placement = WorkspaceDesign.RailPlacement.BOTTOM }
        settle()

        withClue(
            "Focused tab " + focusedTitle + " should have been scrolled into the viewport of the " +
                "bottom rail, but " + describeFocusedEntry(),
        ) {
            focusedEntryIsInsideList() shouldBe true
        }
    }

    companion object {
        private const val TAB_COUNT = 10
        private const val ITEM_TAG = WorkspaceNavigationRailDefaults.ITEM_TEST_TAG
        private const val LIST_TAG = WorkspaceNavigationRailDefaults.LIST_TEST_TAG

        private fun title(index: Int) = "Tab" + index
    }
}
