package eu.darken.butler.workspace.ui.dialogs

import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import eu.darken.butler.common.compose.PreviewWrapper
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
}
