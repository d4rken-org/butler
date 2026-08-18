package eu.darken.butler.viewer.ui.viewer

import android.content.Context
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTouchHeightIsEqualTo
import androidx.compose.ui.test.assertTouchWidthIsEqualTo
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import eu.darken.butler.common.compose.PreviewWrapper
import eu.darken.butler.viewer.R
import io.kotest.matchers.shouldBe
import org.junit.Test
import testhelpers.ComposeTest

class PdfPageBarTest : ComposeTest() {

    private val context = ApplicationProvider.getApplicationContext<Context>()

    private val previousLabel = context.getString(R.string.viewer_pdf_page_previous)
    private val nextLabel = context.getString(R.string.viewer_pdf_page_next)

    private fun setBar(
        pageIndex: Int,
        pageCount: Int,
        isRendering: Boolean = false,
        onPreviousPage: () -> Unit = {},
        onNextPage: () -> Unit = {},
    ) {
        composeTestRule.setContent {
            PreviewWrapper {
                PdfPageBar(
                    pageIndex = pageIndex,
                    pageCount = pageCount,
                    isRendering = isRendering,
                    onPreviousPage = onPreviousPage,
                    onNextPage = onNextPage,
                )
            }
        }
    }

    @Test
    fun `a page in the middle shows its position and offers both directions`() {
        setBar(pageIndex = 2, pageCount = 104)

        composeTestRule
            .onNodeWithText(context.getString(R.string.viewer_pdf_page_indicator, 3, 104))
            .assertIsDisplayed()
        composeTestRule.onNodeWithContentDescription(previousLabel).assertIsEnabled()
        composeTestRule.onNodeWithContentDescription(nextLabel).assertIsEnabled()
    }

    @Test
    fun `the first page cannot go back`() {
        setBar(pageIndex = 0, pageCount = 104)

        composeTestRule.onNodeWithContentDescription(previousLabel).assertIsNotEnabled()
        composeTestRule.onNodeWithContentDescription(nextLabel).assertIsEnabled()
    }

    @Test
    fun `the last page cannot go forward`() {
        setBar(pageIndex = 103, pageCount = 104)

        composeTestRule.onNodeWithContentDescription(previousLabel).assertIsEnabled()
        composeTestRule.onNodeWithContentDescription(nextLabel).assertIsNotEnabled()
    }

    @Test
    fun `a running render blocks both directions`() {
        setBar(pageIndex = 2, pageCount = 104, isRendering = true)

        composeTestRule.onNodeWithContentDescription(previousLabel).assertIsNotEnabled()
        composeTestRule.onNodeWithContentDescription(nextLabel).assertIsNotEnabled()
    }

    @Test
    fun `a single page document shows the hint instead of controls`() {
        setBar(pageIndex = 0, pageCount = 1)

        composeTestRule
            .onNodeWithText(context.getString(R.string.viewer_pdf_preview_hint_single))
            .assertIsDisplayed()
        composeTestRule.onNodeWithContentDescription(previousLabel).assertDoesNotExist()
        composeTestRule.onNodeWithContentDescription(nextLabel).assertDoesNotExist()
    }

    @Test
    fun `the controls hand their direction back to the caller`() {
        var previousCount = 0
        var nextCount = 0
        setBar(
            pageIndex = 2,
            pageCount = 104,
            onPreviousPage = { previousCount++ },
            onNextPage = { nextCount++ },
        )

        composeTestRule.onNodeWithContentDescription(nextLabel).performClick()
        composeTestRule.onNodeWithContentDescription(previousLabel).performClick()

        nextCount shouldBe 1
        previousCount shouldBe 1
    }

    @Test
    fun `a narrow bar keeps both controls at full touch target size`() {
        composeTestRule.setContent {
            PreviewWrapper {
                Box(modifier = Modifier.width(160.dp)) {
                    PdfPageBar(pageIndex = 998, pageCount = 1000)
                }
            }
        }

        listOf(previousLabel, nextLabel).forEach { label ->
            composeTestRule.onNodeWithContentDescription(label)
                // 40dp is the button itself, the 48dp it expands to is what a finger has to hit.
                .assertWidthIsAtLeast(40.dp)
                .assertHeightIsAtLeast(40.dp)
                .assertTouchWidthIsEqualTo(48.dp)
                .assertTouchHeightIsEqualTo(48.dp)
                .assertIsEnabled()
                .assertHasClickAction()
        }
    }

    @Test
    fun `the page indicator announces itself as it changes`() {
        setBar(pageIndex = 2, pageCount = 104)

        composeTestRule
            .onNodeWithText(context.getString(R.string.viewer_pdf_page_indicator, 3, 104))
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Polite))
    }
}
