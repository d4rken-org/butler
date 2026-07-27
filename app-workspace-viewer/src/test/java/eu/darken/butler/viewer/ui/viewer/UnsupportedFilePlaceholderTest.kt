package eu.darken.butler.viewer.ui.viewer

import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import eu.darken.butler.common.compose.PreviewWrapper
import eu.darken.butler.viewer.R
import io.kotest.matchers.shouldBe
import org.junit.Test
import testhelpers.ComposeTest

class UnsupportedFilePlaceholderTest : ComposeTest() {

    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun `shows the detected mime type`() {
        composeTestRule.setContent {
            PreviewWrapper {
                UnsupportedFilePlaceholder(mimeType = "application/pdf", onOpenWith = {})
            }
        }

        composeTestRule
            .onNodeWithText(context.getString(R.string.viewer_unsupported_title))
            .assertIsDisplayed()
        composeTestRule.onNodeWithText("application/pdf").assertIsDisplayed()
    }

    @Test
    fun `the open with button reports back`() {
        var opened = false
        composeTestRule.setContent {
            PreviewWrapper {
                UnsupportedFilePlaceholder(mimeType = "application/zip", onOpenWith = { opened = true })
            }
        }

        composeTestRule
            .onNodeWithText(context.getString(R.string.viewer_open_with_action))
            .performClick()

        opened shouldBe true
    }
}
