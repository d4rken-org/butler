package eu.darken.butler.workspace.ui.workspaces.adaptive

import android.content.Context
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.test.core.app.ApplicationProvider
import eu.darken.butler.R
import eu.darken.butler.common.ca.toCaString
import eu.darken.butler.common.compose.PreviewWrapper
import eu.darken.butler.workspace.core.Workspace
import eu.darken.butler.workspace.core.WorkspaceAction
import eu.darken.butler.workspace.ui.manager.WorkspaceDesign
import eu.darken.butler.workspace.ui.workspaces.WorkspacePaneInfo
import eu.darken.butler.workspace.ui.workspaces.asPaneInfo
import io.kotest.matchers.shouldBe
import org.junit.Test
import org.robolectric.annotation.Config
import testhelpers.ComposeTest

/**
 * What a tap and a long press do on an entry, which depends on how many panes the layout has: with
 * several, a tap opens the menu and a long press on the icon starts a reorder; with one, a tap shows
 * the tab, a long press opens the menu, and nothing reorders.
 */
// Same window as WorkspaceRailReorderTest, and for the same reason: at Robolectric's 320dp default
// the reorderable library's edge-scroll bands cover the whole list.
@Config(qualifiers = "w411dp-h891dp")
class WorkspaceRailGestureTest : ComposeTest() {

    private val context = ApplicationProvider.getApplicationContext<Context>()

    private fun tab(title: String) = Workspace.Info(
        id = Workspace.Id(),
        type = Workspace.Type.EXPLORER,
        title = title.toCaString(),
        lifecycleState = Workspace.LifecycleState.Ready,
    )

    private val tabs = listOf(tab("One"), tab("Two"), tab("Three"))

    private val tabActions = mutableListOf<WorkspaceAction>()
    private val assignments = mutableListOf<Pair<Workspace.Id, Int>>()

    private val multiPane = WorkspaceDesign(
        layout = WorkspaceDesign.Layout.DUAL_HORIZONTAL,
        railPlacement = WorkspaceDesign.RailPlacement.BOTTOM,
    )
    private val singlePane = WorkspaceDesign(
        layout = WorkspaceDesign.Layout.SINGLE,
        hasNavigationRail = true,
        railPlacement = WorkspaceDesign.RailPlacement.BOTTOM,
    )
    private val designState = mutableStateOf(multiPane)

    private val dragDescription: String
        get() = context.getString(R.string.workspace_dragging_description)

    private val showAction: String
        get() = context.getString(R.string.workspace_pane_show_action)

    private fun setRail(
        design: WorkspaceDesign,
        selected: Map<Int, WorkspacePaneInfo> = emptyMap(),
    ) {
        designState.value = design
        // The Butler button's mascot animates on an endless loop, which never lets the clock idle.
        composeTestRule.mainClock.autoAdvance = false
        composeTestRule.setContent {
            PreviewWrapper {
                Box(modifier = Modifier.fillMaxSize()) {
                    WorkspaceNavigationRail(
                        modifier = Modifier.align(Alignment.BottomCenter),
                        workspaces = tabs,
                        selected = selected,
                        focusedId = null,
                        design = designState.value,
                        onTabAction = { tabActions += it },
                        onPaneAssignment = { id, pane -> assignments += id to pane },
                        onPaneUnassign = {},
                    )
                }
            }
        }
        composeTestRule.waitForIdle()
        // Lets the LazyRow report its orientation before a gesture starts; see WorkspaceRailReorderTest.
        advanceFrames()
    }

    private fun advanceFrames(count: Int = 5) {
        repeat(count) {
            composeTestRule.mainClock.advanceTimeByFrame()
            composeTestRule.waitForIdle()
        }
    }

    /** Long-presses the first entry on its type icon, which is where the reorder handle sits. */
    private fun longPressFirstIcon() {
        var longPressMs = 0L
        composeTestRule.onAllNodesWithTag(ITEM_TAG)[0].performTouchInput {
            longPressMs = viewConfiguration.longPressTimeoutMillis
            down(Offset(width / 2f, height * 0.25f))
        }
        // Both detectors time the long press on the composition clock, which is parked.
        composeTestRule.mainClock.advanceTimeBy(longPressMs + 100)
        advanceFrames()
    }

    private fun dragAlongList() {
        val entries = composeTestRule.onAllNodesWithTag(ITEM_TAG)
        val pitch = entries[1].getUnclippedBoundsInRoot().left - entries[0].getUnclippedBoundsInRoot().left
        val pitchPx = with(composeTestRule.density) { pitch.toPx() }
        repeat(2) {
            composeTestRule.onNodeWithTag(LIST_TAG).performTouchInput {
                moveBy(Offset(pitchPx * 0.55f, 0f))
                advanceEventTime(16)
            }
            advanceFrames()
        }
    }

    @Test
    fun `a tap opens the menu when there are several panes`() {
        setRail(multiPane)

        composeTestRule.onAllNodesWithTag(ITEM_TAG)[1].performClick()
        advanceFrames()

        composeTestRule.onNodeWithText(context.getString(R.string.workspace_pane_assign_action, 1))
            .assertExists()
        assignments shouldBe emptyList()
    }

    @Test
    fun `a tap shows the entry when there is one pane`() {
        setRail(singlePane)

        composeTestRule.onAllNodesWithTag(ITEM_TAG)[1].performClick()
        advanceFrames()

        assignments shouldBe listOf(tabs[1].id to 0)
        composeTestRule.onNodeWithText(showAction).assertDoesNotExist()
    }

    @Test
    fun `a tap on the entry already in the only pane changes nothing`() {
        setRail(singlePane, selected = mapOf(0 to tabs[0].asPaneInfo()))

        composeTestRule.onAllNodesWithTag(ITEM_TAG)[0].performClick()
        advanceFrames()

        assignments shouldBe emptyList()
        composeTestRule.onNodeWithText(showAction).assertDoesNotExist()
    }

    @Test
    fun `a long press on the icon opens the menu when there is one pane`() {
        setRail(singlePane)

        longPressFirstIcon()
        // Before the finger lifts: a menu that only appears on release would be a click.
        composeTestRule.onNodeWithText(showAction).assertExists()
        composeTestRule.onAllNodesWithContentDescription(dragDescription).assertCountEquals(0)
        composeTestRule.onNodeWithTag(LIST_TAG).performTouchInput { up() }
        advanceFrames()

        assignments shouldBe emptyList()
    }

    @Test
    fun `nothing reorders when there is one pane`() {
        setRail(singlePane)

        longPressFirstIcon()
        composeTestRule.onAllNodesWithContentDescription(dragDescription).assertCountEquals(0)
        dragAlongList()
        composeTestRule.onNodeWithTag(LIST_TAG).performTouchInput { up() }
        advanceFrames()

        tabActions.filterIsInstance<WorkspaceAction.Reorder>() shouldBe emptyList()
    }

    /**
     * A window that shrinks to one pane mid-drag disables the handle under the finger. The drag has
     * to end there, committing the order it reached, rather than leave the rail holding a local order
     * it never publishes again.
     */
    @Test
    fun `a drag ends when the layout drops to one pane`() {
        setRail(multiPane)

        longPressFirstIcon()
        composeTestRule.onAllNodesWithContentDescription(dragDescription).assertCountEquals(1)
        dragAlongList()

        designState.value = singlePane
        advanceFrames()

        composeTestRule.onAllNodesWithContentDescription(dragDescription).assertCountEquals(0)
        val reorders = tabActions.filterIsInstance<WorkspaceAction.Reorder>()
        reorders.size shouldBe 1
        reorders.single().ownerIds shouldBe listOf(tabs[1].id, tabs[0].id, tabs[2].id)

        composeTestRule.onNodeWithTag(LIST_TAG).performTouchInput { up() }
        advanceFrames()
        tabActions.filterIsInstance<WorkspaceAction.Reorder>().size shouldBe 1
    }

    companion object {
        private const val ITEM_TAG = WorkspaceNavigationRailDefaults.ITEM_TEST_TAG
        private const val LIST_TAG = WorkspaceNavigationRailDefaults.LIST_TEST_TAG
    }
}
