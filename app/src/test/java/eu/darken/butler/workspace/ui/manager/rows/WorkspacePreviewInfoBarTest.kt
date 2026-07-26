package eu.darken.butler.workspace.ui.manager.rows

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import eu.darken.butler.common.ca.CaString
import eu.darken.butler.common.ca.toCaString
import eu.darken.butler.common.compose.PreviewWrapper
import org.junit.Test
import testhelpers.ComposeTest

/**
 * The bar overlays a screenshot of arbitrary content, so a line it cannot fill must not be drawn -
 * and a bar with nothing to say must not paint its background over the preview either.
 */
class WorkspacePreviewInfoBarTest : ComposeTest() {

    private fun setContent(primary: CaString?, secondary: CaString?) {
        composeTestRule.setContent {
            PreviewWrapper {
                WorkspacePreviewInfoBar(
                    primary = primary,
                    secondary = secondary,
                )
            }
        }
    }

    @Test
    fun `both lines render`() {
        setContent("/sdcard/Download".toCaString(), "42 items".toCaString())

        composeTestRule.onNodeWithText("/sdcard/Download").assertIsDisplayed()
        composeTestRule.onNodeWithText("42 items").assertIsDisplayed()
    }

    @Test
    fun `a missing primary leaves the secondary`() {
        setContent(null, "42 items".toCaString())

        composeTestRule.onNodeWithTag(TEST_TAG_WORKSPACE_CARD_INFOBAR, useUnmergedTree = true).assertExists()
        composeTestRule.onNodeWithText("42 items").assertIsDisplayed()
    }

    @Test
    fun `a missing secondary leaves the primary`() {
        setContent("/sdcard/Download".toCaString(), null)

        composeTestRule.onNodeWithTag(TEST_TAG_WORKSPACE_CARD_INFOBAR, useUnmergedTree = true).assertExists()
        composeTestRule.onNodeWithText("/sdcard/Download").assertIsDisplayed()
    }

    @Test
    fun `a blank secondary is treated as absent`() {
        setContent("/sdcard/Download".toCaString(), "   ".toCaString())

        composeTestRule.onNodeWithText("/sdcard/Download").assertIsDisplayed()
        composeTestRule.onNodeWithText("   ").assertDoesNotExist()
    }

    @Test
    fun `two blank lines render nothing at all`() {
        setContent("".toCaString(), "   ".toCaString())

        composeTestRule.onNodeWithTag(TEST_TAG_WORKSPACE_CARD_INFOBAR, useUnmergedTree = true).assertDoesNotExist()
    }
}
