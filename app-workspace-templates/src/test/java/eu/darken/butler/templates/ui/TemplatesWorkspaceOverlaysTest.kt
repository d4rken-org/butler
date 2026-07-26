package eu.darken.butler.templates.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithTag
import eu.darken.butler.common.compose.PreviewWrapper
import eu.darken.butler.workspace.ui.dialogs.PaneBoundAlertDialogDefaults
import eu.darken.butler.workspace.ui.modal.PaneLayerHost
import org.junit.Test
import testhelpers.ComposeTest

/**
 * Renaming from inside a Templates workspace is an action on that pane, so it has to render from
 * the overlay slot as a pane-bound dialog — a window dialog would dim the whole screen and sit
 * outside the pane's back, focus and accessibility containment.
 */
class TemplatesWorkspaceOverlaysTest : ComposeTest() {

    @Test
    fun `nothing renders while the rename dialog is closed`() {
        composeTestRule.setContent {
            PreviewWrapper {
                PaneLayerHost(modifier = Modifier.fillMaxSize(), paneFocused = true) {
                    TemplatesWorkspaceOverlays(renameVisible = false)
                }
            }
        }

        composeTestRule.onNodeWithTag(PaneBoundAlertDialogDefaults.SURFACE_TEST_TAG).assertDoesNotExist()
    }

    @Test
    fun `the rename dialog renders pane-bound from the overlay slot`() {
        composeTestRule.setContent {
            PreviewWrapper {
                PaneLayerHost(modifier = Modifier.fillMaxSize(), paneFocused = true) {
                    TemplatesWorkspaceOverlays(
                        renameVisible = true,
                        customTitle = "Holiday photos",
                    )
                }
            }
        }

        // The pane-bound scrim and surface, not a window dialog
        composeTestRule.onNodeWithTag(PaneBoundAlertDialogDefaults.SCRIM_TEST_TAG).assertIsDisplayed()
        composeTestRule.onNodeWithTag(PaneBoundAlertDialogDefaults.SURFACE_TEST_TAG).assertIsDisplayed()
    }
}
