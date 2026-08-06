package eu.darken.butler.editor.ui.editor.elements

import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTouchInput
import eu.darken.butler.common.ca.toCaString
import eu.darken.butler.common.compose.PreviewWrapper
import eu.darken.butler.workspace.core.Workspace
import eu.darken.butler.workspace.ui.manager.WorkspaceDesign
import org.junit.Test
import testhelpers.ComposeTest

/** The toolbar's icon buttons label themselves on long press. */
class EditorToolbarCardTooltipTest : ComposeTest() {

    @Test
    fun `long-pressing the open button shows its tooltip`() {
        // Manual clock: a plain tooltip auto-dismisses ~1.5s after appearing, which would race
        // the assertion under automatic advancement.
        composeTestRule.mainClock.autoAdvance = false
        composeTestRule.setContent {
            PreviewWrapper {
                EditorToolbarCard(
                    workspaceId = Workspace.Id(),
                    design = WorkspaceDesign(layout = WorkspaceDesign.Layout.DUAL_VERTICAL),
                    title = "example.txt".toCaString(),
                    subTitle = "/storage/emulated/0".toCaString(),
                    isModified = false,
                    progress = null,
                    hasContent = true,
                    hasFile = true,
                    canUndo = false,
                    canRedo = false,
                    onAction = {},
                )
            }
        }

        composeTestRule.onNodeWithContentDescription("Open").performTouchInput { longClick() }
        composeTestRule.mainClock.advanceTimeBy(200)

        composeTestRule.onNodeWithText("Open").assertExists()
    }
}
