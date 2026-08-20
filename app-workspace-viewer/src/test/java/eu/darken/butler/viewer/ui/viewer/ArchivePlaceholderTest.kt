package eu.darken.butler.viewer.ui.viewer

import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import eu.darken.butler.common.compose.PreviewWrapper
import eu.darken.butler.common.files.archive.ArchiveFormat
import eu.darken.butler.viewer.R
import eu.darken.butler.viewer.core.ViewerContent
import io.kotest.matchers.shouldBe
import org.junit.Test
import testhelpers.ComposeTest

/** Every state has to offer an action that works, or none at all. */
class ArchivePlaceholderTest : ComposeTest() {

    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun `a browsable archive offers browsing`() {
        var browsed = false
        composeTestRule.setContent {
            PreviewWrapper {
                ArchivePlaceholder(
                    format = ArchiveFormat.ZIP,
                    access = ViewerContent.Archive.Access.BROWSABLE,
                    onBrowse = { browsed = true },
                )
            }
        }

        composeTestRule.onNodeWithText(context.getString(R.string.viewer_archive_title)).assertIsDisplayed()
        composeTestRule
            .onNodeWithText(context.getString(R.string.viewer_browse_archive_action))
            .performClick()

        browsed shouldBe true
    }

    @Test
    fun `a streamed archive offers saving a copy first`() {
        var saved = false
        composeTestRule.setContent {
            PreviewWrapper {
                ArchivePlaceholder(
                    format = ArchiveFormat.TAR_GZ,
                    access = ViewerContent.Archive.Access.NEEDS_COPY,
                    onSaveCopy = { saved = true },
                )
            }
        }

        composeTestRule
            .onNodeWithText(context.getString(R.string.viewer_archive_needs_copy_msg))
            .assertIsDisplayed()
        composeTestRule
            .onNodeWithText(context.getString(R.string.viewer_browse_archive_action))
            .assertDoesNotExist()
        composeTestRule
            .onNodeWithText(context.getString(R.string.viewer_save_copy_action))
            .performClick()

        saved shouldBe true
    }

    /** Saving a copy cannot serve a nested container either, so it gets an explanation, not a button. */
    @Test
    fun `an archive inside an archive offers no action at all`() {
        composeTestRule.setContent {
            PreviewWrapper {
                ArchivePlaceholder(
                    format = ArchiveFormat.ZIP,
                    access = ViewerContent.Archive.Access.NESTED,
                )
            }
        }

        composeTestRule
            .onNodeWithText(context.getString(R.string.viewer_archive_nested_msg))
            .assertIsDisplayed()
        composeTestRule
            .onNodeWithText(context.getString(R.string.viewer_browse_archive_action))
            .assertDoesNotExist()
        composeTestRule
            .onNodeWithText(context.getString(R.string.viewer_save_copy_action))
            .assertDoesNotExist()
    }
}
