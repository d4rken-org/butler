package eu.darken.butler.common.compose

import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import io.kotest.matchers.shouldBe
import org.junit.Test
import testhelpers.ComposeTest

class ClipboardCopyTest : ComposeTest() {

    private fun clipboardText(context: Context): String? {
        val manager = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = manager.primaryClip ?: return null
        if (clip.itemCount == 0) return null
        return clip.getItemAt(0).text?.toString()
    }

    @Test
    fun `the returned lambda writes plain text to the system clipboard`() {
        var context: Context? = null

        composeTestRule.setContent {
            PreviewWrapper {
                context = LocalContext.current
                val copy = rememberClipboardCopy()
                Text(
                    text = "trigger",
                    modifier = Modifier.clickable { copy("com.example.app.MainActivity") },
                )
            }
        }

        composeTestRule.onNodeWithText("trigger").performClick()
        composeTestRule.waitForIdle()

        clipboardText(context!!) shouldBe "com.example.app.MainActivity"
    }

    @Test
    fun `a later copy replaces the previous clip`() {
        var context: Context? = null

        composeTestRule.setContent {
            PreviewWrapper {
                context = LocalContext.current
                val copy = rememberClipboardCopy()
                Column {
                    Text(
                        text = "first",
                        modifier = Modifier.clickable { copy("first-value") },
                    )
                    Text(
                        text = "second",
                        modifier = Modifier.clickable { copy("second-value") },
                    )
                }
            }
        }

        composeTestRule.onNodeWithText("first").performClick()
        composeTestRule.waitForIdle()
        clipboardText(context!!) shouldBe "first-value"

        composeTestRule.onNodeWithText("second").performClick()
        composeTestRule.waitForIdle()
        clipboardText(context!!) shouldBe "second-value"
    }
}
