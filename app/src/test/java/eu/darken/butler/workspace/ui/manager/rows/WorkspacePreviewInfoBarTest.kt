package eu.darken.butler.workspace.ui.manager.rows

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import eu.darken.butler.common.ca.CaString
import eu.darken.butler.common.ca.toCaString
import eu.darken.butler.common.compose.PreviewWrapper
import io.kotest.matchers.shouldBe
import org.junit.Test
import testhelpers.ComposeTest

/**
 * The bar overlays a screenshot of arbitrary content, so a bar with nothing to say must not paint
 * its background over the preview at all. Once it is drawn it always occupies two rows so the grid
 * reads as one band: a line without content still holds its row, it just carries no visible text.
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

    private fun infoBarTexts(): List<String> = composeTestRule
        .onAllNodes(hasAnyAncestor(hasTestTag(TEST_TAG_WORKSPACE_CARD_INFOBAR)), useUnmergedTree = true)
        .fetchSemanticsNodes()
        .flatMap { it.config.getOrNull(SemanticsProperties.Text).orEmpty() }
        .map { it.text }

    @Test
    fun `both lines render`() {
        setContent("/sdcard/Download".toCaString(), "42 items".toCaString())

        composeTestRule.onNodeWithText("/sdcard/Download").assertIsDisplayed()
        composeTestRule.onNodeWithText("42 items").assertIsDisplayed()
    }

    @Test
    fun `a missing primary leaves the secondary and reserves the first row`() {
        setContent(null, "42 items".toCaString())

        composeTestRule.onNodeWithTag(TEST_TAG_WORKSPACE_CARD_INFOBAR, useUnmergedTree = true).assertExists()
        composeTestRule.onNodeWithText("42 items").assertIsDisplayed()
        infoBarTexts() shouldBe listOf("", "42 items")
    }

    @Test
    fun `a missing secondary leaves the primary and reserves the second row`() {
        setContent("/sdcard/Download".toCaString(), null)

        composeTestRule.onNodeWithTag(TEST_TAG_WORKSPACE_CARD_INFOBAR, useUnmergedTree = true).assertExists()
        composeTestRule.onNodeWithText("/sdcard/Download").assertIsDisplayed()
        infoBarTexts() shouldBe listOf("/sdcard/Download", "")
    }

    @Test
    fun `a blank secondary draws no text but keeps its row`() {
        setContent("/sdcard/Download".toCaString(), "   ".toCaString())

        composeTestRule.onNodeWithText("/sdcard/Download").assertIsDisplayed()
        composeTestRule.onNodeWithText("   ").assertDoesNotExist()
        infoBarTexts() shouldBe listOf("/sdcard/Download", "")
    }

    @Test
    fun `two blank lines render nothing at all`() {
        setContent("".toCaString(), "   ".toCaString())

        composeTestRule.onNodeWithTag(TEST_TAG_WORKSPACE_CARD_INFOBAR, useUnmergedTree = true).assertDoesNotExist()
    }
}
