package eu.darken.butler.workspace.ui.dialogs

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import eu.darken.butler.common.compose.PreviewWrapper
import eu.darken.butler.common.ui.dialogs.AdaptiveAlertDialog
import eu.darken.butler.common.ui.dialogs.LocalAlertDialogRenderer
import eu.darken.butler.common.ui.dialogs.WindowAlertDialogRenderer
import eu.darken.butler.workspace.ui.modal.PaneLayerHost
import io.kotest.matchers.shouldBe
import org.junit.Test
import testhelpers.ComposeTest

/**
 * Shared dialogs in `app-common` cannot reference the pane-modal primitives, so which dialog they
 * become is decided by the composition around them. Inside a pane it must be the pane-bound one;
 * everywhere else the platform window dialog.
 */
class AlertDialogRendererTest : ComposeTest() {

    private val scrim = PaneBoundAlertDialogDefaults.SCRIM_TEST_TAG

    @Test
    fun `the renderer defaults to the window dialog outside a pane`() {
        var renderer: Any? = null

        composeTestRule.setContent {
            PreviewWrapper {
                renderer = LocalAlertDialogRenderer.current
                AdaptiveAlertDialog(
                    onDismissRequest = {},
                    title = { Text("Outside") },
                    confirmButton = { TextButton(onClick = {}) { Text("OK") } },
                )
            }
        }

        composeTestRule.runOnIdle { renderer shouldBe WindowAlertDialogRenderer }
        composeTestRule.onNodeWithText("Outside").assertExists()
        // The window dialog has no pane scrim — that tag only exists on the pane-bound one
        composeTestRule.onNodeWithTag(scrim).assertDoesNotExist()
    }

    @Test
    fun `the pane layer host swaps in the pane-bound renderer`() {
        var renderer: Any? = null

        composeTestRule.setContent {
            PreviewWrapper {
                PaneLayerHost(modifier = Modifier.fillMaxSize(), paneFocused = true) {
                    renderer = LocalAlertDialogRenderer.current
                    AdaptiveAlertDialog(
                        onDismissRequest = {},
                        title = { Text("Inside") },
                        confirmButton = { TextButton(onClick = {}) { Text("OK") } },
                    )
                }
            }
        }

        composeTestRule.runOnIdle { renderer shouldBe PaneBoundAlertDialogRenderer }
        composeTestRule.onNodeWithText("Inside").assertExists()
        composeTestRule.onNodeWithTag(scrim).assertExists()
    }
}
