package eu.darken.butler.viewer.ui.viewer

import android.content.Context
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onAllNodesWithText
import androidx.test.core.app.ApplicationProvider
import eu.darken.butler.common.compose.PreviewWrapper
import eu.darken.butler.viewer.R
import eu.darken.butler.workspace.core.Workspace
import eu.darken.butler.workspace.ui.manager.WorkspaceDesign
import org.junit.Test
import testhelpers.ComposeTest

class ViewerToolbarCardTest : ComposeTest() {

    private val context = ApplicationProvider.getApplicationContext<Context>()

    private val fileName = "IMG_20240817_183042.jpg"
    private val fullPath = "/storage/emulated/0/DCIM/Camera/$fileName"

    private fun toolbarWith(isCollapsed: Boolean) {
        composeTestRule.setContent {
            PreviewWrapper {
                ViewerToolbarCard(
                    workspaceId = Workspace.Id(),
                    // Split-pane layout: keeps the mascot-bearing workspace button, which
                    // Robolectric cannot rasterise, out of the toolbar cutout.
                    design = WorkspaceDesign(layout = WorkspaceDesign.Layout.DUAL_VERTICAL),
                    fileName = fileName,
                    fullPath = fullPath,
                    isCollapsed = isCollapsed,
                )
            }
        }
    }

    // CutoutCard subcomposes its content twice (measure pass + render pass), so single-node
    // matchers can report multiple matches - index into all matching nodes instead.

    @Test
    fun `the expanded toolbar shows the full path`() {
        toolbarWith(isCollapsed = false)

        composeTestRule
            .onAllNodesWithText(context.getString(R.string.viewer_toolbar_path_label))[0]
            .assertIsDisplayed()
        composeTestRule.onAllNodesWithText(fullPath)[0].assertIsDisplayed()
    }

    @Test
    fun `the collapsed toolbar keeps the name and drops the path`() {
        toolbarWith(isCollapsed = true)

        composeTestRule.onAllNodesWithText(fileName)[0].assertIsDisplayed()
        composeTestRule
            .onAllNodesWithText(context.getString(R.string.viewer_toolbar_path_label))
            .assertCountEquals(0)
    }
}
