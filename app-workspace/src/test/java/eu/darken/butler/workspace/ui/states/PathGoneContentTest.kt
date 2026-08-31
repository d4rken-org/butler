package eu.darken.butler.workspace.ui.states

import android.content.Context
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import eu.darken.butler.common.compose.PreviewWrapper
import eu.darken.butler.common.files.LocalPath
import eu.darken.butler.common.files.errors.PathNotFoundException
import eu.darken.butler.workspace.R
import io.kotest.matchers.shouldBe
import org.junit.Test
import testhelpers.ComposeTest

/**
 * The point of this screen is that a vanished file reads as a fact about the file rather than as a
 * fault in the app, so the assertions are about the words the user actually gets.
 */
class PathGoneContentTest : ComposeTest() {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val path = LocalPath.build("/storage/emulated/0/Documents/notes.txt")

    @Test
    fun `names the missing file rather than the exception class`() {
        composeTestRule.setContent {
            PreviewWrapper {
                PathGoneBody(error = PathNotFoundException(path))
            }
        }

        // The filename is what the user recognises; the raw path and the class name are not.
        composeTestRule.onNodeWithText("notes.txt", substring = true).assertIsDisplayed()
    }

    @Test
    fun `the full-pane variant offers closing the tab`() {
        var closed = false
        composeTestRule.setContent {
            PreviewWrapper {
                PathGoneContent(
                    error = PathNotFoundException(path),
                    onShareError = {},
                    onCloseWorkspace = { closed = true },
                )
            }
        }

        composeTestRule.onNodeWithText(context.getString(R.string.workspace_close_tab_action)).performClick()
        closed shouldBe true
    }

    @Test
    fun `close is absent when the caller offers no way to close`() {
        composeTestRule.setContent {
            PreviewWrapper {
                PathGoneContent(
                    error = PathNotFoundException(path),
                    onShareError = {},
                    onCloseWorkspace = null,
                )
            }
        }

        composeTestRule
            .onAllNodesWithText(context.getString(R.string.workspace_close_tab_action))
            .assertCountEquals(0)
    }
}
