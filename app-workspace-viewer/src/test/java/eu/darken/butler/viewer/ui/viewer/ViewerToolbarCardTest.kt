package eu.darken.butler.viewer.ui.viewer

import android.content.Context
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import eu.darken.butler.common.compose.PreviewWrapper
import eu.darken.butler.viewer.R
import eu.darken.butler.workspace.core.Workspace
import eu.darken.butler.workspace.ui.manager.WorkspaceDesign
import io.kotest.matchers.shouldBe
import org.junit.Test
import testhelpers.ComposeTest

class ViewerToolbarCardTest : ComposeTest() {

    private val context = ApplicationProvider.getApplicationContext<Context>()

    private val fileName = "IMG_20240817_183042.jpg"
    private val folderPath = "/storage/emulated/0/DCIM/Camera"

    private fun toolbarWith(isCollapsed: Boolean, position: Pair<Int, Int>? = null) {
        composeTestRule.setContent {
            PreviewWrapper {
                ViewerToolbarCard(
                    workspaceId = Workspace.Id(),
                    // Split-pane layout: keeps the mascot-bearing workspace button, which
                    // Robolectric cannot rasterise, out of the toolbar cutout.
                    design = WorkspaceDesign(layout = WorkspaceDesign.Layout.DUAL_VERTICAL),
                    fileName = fileName,
                    folderPath = folderPath,
                    isCollapsed = isCollapsed,
                    position = position,
                )
            }
        }
    }

    // CutoutCard subcomposes its content twice (measure pass + render pass), so single-node
    // matchers can report multiple matches - index into all matching nodes instead.

    @Test
    fun `the expanded toolbar shows the containing folder, not the file name again`() {
        toolbarWith(isCollapsed = false)

        composeTestRule
            .onAllNodesWithText(context.getString(R.string.viewer_toolbar_folder_label))[0]
            .assertIsDisplayed()
        composeTestRule.onAllNodesWithText(folderPath)[0].assertIsDisplayed()
        // The title above it already carries the name; repeating it as the path's tail said nothing.
        composeTestRule.onAllNodesWithText("$folderPath/$fileName").assertCountEquals(0)
    }

    @Test
    fun `the collapsed toolbar keeps the name and drops the folder`() {
        toolbarWith(isCollapsed = true)

        composeTestRule.onAllNodesWithText(fileName)[0].assertIsDisplayed()
        composeTestRule
            .onAllNodesWithText(context.getString(R.string.viewer_toolbar_folder_label))
            .assertCountEquals(0)
    }

    @Test
    fun `the expanded toolbar shows the file's position in the folder block`() {
        toolbarWith(isCollapsed = false, position = 3 to 7)

        composeTestRule.onAllNodesWithText("3 / 7")[0].assertIsDisplayed()
    }

    @Test
    fun `the collapsed toolbar shows no position`() {
        toolbarWith(isCollapsed = true, position = 3 to 7)

        composeTestRule.onAllNodesWithText("3 / 7").assertCountEquals(0)
    }

    @Test
    fun `without a listing there is no position`() {
        toolbarWith(isCollapsed = false)

        composeTestRule.onAllNodesWithText(" / ", substring = true).assertCountEquals(0)
    }

    @Test
    fun `the position is announced in words`() {
        toolbarWith(isCollapsed = false, position = 3 to 7)

        composeTestRule.onAllNodesWithContentDescription("File 3 of 7")[0].assertIsDisplayed()
    }

    private val backLabel: String
        get() = context.getString(R.string.viewer_back_action)

    private var expandCalls = 0
    private var backCalls = 0

    private fun setCard(
        isCollapsed: Boolean = true,
        withBack: Boolean = false,
        withExpand: Boolean = true,
        cardModifier: Modifier = Modifier,
    ) {
        composeTestRule.setContent {
            PreviewWrapper {
                Box(modifier = cardModifier) {
                    ViewerToolbarCard(
                        workspaceId = Workspace.Id(),
                        // Split-pane layout: keeps the mascot-bearing workspace button, which
                        // Robolectric cannot rasterise, out of the toolbar cutout.
                        design = WorkspaceDesign(layout = WorkspaceDesign.Layout.DUAL_VERTICAL),
                        fileName = fileName,
                        folderPath = folderPath,
                        isCollapsed = isCollapsed,
                        onBackClick = if (withBack) {
                            { backCalls++ }
                        } else {
                            null
                        },
                        onExpand = if (withExpand) {
                            { expandCalls++ }
                        } else {
                            null
                        },
                    )
                }
            }
        }
        composeTestRule.waitForIdle()
    }

    private fun tapCentreOfFileName() {
        composeTestRule.onNodeWithText(fileName, useUnmergedTree = true).performTouchInput { click() }
        composeTestRule.waitForIdle()
    }

    @Test
    fun `tapping the collapsed file name asks the bar to expand`() {
        setCard()

        tapCentreOfFileName()

        expandCalls shouldBe 1
    }

    @Test
    fun `tapping the expanded file name does not ask the bar to expand`() {
        setCard(isCollapsed = false, withBack = true)

        tapCentreOfFileName()

        expandCalls shouldBe 0
        backCalls shouldBe 0
    }

    @Test
    fun `the zoom-collapsed file name is not tappable`() {
        setCard(withExpand = false)

        composeTestRule.onNodeWithText(fileName).assertIsDisplayed()
        composeTestRule.onAllNodes(hasClickAction() and hasText(fileName)).assertCountEquals(0)
    }

    private fun assertFileNameAndBackStaySeparate() {
        tapCentreOfFileName()
        expandCalls shouldBe 1

        composeTestRule.onNodeWithContentDescription(backLabel).performTouchInput { click() }
        composeTestRule.waitForIdle()
        backCalls shouldBe 1
        expandCalls shouldBe 1
    }

    @Test
    fun `the collapsed file name expands while back keeps its own action`() {
        setCard(withBack = true)

        assertFileNameAndBackStaySeparate()
    }

    @Test
    fun `the back button edge facing the file name keeps its own action`() {
        setCard(withBack = true)

        composeTestRule.onNodeWithContentDescription(backLabel).performTouchInput {
            click(Offset(width - 1f, centerY))
        }
        composeTestRule.waitForIdle()

        backCalls shouldBe 1
        expandCalls shouldBe 0
    }

    @Test
    fun `a narrow collapsed toolbar keeps the file name and back separate`() {
        setCard(withBack = true, cardModifier = Modifier.width(280.dp))

        assertFileNameAndBackStaySeparate()
    }
}
