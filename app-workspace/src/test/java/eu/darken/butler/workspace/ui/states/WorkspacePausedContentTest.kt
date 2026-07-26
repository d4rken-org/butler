package eu.darken.butler.workspace.ui.states

import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import eu.darken.butler.common.ca.toCaString
import eu.darken.butler.common.compose.PreviewWrapper
import eu.darken.butler.workspace.core.Workspace
import eu.darken.butler.workspace.core.label
import org.junit.Test
import testhelpers.ComposeTest

/**
 * The placeholder is what the user sees for a paused tab: it must name the tab, not just its type.
 */
class WorkspacePausedContentTest : ComposeTest() {

    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun `renders the derived title and subtitle`() {
        composeTestRule.setContent {
            PreviewWrapper {
                WorkspacePausedContent(
                    type = Workspace.Type.SEARCHER,
                    title = "*.pdf".toCaString(),
                    subtitle = "/sdcard/Download".toCaString(),
                    onResume = {},
                )
            }
        }

        composeTestRule.onNodeWithText("*.pdf").assertIsDisplayed()
        composeTestRule.onNodeWithText("/sdcard/Download").assertIsDisplayed()
    }

    @Test
    fun `falls back to the type label without a title`() {
        composeTestRule.setContent {
            PreviewWrapper {
                WorkspacePausedContent(
                    type = Workspace.Type.EXPLORER,
                    onResume = {},
                )
            }
        }

        composeTestRule
            .onNodeWithText(Workspace.Type.EXPLORER.label.get(context))
            .assertIsDisplayed()
    }

    @Test
    fun `falls back to the type label for a blank title`() {
        composeTestRule.setContent {
            PreviewWrapper {
                WorkspacePausedContent(
                    type = Workspace.Type.EXPLORER,
                    title = "   ".toCaString(),
                    onResume = {},
                )
            }
        }

        composeTestRule
            .onNodeWithText(Workspace.Type.EXPLORER.label.get(context))
            .assertIsDisplayed()
        composeTestRule.onNodeWithText("   ").assertDoesNotExist()
    }

    @Test
    fun `a blank subtitle draws no second line`() {
        composeTestRule.setContent {
            PreviewWrapper {
                WorkspacePausedContent(
                    type = Workspace.Type.EDITOR,
                    title = "notes.txt".toCaString(),
                    subtitle = "".toCaString(),
                    onResume = {},
                )
            }
        }

        composeTestRule.onNodeWithText("notes.txt").assertIsDisplayed()
        composeTestRule.onNodeWithText("").assertDoesNotExist()
    }
}
