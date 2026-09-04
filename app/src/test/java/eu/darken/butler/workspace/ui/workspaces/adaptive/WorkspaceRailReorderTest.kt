package eu.darken.butler.workspace.ui.workspaces.adaptive

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import eu.darken.butler.common.ca.toCaString
import eu.darken.butler.common.compose.PreviewWrapper
import eu.darken.butler.workspace.core.Workspace
import eu.darken.butler.workspace.core.WorkspaceAction
import eu.darken.butler.workspace.ui.manager.WorkspaceDesign
import io.kotest.matchers.shouldBe
import org.junit.Test
import testhelpers.ComposeTest

/**
 * Reordering has to keep working when the list runs along the other axis: the rail hands the generic
 * reorderable state a `LazyRow` instead of a `LazyColumn` and relies on it taking the orientation
 * from the list's own layout info, so nothing here is axis-specific except the direction of the drag.
 */
class WorkspaceRailReorderTest : ComposeTest() {

    private fun tab(title: String) = Workspace.Info(
        id = Workspace.Id(),
        type = Workspace.Type.EXPLORER,
        title = title.toCaString(),
        lifecycleState = Workspace.LifecycleState.Ready,
    )

    private val tabs = listOf(tab("One"), tab("Two"), tab("Three"))

    private val actions = mutableListOf<WorkspaceAction>()

    private fun setRail() {
        // The Butler button's mascot animates on an endless loop, which never lets the clock idle.
        // The gesture below advances the clock itself, so it does not need the automatic advance.
        composeTestRule.mainClock.autoAdvance = false
        composeTestRule.setContent {
            PreviewWrapper {
                Box(modifier = Modifier.fillMaxSize()) {
                    WorkspaceNavigationRail(
                        modifier = Modifier.align(Alignment.BottomCenter),
                        workspaces = tabs,
                        selected = emptyMap(),
                        focusedId = null,
                        design = WorkspaceDesign(
                            layout = WorkspaceDesign.Layout.DUAL_HORIZONTAL,
                            railPlacement = WorkspaceDesign.RailPlacement.BOTTOM,
                        ),
                        onTabAction = { actions += it },
                        onPaneAssignment = { _, _ -> },
                        onPaneUnassign = {},
                    )
                }
            }
        }
        composeTestRule.waitForIdle()
    }

    private fun advanceFrames(count: Int = 5) {
        repeat(count) {
            composeTestRule.mainClock.advanceTimeByFrame()
            composeTestRule.waitForIdle()
        }
    }

    @Test
    fun `dragging an entry sideways reorders the tabs`() {
        setRail()

        val entries = composeTestRule.onAllNodesWithTag(ITEM_TAG)
        entries.assertCountEquals(3)
        val first = entries[0].getUnclippedBoundsInRoot()
        // Measured rather than assumed: what an entry has to travel to change places with the next
        // one is the distance between two entries, spacing included.
        val pitch = entries[1].getUnclippedBoundsInRoot().left - first.left
        val pitchPx = with(composeTestRule.density) { pitch.toPx() }

        composeTestRule.onAllNodesWithTag(ITEM_TAG)[0].performTouchInput {
            // The drag handle is the entry's icon, which sits above the label.
            down(Offset(width / 2f, height * 0.25f))
            advanceEventTime(viewConfiguration.longPressTimeoutMillis + 100)
        }
        advanceFrames()

        // Past the second entry's centre but well short of the third's, so the entry ends up in the
        // middle rather than at the end.
        val list = composeTestRule.onNodeWithTag(LIST_TAG)
        repeat(2) {
            list.performTouchInput {
                moveBy(Offset(pitchPx * 0.55f, 0f))
                advanceEventTime(50)
            }
            advanceFrames()
        }

        list.performTouchInput { up() }
        advanceFrames()

        val reorders = actions.filterIsInstance<WorkspaceAction.Reorder>()
        reorders.isNotEmpty() shouldBe true
        reorders.last().ownerIds shouldBe listOf(tabs[1].id, tabs[0].id, tabs[2].id)
    }

    companion object {
        private const val ITEM_TAG = WorkspaceNavigationRailDefaults.ITEM_TEST_TAG
        private const val LIST_TAG = WorkspaceNavigationRailDefaults.LIST_TEST_TAG
    }
}
