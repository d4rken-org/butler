package eu.darken.butler.workspace.ui.workspaces.adaptive

import android.content.Context
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.test.core.app.ApplicationProvider
import eu.darken.butler.R
import eu.darken.butler.common.ca.toCaString
import eu.darken.butler.common.compose.PreviewWrapper
import eu.darken.butler.workspace.core.Workspace
import eu.darken.butler.workspace.core.WorkspaceAction
import eu.darken.butler.workspace.ui.manager.WorkspaceDesign
import io.kotest.matchers.shouldBe
import org.junit.Test
import org.robolectric.annotation.Config
import testhelpers.ComposeTest

/**
 * Reordering has to keep working when the list runs along the other axis: the rail hands the generic
 * reorderable state a `LazyRow` instead of a `LazyColumn` and relies on it taking the orientation
 * from the list's own layout info, so nothing here is axis-specific except the direction of the drag.
 */
// A portrait phone rather than Robolectric's 320dp default, and load-bearing: the list is what is
// left of the window after the Butler button and the FAB, and the reorderable library turns a drag
// within 48dp of either end of it into an edge scroll instead of a swap. At 320dp those two bands
// leave no room between them.
@Config(qualifiers = "w411dp-h891dp")
class WorkspaceRailReorderTest : ComposeTest() {

    private fun tab(title: String) = Workspace.Info(
        id = Workspace.Id(),
        type = Workspace.Type.EXPLORER,
        title = title.toCaString(),
        lifecycleState = Workspace.LifecycleState.Ready,
    )

    private val tabs = listOf(tab("One"), tab("Two"), tab("Three"))

    private val actions = mutableListOf<WorkspaceAction>()

    private val dragDescription: String
        get() = ApplicationProvider.getApplicationContext<Context>()
            .getString(R.string.workspace_dragging_description)

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
        // The reorderable state is remembered on the list's orientation, and a LazyRow reports
        // Vertical until its first layout has been observed. Settling that here matters: the
        // recomposition it causes rebuilds the state object, and a state rebuilt under the finger
        // restarts the drag handle's gesture detector and swallows the long press.
        advanceFrames()
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

        var longPressMs = 0L
        composeTestRule.onAllNodesWithTag(ITEM_TAG)[0].performTouchInput {
            longPressMs = viewConfiguration.longPressTimeoutMillis
            // The drag handle is the entry's icon, which sits above the label.
            down(Offset(width / 2f, height * 0.25f))
        }
        // The handle waits out the long press on the composition clock, not on the timestamps the
        // events carry, so only advancing the clock here lets the press mature into a drag.
        composeTestRule.mainClock.advanceTimeBy(longPressMs + 100)
        advanceFrames()
        // The entry swaps its type icon for the drag handle once the lift is under way, so this
        // fails loudly if the gesture below ends up dragging nothing.
        composeTestRule.onAllNodesWithContentDescription(dragDescription).assertCountEquals(1)

        // Addressed on the list, not on the entry: the entry that started the gesture changes index
        // as the reorder takes effect, while the pointer stays with the handler the down hit.
        val list = composeTestRule.onNodeWithTag(LIST_TAG)
        // Past the second entry's centre but well short of the third's, so the entry ends up in the
        // middle rather than at the end.
        repeat(2) {
            list.performTouchInput {
                moveBy(Offset(pitchPx * 0.55f, 0f))
                advanceEventTime(16)
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
