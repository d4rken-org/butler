package eu.darken.butler.workspace.ui.workspaces.adaptive

import android.content.Context
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import eu.darken.butler.R
import eu.darken.butler.common.compose.PreviewWrapper
import io.kotest.matchers.shouldBe
import org.junit.Test
import testhelpers.ComposeTest

/**
 * Detaching a tab is only offered for a pane the layout renders, so the item follows the entry's
 * pane assignment rather than being always present.
 *
 * The menu cannot close itself - `expanded` is hoisted - so every item's dismissal is asserted
 * alongside its action.
 */
class WorkspaceRailItemMenuTest : ComposeTest() {

    private val context = ApplicationProvider.getApplicationContext<Context>()

    private val calls = mutableListOf<String>()

    private fun renderMenu(currentPaneIndex: Int?) {
        composeTestRule.setContent {
            PreviewWrapper {
                WorkspaceRailItemMenu(
                    expanded = true,
                    maxPanes = 2,
                    currentPaneIndex = currentPaneIndex,
                    onDismiss = { calls.add("dismiss") },
                    onAssign = { calls.add("assign") },
                    onUnassign = { calls.add("unassign") },
                    onRename = { calls.add("rename") },
                    onClose = { calls.add("close") },
                )
            }
        }
    }

    private fun assignLabel(paneNumber: Int) = context.getString(
        R.string.workspace_pane_assign_action,
        paneNumber,
    )

    private val closeLabel get() = context.getString(R.string.workspace_pane_close_action)

    @Test
    fun `an entry in a pane can be removed from it`() {
        renderMenu(currentPaneIndex = 1)

        composeTestRule.onNodeWithTag(UNASSIGN_TAG).assertExists()
    }

    @Test
    fun `an entry in no pane cannot be removed from one`() {
        renderMenu(currentPaneIndex = null)

        composeTestRule.onNodeWithTag(UNASSIGN_TAG).assertDoesNotExist()
    }

    @Test
    fun `removing an entry from its pane dismisses the menu first`() {
        renderMenu(currentPaneIndex = 0)

        composeTestRule.onNodeWithTag(UNASSIGN_TAG).performClick()

        calls shouldBe listOf("dismiss", "unassign")
    }

    @Test
    fun `an entry in a pane keeps the assign and close items`() {
        renderMenu(currentPaneIndex = 0)

        composeTestRule.onNodeWithText(assignLabel(1)).assertExists()
        composeTestRule.onNodeWithText(assignLabel(2)).assertExists()
        composeTestRule.onNodeWithText(closeLabel).assertExists()
    }

    @Test
    fun `an entry in no pane keeps the assign and close items`() {
        renderMenu(currentPaneIndex = null)

        composeTestRule.onNodeWithText(assignLabel(1)).assertExists()
        composeTestRule.onNodeWithText(assignLabel(2)).assertExists()
        composeTestRule.onNodeWithText(closeLabel).assertExists()
    }

    companion object {
        private const val UNASSIGN_TAG = WorkspaceNavigationRailDefaults.UNASSIGN_TEST_TAG
    }
}
