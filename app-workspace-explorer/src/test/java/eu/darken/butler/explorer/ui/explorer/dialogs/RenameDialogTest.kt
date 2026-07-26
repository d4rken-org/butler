package eu.darken.butler.explorer.ui.explorer.dialogs

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import eu.darken.butler.common.compose.PreviewWrapper
import eu.darken.butler.common.files.LocalPath
import eu.darken.butler.workspace.ui.modal.PaneLayer
import eu.darken.butler.workspace.ui.modal.PaneLayerHost
import eu.darken.butler.workspace.ui.modal.PaneLayerRank
import io.kotest.matchers.shouldBe
import org.junit.Test
import testhelpers.ComposeTest

class RenameDialogTest : ComposeTest() {

    private val item = LocalPath.build("/storage/emulated/0", "file.txt")

    @Test
    fun `takes focus and confirms a new name while it is the active layer`() {
        var result: RenameResult? = null

        composeTestRule.setContent {
            PreviewWrapper {
                PaneLayerHost(modifier = Modifier.fillMaxSize(), paneFocused = true) {
                    RenameDialog(
                        item = item,
                        currentName = "file.txt",
                        onDismiss = {},
                        onConfirm = { result = it },
                    )
                }
            }
        }

        composeTestRule.onNode(hasSetTextAction()).assertIsFocused()

        composeTestRule.onNode(hasSetTextAction()).performTextClearance()
        composeTestRule.onNode(hasSetTextAction()).performTextInput("renamed.txt")
        composeTestRule.onNode(hasClickAction() and hasText("Rename")).performClick()

        composeTestRule.runOnIdle {
            result?.newName shouldBe "renamed.txt"
            result?.item shouldBe item
        }
    }

    @Test
    fun `does not take focus while another layer covers it`() {
        composeTestRule.setContent {
            PreviewWrapper {
                PaneLayerHost(modifier = Modifier.fillMaxSize(), paneFocused = true) {
                    RenameDialog(
                        item = item,
                        currentName = "file.txt",
                        onDismiss = {},
                        onConfirm = {},
                    )
                    PaneLayer(rank = PaneLayerRank.CHILD_CONTENT) {}
                }
            }
        }

        composeTestRule.onNode(hasSetTextAction()).assertIsNotFocused()
    }
}
