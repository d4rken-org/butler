package eu.darken.butler.workspace.ui.workspaces

import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import eu.darken.butler.R
import eu.darken.butler.common.ca.toCaString
import eu.darken.butler.common.compose.PreviewWrapper
import eu.darken.butler.workspace.core.Workspace
import eu.darken.butler.workspace.ui.manager.WorkspaceDesign
import eu.darken.butler.workspace.ui.workspaces.adaptive.WorkspaceNavigationRailDefaults
import eu.darken.butler.workspace.ui.workspaces.adaptive.WorkspaceRailItem
import io.kotest.matchers.shouldBe
import org.junit.Test
import testhelpers.ComposeTest

/**
 * The rail entry carries the pane assignment twice: as a layout glyph for the eye and as `selected`
 * semantics for TalkBack. Both have to survive the switch away from `NavigationRailItem`, which used
 * to supply the selection state for free, and the glyph must disappear together with the assignment.
 *
 * The glyph sits outside the entry's `Surface` - it is drawn in a corner notch cut out of it - so
 * these also guard that the wrapping `Box` still merges into one node, and that the notch never
 * pushes the entry past the height its own content asks for.
 *
 * Heights are asserted as a floor, never as an exact value: the entry's height is its content's,
 * with [ITEM_MIN_HEIGHT] only as a lower bound, and the label's share of that content is measured
 * by Robolectric's stub font rather than the real one (see `ComposeTest`). What the exact-value
 * arithmetic would be testing is the stub, not the layout.
 *
 * Colours carry the rest of the state (outline vs fill), and the notch and the glyph's cells are
 * geometry: neither is asserted here, because Robolectric cannot draw. Previews cover them, and
 * `PaneCellTest` in `:app-workspace` covers the cell arrangement.
 */
class WorkspaceRailItemTest : ComposeTest() {

    private val context = ApplicationProvider.getApplicationContext<Context>()

    private fun workspace(title: String = "Explorer") = Workspace.Info(
        id = Workspace.Id(),
        type = Workspace.Type.EXPLORER,
        title = title.toCaString(),
    )

    private fun paneDescription(paneNumber: Int) = context.getString(
        R.string.workspace_pane_current_description,
        paneNumber,
    )

    private val paneIndexState = mutableStateOf<Int?>(null)
    private val layoutState = mutableStateOf(WorkspaceDesign.Layout.DUAL_VERTICAL)
    private var isRendered = false

    /**
     * `setContent` may only be called once per rule, and one of these tests compares the same entry
     * across two configurations - so the configuration is state the content reads rather than an
     * argument to it, and repeat calls recompose instead of failing.
     *
     * The unconstrained label beside the entry is the yardstick for the line-box test: Robolectric's
     * stub font makes the label's absolute height meaningless, but the two are measured by the same
     * stub in the same composition, so the comparison between them still holds.
     */
    private fun renderItem(
        paneIndex: Int?,
        layout: WorkspaceDesign.Layout = WorkspaceDesign.Layout.DUAL_VERTICAL,
    ) {
        paneIndexState.value = paneIndex
        layoutState.value = layout

        if (isRendered) {
            composeTestRule.waitForIdle()
            return
        }
        isRendered = true

        composeTestRule.setContent {
            PreviewWrapper {
                Column {
                    WorkspaceRailItem(
                        workspace = workspace(),
                        paneIndex = paneIndexState.value,
                        isFocused = false,
                        layout = layoutState.value,
                        onClick = {},
                    )
                    Text(
                        modifier = Modifier.testTag(LABEL_REFERENCE_TAG),
                        text = "Reference",
                        style = MaterialTheme.typography.labelSmall,
                        maxLines = 1,
                    )
                }
            }
        }
    }

    private fun heightOf(interaction: SemanticsNodeInteraction) = interaction
        .getUnclippedBoundsInRoot()
        .let { it.bottom - it.top }

    private fun itemHeight() = heightOf(composeTestRule.onNodeWithTag(ITEM_TAG))

    @Test
    fun `a workspace in a pane shows its pane number`() {
        renderItem(paneIndex = 1)

        composeTestRule.onNodeWithContentDescription(paneDescription(2), useUnmergedTree = true).assertExists()
    }

    @Test
    fun `a workspace without a pane shows no glyph`() {
        renderItem(paneIndex = null)

        composeTestRule.onAllNodesWithContentDescription(paneDescription(1), useUnmergedTree = true)
            .assertCountEquals(0)
        composeTestRule.onAllNodesWithContentDescription(paneDescription(2), useUnmergedTree = true)
            .assertCountEquals(0)
    }

    /**
     * A diagram of a layout that has exactly one pane says nothing the fill and outline don't
     * already say, so the entry keeps its plain shape.
     */
    @Test
    fun `a single pane layout shows no glyph`() {
        renderItem(paneIndex = 0, layout = WorkspaceDesign.Layout.SINGLE)

        composeTestRule.onAllNodesWithContentDescription(paneDescription(1), useUnmergedTree = true)
            .assertCountEquals(0)
    }

    /**
     * The glyph is a sibling of the clickable `Surface`, not a child, so the pane it depicts has to
     * be announced by the Surface for the entry to stay one node. Moving the selection state or the
     * pane description onto the wrapping `Box` instead splits the entry in two for TalkBack: a
     * merging node cannot absorb `Surface(onClick)`, which merges in its own right - so the click
     * action would no longer share a node with the tag.
     */
    @Test
    fun `the entry is a single node`() {
        renderItem(paneIndex = 0)

        composeTestRule.onNodeWithTag(ITEM_TAG).assertHasClickAction()
        composeTestRule.onNodeWithTag(ITEM_TAG).assertIsSelected()
    }

    @Test
    fun `the pane assignment is the selection state`() {
        renderItem(paneIndex = 0)

        composeTestRule.onNodeWithTag(ITEM_TAG).assertIsSelected()
    }

    @Test
    fun `a workspace outside every pane is not selected`() {
        renderItem(paneIndex = null)

        composeTestRule.onNodeWithTag(ITEM_TAG).assertIsNotSelected()
    }

    @Test
    fun `the item is a tab`() {
        renderItem(paneIndex = 0)

        composeTestRule.onNodeWithTag(ITEM_TAG)
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab))
    }

    @Test
    fun `the item is at least the rail item height`() {
        renderItem(paneIndex = null)

        composeTestRule.onNodeWithTag(ITEM_TAG).assertHeightIsAtLeast(ITEM_MIN_HEIGHT)
    }

    /**
     * The notch is carved out of the entry's width, so an assigned entry - which also drops to the
     * smaller icon - must never end up taller than an unassigned one.
     */
    @Test
    fun `the glyph does not grow the item`() {
        renderItem(paneIndex = null)
        val unassigned = itemHeight()

        composeTestRule.onNodeWithTag(ITEM_TAG).assertHeightIsAtLeast(ITEM_MIN_HEIGHT)

        renderItem(paneIndex = 1, layout = WorkspaceDesign.Layout.QUAD_GRID)

        composeTestRule.onNodeWithTag(ITEM_TAG).assertHeightIsAtLeast(ITEM_MIN_HEIGHT)
        (itemHeight() <= unassigned) shouldBe true
    }

    /**
     * The entry used to be a fixed 56dp, which is less than the larger of its two icons plus a full
     * label line: the label was measured last, got only the height the icon left over, and lost the
     * bottom of its line box. Asserting the entry's height in dp cannot catch that - the clipping
     * happens inside a box that stays exactly the size it was told to be - so this compares the
     * label against the same label with nothing constraining it.
     *
     * The unassigned entry is the case that has to hold: it carries the bigger icon, so it is the
     * one that runs out of room first.
     *
     * Font scale is the other half of the regression - the line box is sp, the floor is dp - but it
     * cannot be tested here: Robolectric's stub font reports one fixed height whatever the scale.
     */
    @Test
    fun `the entry gives the label its whole line box`() {
        renderItem(paneIndex = null)

        val label = heightOf(composeTestRule.onNodeWithText("Explorer", useUnmergedTree = true))
        val unconstrained = heightOf(composeTestRule.onNodeWithTag(LABEL_REFERENCE_TAG, useUnmergedTree = true))

        label shouldBe unconstrained
    }

    @Test
    fun `the item is labelled with the workspace title`() {
        renderItem(paneIndex = null)

        composeTestRule.onNodeWithText("Explorer", useUnmergedTree = true).assertIsDisplayed()
    }

    companion object {
        private const val ITEM_TAG = WorkspaceNavigationRailDefaults.ITEM_TEST_TAG

        private const val LABEL_REFERENCE_TAG = "workspace.rail.item.label.reference"

        private val ITEM_MIN_HEIGHT = 56.dp
    }
}
