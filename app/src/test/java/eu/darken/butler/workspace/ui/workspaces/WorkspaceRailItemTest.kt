package eu.darken.butler.workspace.ui.workspaces

import android.content.Context
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
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
import org.junit.Test
import testhelpers.ComposeTest

/**
 * The rail entry carries the pane assignment twice: as a layout glyph for the eye and as `selected`
 * semantics for TalkBack. Both have to survive the switch away from `NavigationRailItem`, which used
 * to supply the selection state for free, and the glyph must disappear together with the assignment.
 *
 * The glyph sits outside the entry's `Surface` - it is drawn in a corner notch cut out of it - so
 * these also guard that the wrapping `Box` still merges into one node and still owns the height.
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

    private fun renderItem(
        paneIndex: Int?,
        layout: WorkspaceDesign.Layout = WorkspaceDesign.Layout.DUAL_VERTICAL,
    ) {
        composeTestRule.setContent {
            PreviewWrapper {
                WorkspaceRailItem(
                    workspace = workspace(),
                    paneIndex = paneIndex,
                    isFocused = false,
                    layout = layout,
                    onClick = {},
                )
            }
        }
    }

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
    fun `the item keeps a fixed height`() {
        renderItem(paneIndex = null)

        composeTestRule.onNodeWithTag(ITEM_TAG).assertHeightIsEqualTo(ITEM_HEIGHT)
    }

    @Test
    fun `the glyph does not grow the item`() {
        renderItem(paneIndex = 1, layout = WorkspaceDesign.Layout.QUAD_GRID)

        composeTestRule.onNodeWithTag(ITEM_TAG).assertHeightIsEqualTo(ITEM_HEIGHT)
    }

    @Test
    fun `the item is labelled with the workspace title`() {
        renderItem(paneIndex = null)

        composeTestRule.onNodeWithText("Explorer", useUnmergedTree = true).assertIsDisplayed()
    }

    companion object {
        private const val ITEM_TAG = WorkspaceNavigationRailDefaults.ITEM_TEST_TAG

        private val ITEM_HEIGHT = 56.dp
    }
}
