package eu.darken.butler.workspace.ui.manager.rows

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.style.ResolvedTextDirection
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.LayoutDirection
import eu.darken.butler.common.ca.CaString
import eu.darken.butler.common.ca.toCaString
import eu.darken.butler.common.compose.PreviewWrapper
import io.kotest.matchers.shouldBe
import org.junit.Test
import org.robolectric.annotation.GraphicsMode
import testhelpers.ComposeTest

/**
 * The bar overlays a screenshot of arbitrary content, so a bar with nothing to say must not paint
 * its background over the preview at all. Once it is drawn it always occupies two rows so the grid
 * reads as one band: a line without content still holds its row, it just carries no visible text.
 */
class WorkspacePreviewInfoBarTest : ComposeTest() {

    private fun setContent(
        primary: CaString?,
        secondary: CaString?,
        layoutDirection: LayoutDirection = LayoutDirection.Ltr,
    ) {
        composeTestRule.setContent {
            PreviewWrapper {
                CompositionLocalProvider(LocalLayoutDirection provides layoutDirection) {
                    WorkspacePreviewInfoBar(
                        primary = primary,
                        secondary = secondary,
                    )
                }
            }
        }
    }

    private fun layoutOf(text: String): TextLayoutResult {
        val results = mutableListOf<TextLayoutResult>()
        composeTestRule.onNodeWithText(text, useUnmergedTree = true)
            .fetchSemanticsNode()
            .config[SemanticsActions.GetTextLayoutResult]
            .action!!
            .invoke(results)
        return results.single()
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

    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    @Test
    fun `in an rtl layout a path line reads left to right and a label line right to left`() {
        setContent(PATH.toCaString(), ARABIC_LABEL.toCaString(), LayoutDirection.Rtl)

        val path = layoutOf(PATH)
        path.layoutInput.style.textDirection shouldBe TextDirection.Content
        path.layoutInput.style.textAlign shouldBe TextAlign.Right
        path.getParagraphDirection(0) shouldBe ResolvedTextDirection.Ltr

        val label = layoutOf(ARABIC_LABEL)
        label.layoutInput.style.textDirection shouldBe TextDirection.Content
        label.layoutInput.style.textAlign shouldBe TextAlign.Right
        label.getParagraphDirection(0) shouldBe ResolvedTextDirection.Rtl
    }

    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    @Test
    fun `in an ltr layout an arabic title reads right to left on the left edge`() {
        setContent(ARABIC_LABEL.toCaString(), null, LayoutDirection.Ltr)

        val title = layoutOf(ARABIC_LABEL)
        title.layoutInput.style.textDirection shouldBe TextDirection.Content
        title.layoutInput.style.textAlign shouldBe TextAlign.Left
        title.getParagraphDirection(0) shouldBe ResolvedTextDirection.Rtl
    }

    @Test
    fun `a path without letters is still laid out by its content in an rtl layout`() {
        setContent(DIGITS_PATH.toCaString(), null, LayoutDirection.Rtl)

        val title = layoutOf(DIGITS_PATH)
        title.layoutInput.style.textDirection shouldBe TextDirection.Content
        title.layoutInput.style.textAlign shouldBe TextAlign.Right
    }

    companion object {
        private const val PATH = "/storage/emulated/0/Download"
        private const val ARABIC_LABEL = "ملفات حديثة"
        private const val DIGITS_PATH = "/123/456"
    }
}
