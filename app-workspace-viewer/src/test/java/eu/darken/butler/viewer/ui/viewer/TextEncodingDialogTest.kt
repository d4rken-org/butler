package eu.darken.butler.viewer.ui.viewer

import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import eu.darken.butler.common.compose.PreviewWrapper
import eu.darken.butler.common.files.text.TextCharsets
import eu.darken.butler.viewer.R
import io.kotest.matchers.shouldBe
import org.junit.Test
import org.robolectric.annotation.Config
import testhelpers.ComposeTest
import java.nio.charset.Charset

@Config(qualifiers = "w400dp-h800dp")
class TextEncodingDialogTest : ComposeTest() {

    private val context = ApplicationProvider.getApplicationContext<Context>()

    private val picks = mutableListOf<Charset?>()

    private fun setDialog(selected: Charset?, autoCharset: Charset?) {
        composeTestRule.setContent {
            PreviewWrapper {
                TextEncodingDialog(
                    selected = selected,
                    autoCharset = autoCharset,
                    onSelect = { picks += it },
                    onDismiss = {},
                )
            }
        }
    }

    @Test
    fun `automatic names the detected charset and is marked without a pick`() {
        setDialog(selected = null, autoCharset = TextCharsets.GB18030)

        composeTestRule
            .onNodeWithText(context.getString(R.string.viewer_text_encoding_automatic_detected, "GB18030"))
            .assertIsDisplayed()
            .assertIsSelected()
        composeTestRule.onNodeWithText("GB18030").assertIsNotSelected()
    }

    @Test
    fun `automatic stays plain while nothing was detected`() {
        setDialog(selected = Charsets.UTF_16LE, autoCharset = null)

        composeTestRule
            .onNodeWithText(context.getString(R.string.viewer_text_encoding_automatic))
            .assertIsNotSelected()
        composeTestRule.onNodeWithText("UTF-16LE").performScrollTo().assertIsSelected()
    }

    @Test
    fun `selecting an entry reports it, and automatic reports null`() {
        setDialog(selected = Charsets.UTF_8, autoCharset = Charsets.UTF_8)

        composeTestRule.onNodeWithText("windows-1252").performScrollTo().performClick()
        composeTestRule
            .onNodeWithText(context.getString(R.string.viewer_text_encoding_automatic_detected, "UTF-8"))
            .performScrollTo()
            .performClick()

        picks shouldBe listOf(TextCharsets.WINDOWS_1252, null)
    }
}
