package eu.darken.butler.explorer.ui.explorer

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.onNodeWithTag
import eu.darken.butler.common.compose.PreviewWrapper
import eu.darken.butler.common.files.LocalPath
import eu.darken.butler.explorer.ui.explorer.dialogs.ExplorerDialogState
import eu.darken.butler.workspace.ui.dialogs.PaneBoundAlertDialogDefaults
import eu.darken.butler.workspace.ui.modal.PaneLayerHost
import org.junit.Test
import testhelpers.ComposeTest

/** The explorer page's dialogs render from the overlay slot, not from the page. */
class ExplorerWorkspaceOverlaysTest : ComposeTest() {

    @Test
    fun `nothing renders while no dialog is requested`() {
        composeTestRule.setContent {
            PreviewWrapper {
                PaneLayerHost(modifier = Modifier.fillMaxSize(), paneFocused = true) {
                    ExplorerWorkspaceOverlays(dialogState = ExplorerDialogState.None)
                }
            }
        }

        composeTestRule.onNodeWithTag(PaneBoundAlertDialogDefaults.SURFACE_TEST_TAG).assertDoesNotExist()
    }

    @Test
    fun `the rename dialog renders from the overlay slot`() {
        composeTestRule.setContent {
            PreviewWrapper {
                PaneLayerHost(modifier = Modifier.fillMaxSize(), paneFocused = true) {
                    ExplorerWorkspaceOverlays(
                        dialogState = ExplorerDialogState.Rename(
                            LocalPath.build("/storage/emulated/0", "file.txt")
                        ),
                    )
                }
            }
        }

        composeTestRule.onNodeWithTag(PaneBoundAlertDialogDefaults.SURFACE_TEST_TAG).assertIsDisplayed()
        composeTestRule.onNode(hasSetTextAction()).assertIsDisplayed()
    }
}
