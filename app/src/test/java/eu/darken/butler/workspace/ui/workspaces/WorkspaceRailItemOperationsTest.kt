package eu.darken.butler.workspace.ui.workspaces

import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import eu.darken.butler.R
import eu.darken.butler.common.ca.toCaString
import eu.darken.butler.common.compose.PreviewWrapper
import eu.darken.butler.workspace.core.Workspace
import eu.darken.butler.workspace.ui.manager.WorkspaceDesign
import eu.darken.butler.workspace.ui.workspaces.adaptive.WorkspaceNavigationRailDefaults
import eu.darken.butler.workspace.ui.workspaces.adaptive.WorkspaceRailItem
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import org.junit.Test
import testhelpers.ComposeTest

/**
 * The rail entry marks the work its whole tab is doing: a spinner while something runs, a glyph
 * while work is only queued or waiting on an answer.
 *
 * The markers sit outside the entry's `Surface`, in the corner opposite the pane notch, so these
 * also guard the two things that placement can break - the marker leaving the entry's bounds, in
 * the tightest state and again mirrored under RTL, and the entry ceasing to be one TalkBack node.
 */
class WorkspaceRailItemOperationsTest : ComposeTest() {

    private val context = ApplicationProvider.getApplicationContext<Context>()

    private fun paneDescription(paneNumber: Int) = context.getString(
        R.string.workspace_pane_current_description,
        paneNumber,
    )

    /**
     * The entry in the width the start placement actually leaves it: the rail is 80dp thick and
     * insets its content by 8dp on each side.
     */
    private fun renderItem(
        operationCount: Int,
        activeCount: Int,
        paneIndex: Int? = null,
        layoutDirection: LayoutDirection = LayoutDirection.Ltr,
    ) {
        composeTestRule.setContent {
            CompositionLocalProvider(LocalLayoutDirection provides layoutDirection) {
                PreviewWrapper {
                    Column(
                        modifier = Modifier
                            .width(RAIL_THICKNESS)
                            .padding(horizontal = RAIL_ITEM_INSET),
                    ) {
                        WorkspaceRailItem(
                            workspace = Workspace.Info(
                                id = Workspace.Id(),
                                type = Workspace.Type.EXPLORER,
                                title = "Explorer".toCaString(),
                            ),
                            paneIndex = paneIndex,
                            isFocused = false,
                            operationCount = operationCount,
                            activeCount = activeCount,
                            layout = WorkspaceDesign.Layout.DUAL_VERTICAL,
                            onClick = {},
                        )
                    }
                }
            }
        }
    }

    /**
     * The same entry, but with the pane assignment in a state the test can change - the notch and
     * everything keyed on it only animate when the value flips while the entry stays composed.
     */
    private fun renderReassignableItem(paneIndex: Int?): MutableState<Int?> {
        val state = mutableStateOf(paneIndex)
        composeTestRule.setContent {
            PreviewWrapper {
                Column(
                    modifier = Modifier
                        .width(RAIL_THICKNESS)
                        .padding(horizontal = RAIL_ITEM_INSET),
                ) {
                    WorkspaceRailItem(
                        workspace = Workspace.Info(
                            id = Workspace.Id(),
                            type = Workspace.Type.EXPLORER,
                            title = "Explorer".toCaString(),
                        ),
                        paneIndex = state.value,
                        isFocused = false,
                        operationCount = 1,
                        activeCount = 1,
                        layout = WorkspaceDesign.Layout.DUAL_VERTICAL,
                        onClick = {},
                    )
                }
            }
        }
        return state
    }

    private fun descriptionOf(interaction: SemanticsNodeInteraction) = interaction
        .fetchSemanticsNode()
        .config
        .getOrNull(SemanticsProperties.ContentDescription)
        .orEmpty()
        .joinToString(", ")

    @Test
    fun `a tab with work running shows the spinner and not the pending glyph`() {
        renderItem(operationCount = 2, activeCount = 1)

        composeTestRule.onAllNodesWithTag(RUNNING_TAG, useUnmergedTree = true).assertCountEquals(1)
        composeTestRule.onAllNodesWithTag(PENDING_TAG, useUnmergedTree = true).assertCountEquals(0)
    }

    @Test
    fun `a tab whose work has not started shows the pending glyph`() {
        renderItem(operationCount = 1, activeCount = 0)

        composeTestRule.onAllNodesWithTag(PENDING_TAG, useUnmergedTree = true).assertCountEquals(1)
        composeTestRule.onAllNodesWithTag(RUNNING_TAG, useUnmergedTree = true).assertCountEquals(0)
    }

    @Test
    fun `an idle tab carries no operation marker`() {
        renderItem(operationCount = 0, activeCount = 0)

        composeTestRule.onAllNodesWithTag(RUNNING_TAG, useUnmergedTree = true).assertCountEquals(0)
        composeTestRule.onAllNodesWithTag(PENDING_TAG, useUnmergedTree = true).assertCountEquals(0)
    }

    /** The notch is the tightest state: the type icon shrinks and slides towards the marker. */
    @Test
    fun `the marker stays inside a notched entry`() {
        renderItem(operationCount = 1, activeCount = 1, paneIndex = 1)

        val entry = composeTestRule.onNodeWithTag(ITEM_TAG).getUnclippedBoundsInRoot()
        val marker = composeTestRule.onNodeWithTag(RUNNING_TAG, useUnmergedTree = true).getUnclippedBoundsInRoot()

        (marker.left >= entry.left) shouldBe true
        (marker.right <= entry.right) shouldBe true
        (marker.top >= entry.top) shouldBe true
        (marker.bottom <= entry.bottom) shouldBe true
    }

    /** The notch mirrors under RTL, so the marker has to swap sides with it. */
    @Test
    fun `the marker stays inside a notched entry under RTL`() {
        renderItem(
            operationCount = 1,
            activeCount = 1,
            paneIndex = 1,
            layoutDirection = LayoutDirection.Rtl,
        )

        val entry = composeTestRule.onNodeWithTag(ITEM_TAG).getUnclippedBoundsInRoot()
        val marker = composeTestRule.onNodeWithTag(RUNNING_TAG, useUnmergedTree = true).getUnclippedBoundsInRoot()

        (marker.left >= entry.left) shouldBe true
        (marker.right <= entry.right) shouldBe true
        (marker.top >= entry.top) shouldBe true
        (marker.bottom <= entry.bottom) shouldBe true
    }

    /**
     * Without a notch nothing shifts towards the marker, so it takes its larger size - and still has
     * to sit inside the entry and clear of the type icon, which is centred at its full 24dp.
     */
    @Test
    fun `the marker stays inside an unnotched entry and clear of the type icon`() {
        renderItem(operationCount = 1, activeCount = 1, paneIndex = null)

        val entry = composeTestRule.onNodeWithTag(ITEM_TAG).getUnclippedBoundsInRoot()
        val marker = composeTestRule.onNodeWithTag(RUNNING_TAG, useUnmergedTree = true).getUnclippedBoundsInRoot()

        (marker.left >= entry.left) shouldBe true
        (marker.right <= entry.right) shouldBe true
        (marker.top >= entry.top) shouldBe true
        (marker.bottom <= entry.bottom) shouldBe true

        val icon = composeTestRule.onNodeWithTag(ICON_TAG, useUnmergedTree = true).getUnclippedBoundsInRoot()
        (marker.right <= icon.left) shouldBe true
    }

    /**
     * Losing a pane animates the type icon back out to its centred full size, so the marker has to
     * travel with it: sized for the unnotched entry the instant the assignment drops, it would grow
     * into the space the icon has not vacated yet and overlap it for the length of the transition.
     */
    @Test
    fun `the marker stays clear of the type icon while the notch closes`() {
        composeTestRule.mainClock.autoAdvance = false
        val paneIndex = renderReassignableItem(paneIndex = 1)
        composeTestRule.mainClock.advanceTimeBy(2_000)

        paneIndex.value = null

        repeat(FRAMES) {
            composeTestRule.mainClock.advanceTimeByFrame()

            val marker = composeTestRule.onNodeWithTag(RUNNING_TAG, useUnmergedTree = true).getUnclippedBoundsInRoot()
            val icon = composeTestRule.onNodeWithTag(ICON_TAG, useUnmergedTree = true).getUnclippedBoundsInRoot()

            withClue("frame $it: marker ${marker.left}..${marker.right}, icon ${icon.left}..${icon.right}") {
                (marker.right <= icon.left) shouldBe true
            }
        }
    }

    /**
     * An indeterminate indicator publishes progress semantics of its own, and the marker is a
     * sibling of the clickable card rather than a child of it - so without clearing them the entry
     * would announce twice.
     */
    @Test
    fun `the running entry exposes no progress node`() {
        renderItem(operationCount = 1, activeCount = 1, paneIndex = 1)

        composeTestRule
            .onAllNodes(
                SemanticsMatcher.keyIsDefined(SemanticsProperties.ProgressBarRangeInfo),
                useUnmergedTree = true,
            )
            .assertCountEquals(0)
    }

    @Test
    fun `the running entry announces its title, its pane and its work`() {
        renderItem(operationCount = 1, activeCount = 1, paneIndex = 1)

        composeTestRule.onNodeWithText("Explorer", useUnmergedTree = true).assertExists()

        val description = descriptionOf(composeTestRule.onNodeWithTag(ITEM_TAG))
        description.contains(paneDescription(2)) shouldBe true
        description.contains(context.getString(R.string.workspace_row_operations_running_content_desc)) shouldBe true
    }

    @Test
    fun `the pending entry announces its work`() {
        renderItem(operationCount = 1, activeCount = 0)

        val description = descriptionOf(composeTestRule.onNodeWithTag(ITEM_TAG))
        description.contains(context.getString(R.string.workspace_row_operations_pending_content_desc)) shouldBe true
    }

    companion object {
        private const val ITEM_TAG = WorkspaceNavigationRailDefaults.ITEM_TEST_TAG
        private const val RUNNING_TAG = WorkspaceNavigationRailDefaults.OPS_RUNNING_TEST_TAG
        private const val PENDING_TAG = WorkspaceNavigationRailDefaults.OPS_PENDING_TEST_TAG

        private const val ICON_TAG = WorkspaceNavigationRailDefaults.TYPE_ICON_TEST_TAG

        private val RAIL_THICKNESS = 80.dp
        private val RAIL_ITEM_INSET = 8.dp

        /** Enough frames at 60fps to cover the notch animation from start to rest. */
        private const val FRAMES = 60
    }
}
