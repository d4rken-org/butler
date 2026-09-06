package eu.darken.butler.searcher.ui.search.items

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.unit.dp
import eu.darken.butler.common.compose.PreviewWrapper
import eu.darken.butler.searcher.core.SearchItem
import eu.darken.butler.searcher.core.SearcherViewStyle
import eu.darken.butler.searcher.ui.search.preview.SearcherMockDataProvider
import io.kotest.matchers.comparables.shouldBeLessThan
import io.kotest.matchers.shouldBe
import org.junit.Test
import testhelpers.ComposeTest

class RowDensityTest : ComposeTest() {

    private val result = SearcherMockDataProvider.createMockSearchResult(
        name = "config.json",
        sizeKB = 12,
        hoursAgo = 3,
        matchedQuery = "timeout",
        matchContext = SearchItem.MatchContext(
            lineNumber = 42,
            matchedLine = "  \"timeout\": 5000,",
            startIndex = 3,
            endIndex = 10,
        ),
    )

    private fun rowAt(density: SearcherViewStyle.Density) {
        composeTestRule.setContent {
            PreviewWrapper {
                SelectableFileRow(
                    result = result,
                    density = density,
                    isSelected = false,
                    isSelectionMode = false,
                    onClick = {},
                    onLongPress = {},
                )
            }
        }
    }

    private fun countMatching(text: String) = composeTestRule
        .onAllNodes(hasText(text, substring = true))
        .fetchSemanticsNodes()
        .size

    /** The match is the reason the result is in the list, so it survives every density. */
    @Test
    fun `a compact row keeps the match line`() {
        rowAt(SearcherViewStyle.Density.COMPACT)

        countMatching("timeout") shouldBe 1
    }

    @Test
    fun `a compact row drops the size`() {
        rowAt(SearcherViewStyle.Density.COMPACT)

        countMatching("12") shouldBe 0
    }

    @Test
    fun `a comfortable row keeps the size`() {
        rowAt(SearcherViewStyle.Density.COMFORTABLE)

        countMatching("12") shouldBe 1
    }

    private fun tileIconWidth(density: SearcherViewStyle.Density) = composeTestRule
        .onNode(hasContentDescription("File") and hasAnyAncestor(hasTestTag(density.name)), useUnmergedTree = true)
        .getUnclippedBoundsInRoot()
        .width

    @Test
    fun `a grid tile scales its icon with the density`() {
        composeTestRule.setContent {
            PreviewWrapper {
                Column {
                    listOf(SearcherViewStyle.Density.COMPACT, SearcherViewStyle.Density.DETAILED).forEach { density ->
                        Box(
                            modifier = Modifier
                                .testTag(density.name)
                                .size(200.dp),
                        ) {
                            SelectableFileGrid(
                                result = result,
                                density = density,
                                isSelected = false,
                                isSelectionMode = false,
                                onClick = {},
                                onLongPress = {},
                            )
                        }
                    }
                }
            }
        }

        tileIconWidth(SearcherViewStyle.Density.COMPACT) shouldBeLessThan
            tileIconWidth(SearcherViewStyle.Density.DETAILED)
    }
}
