package eu.darken.butler.explorer.ui.explorer.dialogs

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import eu.darken.butler.common.compose.PreviewWrapper
import eu.darken.butler.workspace.ui.modal.PaneLayerHost
import io.kotest.matchers.shouldBe
import org.junit.Test
import testhelpers.ComposeTest

class BrowsingAbortedDialogTest : ComposeTest() {

    private fun setDialog(
        onRetry: () -> Unit = {},
        onDismiss: () -> Unit = {},
    ) {
        composeTestRule.setContent {
            PreviewWrapper {
                PaneLayerHost(modifier = Modifier.fillMaxSize(), paneFocused = true) {
                    BrowsingAbortedDialog(onRetry = onRetry, onDismiss = onDismiss)
                }
            }
        }
    }

    @Test
    fun `the abort is explained and a retry is offered`() {
        setDialog()

        composeTestRule.onNodeWithText("Operation aborted").assertIsDisplayed()
        composeTestRule.onNodeWithText("Retry").assertIsDisplayed()
        composeTestRule.onNodeWithText("Dismiss").assertIsDisplayed()
    }

    @Test
    fun `retry reports a retry`() {
        var retried = 0
        setDialog(onRetry = { retried++ })

        composeTestRule.onNodeWithText("Retry").performClick()

        composeTestRule.runOnIdle { retried shouldBe 1 }
    }

    @Test
    fun `dismiss reports a dismiss`() {
        var dismissed = 0
        setDialog(onDismiss = { dismissed++ })

        composeTestRule.onNodeWithText("Dismiss").performClick()

        composeTestRule.runOnIdle { dismissed shouldBe 1 }
    }
}
