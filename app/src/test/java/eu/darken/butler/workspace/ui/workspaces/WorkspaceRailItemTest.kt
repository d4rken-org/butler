package eu.darken.butler.workspace.ui.workspaces

import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import eu.darken.butler.common.ca.toCaString
import eu.darken.butler.common.compose.PreviewWrapper
import eu.darken.butler.workspace.core.Workspace
import eu.darken.butler.workspace.ui.workspaces.adaptive.WorkspaceRailItem
import org.junit.Test
import testhelpers.ComposeTest

/**
 * The rail entry carries the pane assignment twice: as a badge for the eye and as `selected`
 * semantics for TalkBack. Both have to survive the switch away from `NavigationRailItem`, which used
 * to supply the selection state for free, and the badge must disappear together with the assignment.
 *
 * The item also owns its height now, so the rail's rhythm depends on it staying fixed instead of
 * growing with the label.
 *
 * Colours carry the rest of the state (outline vs fill) and are deliberately not asserted here:
 * Robolectric cannot draw.
 */
class WorkspaceRailItemTest : ComposeTest() {

    private fun workspace(title: String = "Explorer") = Workspace.Info(
        id = Workspace.Id(),
        type = Workspace.Type.EXPLORER,
        title = title.toCaString(),
    )

    private fun renderItem(paneIndex: Int?) {
        composeTestRule.setContent {
            PreviewWrapper {
                WorkspaceRailItem(
                    modifier = Modifier.testTag(ITEM_TAG),
                    workspace = workspace(),
                    paneIndex = paneIndex,
                    isFocused = false,
                    onClick = {},
                )
            }
        }
    }

    @Test
    fun `a workspace in a pane shows its pane number`() {
        renderItem(paneIndex = 1)

        composeTestRule.onNodeWithText("2", useUnmergedTree = true).assertExists()
    }

    @Test
    fun `a workspace without a pane shows no badge`() {
        renderItem(paneIndex = null)

        composeTestRule.onAllNodesWithText("1", useUnmergedTree = true).assertCountEquals(0)
        composeTestRule.onAllNodesWithText("2", useUnmergedTree = true).assertCountEquals(0)
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
    fun `the item keeps a fixed height`() {
        renderItem(paneIndex = null)

        composeTestRule.onNodeWithTag(ITEM_TAG).assertHeightIsEqualTo(ITEM_HEIGHT)
    }

    @Test
    fun `the item is labelled with the workspace title`() {
        renderItem(paneIndex = null)

        composeTestRule.onNodeWithText("Explorer", useUnmergedTree = true).assertIsDisplayed()
    }

    companion object {
        private const val ITEM_TAG = "rail.item"

        private val ITEM_HEIGHT = 56.dp
    }
}
