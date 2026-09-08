package eu.darken.butler.workspace.ui.issues

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performTextReplacement
import eu.darken.butler.common.compose.PreviewWrapper
import eu.darken.butler.workspace.ui.modal.PaneLayerHost
import io.kotest.matchers.shouldBe
import org.junit.Test
import testhelpers.ComposeTest

class PathIssueRenameDialogTest : ComposeTest() {

    private fun setDialog(onConfirm: (String) -> Unit) {
        composeTestRule.setContent {
            PreviewWrapper {
                PaneLayerHost(modifier = Modifier.fillMaxSize(), paneFocused = true) {
                    PathIssueRenameDialog(
                        currentName = "file.txt",
                        onConfirm = onConfirm,
                        onDismiss = {},
                    )
                }
            }
        }
    }

    @Test
    fun `confirms a new name from the keyboard action`() {
        var result: String? = null
        setDialog { result = it }

        composeTestRule.onNode(hasSetTextAction()).performTextReplacement("  renamed.txt  ")
        composeTestRule.onNode(hasSetTextAction()).performImeAction()

        composeTestRule.runOnIdle { result shouldBe "renamed.txt" }
    }

    @Test
    fun `the keyboard action does nothing while the name is unchanged`() {
        var result: String? = null
        setDialog { result = it }

        composeTestRule.onNode(hasSetTextAction()).performImeAction()

        composeTestRule.runOnIdle { result shouldBe null }
    }
}
