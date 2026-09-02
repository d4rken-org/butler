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

    private fun preview(vararg lines: String, isTruncated: Boolean = false) = TextPreview(
        lines = lines.toList(),
        charset = Charsets.UTF_8,
        isTruncated = isTruncated,
        limitBytes = 1024 * 1024,
    )

    private fun truncationNotice() = context.getString(
        R.string.viewer_text_truncated_notice,
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

    /** The cut has to be stated where the reader is, not at the end of a list they may never reach. */
    @Test
    fun `a truncated file says so and offers the editor`() {
        var opened = false
        composeTestRule.setContent {
            PreviewWrapper {
                TextFileContent(
                    preview = preview("alpha", "bravo", isTruncated = true),
                    onOpenInEditor = { opened = true },
                )
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
    fun `a truncated stream offers no editor button`() {
        composeTestRule.setContent {
            PreviewWrapper {
                TextFileContent(
                    preview = preview("alpha", isTruncated = true),
                    editorAvailable = false,
                )
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
}
