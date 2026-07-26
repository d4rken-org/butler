package eu.darken.butler.workspace.ui.dialogs

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import eu.darken.butler.common.compose.PreviewWrapper
import eu.darken.butler.workspace.ui.modal.PaneLayerHost
import io.kotest.matchers.shouldBe
import org.junit.Test
import testhelpers.ComposeTest

class WorkspaceRenameDialogTest : ComposeTest() {

    private fun setDialog(
        currentCustomTitle: String?,
        onConfirm: (String?) -> Unit,
    ) {
        composeTestRule.setContent {
            PreviewWrapper {
                WorkspaceRenameDialog(
                    currentCustomTitle = currentCustomTitle,
                    autoTitle = "/storage/emulated/0/Download",
                    onConfirm = onConfirm,
                    onDismiss = {},
                )
            }
        }
    }

    private fun setPaneBoundDialog(
        currentCustomTitle: String?,
        onConfirm: (String?) -> Unit,
    ) {
        composeTestRule.setContent {
            PreviewWrapper {
                PaneLayerHost(modifier = Modifier.fillMaxSize(), paneFocused = true) {
                    PaneBoundWorkspaceRenameDialog(
                        currentCustomTitle = currentCustomTitle,
                        autoTitle = "/storage/emulated/0/Download",
                        onConfirm = onConfirm,
                        onDismiss = {},
                    )
                }
            }
        }
    }

    @Test
    fun `confirming an empty field clears the custom name`() {
        var confirmed: String? = "unset"
        var confirmCount = 0
        setDialog(currentCustomTitle = "Holiday photos") {
            confirmed = it
            confirmCount++
        }

        composeTestRule.onNode(hasSetTextAction()).performTextReplacement("")
        composeTestRule.onNodeWithText("Rename").performClick()

        confirmCount shouldBe 1
        confirmed shouldBe null
    }

    @Test
    fun `confirming text reports the trimmed name`() {
        var confirmed: String? = null
        setDialog(currentCustomTitle = null) { confirmed = it }

        composeTestRule.onNode(hasSetTextAction()).performTextReplacement("  Holiday photos  ")
        composeTestRule.onNodeWithText("Rename").performClick()

        confirmed shouldBe "Holiday photos"
    }

    @Test
    fun `the pane-bound variant clears the name in one press`() {
        var confirmed: String? = "unset"
        var confirmCount = 0
        setPaneBoundDialog(currentCustomTitle = "Holiday photos") {
            confirmed = it
            confirmCount++
        }

        composeTestRule.onNodeWithText("Clear").performClick()

        composeTestRule.runOnIdle {
            confirmCount shouldBe 1
            confirmed shouldBe null
        }
    }

    @Test
    fun `the pane-bound variant offers no clear action without a custom name`() {
        setPaneBoundDialog(currentCustomTitle = null) {}

        composeTestRule.onNodeWithText("Clear").assertDoesNotExist()
    }

    /** Material's `AlertDialog` has no neutral slot, so the window variant deliberately has no Clear. */
    @Test
    fun `the window variant offers no clear action`() {
        setDialog(currentCustomTitle = "Holiday photos") {}

        composeTestRule.onNodeWithText("Clear").assertDoesNotExist()
    }
}
