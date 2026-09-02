package eu.darken.butler.workspace.ui.error

import android.content.Context
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import eu.darken.butler.common.compose.PreviewWrapper
import eu.darken.butler.workspace.ui.modal.PaneLayerHost
import io.kotest.matchers.shouldBe
import org.junit.Test
import testhelpers.ComposeTest
import eu.darken.butler.common.R as CommonR

class ErrorShareConsentDialogTest : ComposeTest() {

    private val context: Context = ApplicationProvider.getApplicationContext()

    // Pane host: all but one call site render this dialog as a workspace pane overlay, so the
    // pane-bound renderer is what the dialog actually runs under.
    @Test
    fun `the privacy policy action is offered`() {
        var opened = 0

        composeTestRule.setContent {
            PreviewWrapper {
                PaneLayerHost(modifier = Modifier.fillMaxSize(), paneFocused = true) {
                    ErrorShareConsentDialog(
                        onConfirm = {},
                        onDismiss = {},
                        onPrivacyPolicy = { opened++ },
                    )
                }
            }
        }

        val label = context.getString(CommonR.string.general_privacy_policy_action)
        composeTestRule.onNodeWithText(label).assertIsDisplayed()

        composeTestRule.onNodeWithText(label).performClick()

        composeTestRule.runOnIdle { opened shouldBe 1 }
    }
}
