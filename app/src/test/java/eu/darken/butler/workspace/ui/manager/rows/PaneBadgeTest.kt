package eu.darken.butler.workspace.ui.manager.rows

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithText
import eu.darken.butler.common.compose.PreviewWrapper
import org.junit.Test
import testhelpers.ComposeTest

class PaneBadgeTest : ComposeTest() {

    @Test
    fun `pane 0 displays as 1`() {
        composeTestRule.setContent {
            PreviewWrapper {
                PaneBadge(paneNumber = 0)
            }
        }

        composeTestRule.onNodeWithText("1").assertIsDisplayed()
    }

    @Test
    fun `pane 1 displays as 2`() {
        composeTestRule.setContent {
            PreviewWrapper {
                PaneBadge(paneNumber = 1)
            }
        }

        composeTestRule.onNodeWithText("2").assertIsDisplayed()
    }

    @Test
    fun `pane 2 displays as 3`() {
        composeTestRule.setContent {
            PreviewWrapper {
                PaneBadge(paneNumber = 2)
            }
        }

        composeTestRule.onNodeWithText("3").assertIsDisplayed()
    }
}
