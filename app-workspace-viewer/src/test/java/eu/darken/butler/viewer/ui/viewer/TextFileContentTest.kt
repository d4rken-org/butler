package eu.darken.butler.viewer.ui.viewer

import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import eu.darken.butler.common.compose.PreviewWrapper
import eu.darken.butler.viewer.R
import eu.darken.butler.viewer.core.TextPreview
import io.kotest.matchers.shouldBe
import org.junit.Test
import testhelpers.ComposeTest

class TextFileContentTest : ComposeTest() {

    private val context = ApplicationProvider.getApplicationContext<Context>()

    private fun preview(vararg lines: String, truncation: TextPreview.Truncation? = null) = TextPreview(
        lines = lines.toList(),
        charset = Charsets.UTF_8,
        truncation = truncation,
    )

    private val byteLimit = TextPreview.Truncation.Bytes(1024 * 1024)

    private fun truncationNotice() = context.getString(
        R.string.viewer_text_truncated_bytes,
        android.text.format.Formatter.formatShortFileSize(context, 1024 * 1024),
    )

    @Test
    fun `the file's lines are shown`() {
        composeTestRule.setContent {
            PreviewWrapper { TextFileContent(preview = preview("alpha", "bravo")) }
        }

        composeTestRule.onNodeWithText("alpha").assertIsDisplayed()
        composeTestRule.onNodeWithText("bravo").assertIsDisplayed()
    }

    @Test
    fun `a whole file carries no truncation notice`() {
        composeTestRule.setContent {
            PreviewWrapper { TextFileContent(preview = preview("alpha", "bravo")) }
        }

        composeTestRule.onAllNodesWithText(truncationNotice()).fetchSemanticsNodes().size shouldBe 0
    }

    /** The notice is a bar in the viewer's chrome now, so the content itself carries none of it. */
    @Test
    fun `a truncated file's content shows no notice of its own`() {
        composeTestRule.setContent {
            PreviewWrapper {
                TextFileContent(preview = preview("alpha", "bravo", truncation = byteLimit))
            }
        }

        composeTestRule.onNodeWithText("alpha").assertIsDisplayed()
        composeTestRule.onAllNodesWithText(truncationNotice()).fetchSemanticsNodes().size shouldBe 0
    }

    @Test
    fun `the truncation bar states the limit and offers the editor`() {
        var opened = false
        composeTestRule.setContent {
            PreviewWrapper {
                TextTruncationBar(truncation = byteLimit, onOpenInEditor = { opened = true })
            }
        }

        composeTestRule.onNodeWithText(truncationNotice()).assertIsDisplayed()
        composeTestRule
            .onNodeWithText(context.getString(R.string.viewer_open_in_editor_action))
            .performClick()

        opened shouldBe true
    }

    /** The Editor needs a path, so a stream must not be offered a button that does nothing. */
    @Test
    fun `the truncation bar offers no editor button without a path`() {
        composeTestRule.setContent {
            PreviewWrapper {
                TextTruncationBar(truncation = byteLimit, onOpenInEditor = null)
            }
        }

        composeTestRule.onNodeWithText(truncationNotice()).assertIsDisplayed()
        composeTestRule
            .onAllNodesWithText(context.getString(R.string.viewer_open_in_editor_action))
            .fetchSemanticsNodes().size shouldBe 0
    }

    @Test
    fun `a failed read offers retry and the editor`() {
        var retried = false
        var opened = false
        composeTestRule.setContent {
            PreviewWrapper {
                TextFileContent(
                    preview = null,
                    failed = true,
                    onRetry = { retried = true },
                    onOpenInEditor = { opened = true },
                )
            }
        }

        composeTestRule
            .onNodeWithText(context.getString(R.string.viewer_text_unreadable_label))
            .assertIsDisplayed()
        composeTestRule
            .onNodeWithText(context.getString(eu.darken.butler.common.R.string.general_retry_action))
            .performClick()
        composeTestRule
            .onNodeWithText(context.getString(R.string.viewer_open_in_editor_action))
            .performClick()

        retried shouldBe true
        opened shouldBe true
    }

    /** Still loading is not a failure: no error copy, and nothing that suggests one. */
    @Test
    fun `a pending read shows neither the failure nor the notice`() {
        composeTestRule.setContent {
            PreviewWrapper { TextFileContent(preview = null) }
        }

        composeTestRule
            .onAllNodesWithText(context.getString(R.string.viewer_text_unreadable_label))
            .fetchSemanticsNodes().size shouldBe 0
    }

    @Test
    fun `an empty file renders without crashing`() {
        composeTestRule.setContent {
            PreviewWrapper { TextFileContent(preview = preview("")) }
        }

        composeTestRule.onAllNodesWithText(truncationNotice()).fetchSemanticsNodes().size shouldBe 0
    }

    /**
     * A 42 kB minified file cut at the line width has not been "limited to the first 1 MB", and
     * saying so sends the reader looking for a megabyte that was never there.
     */
    @Test
    fun `the bar names the bound that actually cut`() {
        composeTestRule.setContent {
            PreviewWrapper {
                TextTruncationBar(
                    truncation = TextPreview.Truncation.LineWidth(2_000),
                    onOpenInEditor = {},
                )
            }
        }

        composeTestRule
            .onNodeWithText(context.getString(R.string.viewer_text_truncated_width))
            .assertIsDisplayed()
        composeTestRule.onAllNodesWithText(truncationNotice()).fetchSemanticsNodes().size shouldBe 0
    }

    @Test
    fun `a line-count cut names the line limit`() {
        composeTestRule.setContent {
            PreviewWrapper {
                TextTruncationBar(
                    truncation = TextPreview.Truncation.Lines(50_000),
                    onOpenInEditor = {},
                )
            }
        }

        composeTestRule
            .onNodeWithText(
                context.getString(
                    R.string.viewer_text_truncated_lines,
                    java.text.NumberFormat.getIntegerInstance().format(50_000),
                ),
            )
            .assertIsDisplayed()
    }
}
