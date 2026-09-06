package eu.darken.butler.explorer.ui.explorer.items

import androidx.compose.ui.test.hasText
import eu.darken.butler.common.compose.PreviewWrapper
import eu.darken.butler.explorer.core.ExplorerViewStyle
import eu.darken.butler.explorer.ui.explorer.items.row.DirectoryRow
import eu.darken.butler.explorer.ui.explorer.items.row.RegularFileRow
import eu.darken.butler.explorer.ui.explorer.preview.MockDataProvider
import io.kotest.matchers.shouldBe
import org.junit.Test
import testhelpers.ComposeTest

class RowDensityTest : ComposeTest() {

    private val file = MockDataProvider.createMockRegularFile()

    /** The fixture carries no creation time, which is also what SAF supplies. */
    private val directoryWithoutCreatedAt = MockDataProvider.createMockDirectory()

    private fun fileRowAt(density: ExplorerViewStyle.Density) {
        composeTestRule.setContent {
            PreviewWrapper {
                RegularFileRow(
                    item = file,
                    density = density,
                    isSelected = false,
                    onToggleSelection = {},
                    onClick = {},
                    showSelection = false,
                )
            }
        }
    }

    private fun countMatching(text: String) = composeTestRule
        .onAllNodes(hasText(text, substring = true))
        .fetchSemanticsNodes()
        .size

    @Test
    fun `a compact row drops permissions and ownership`() {
        fileRowAt(ExplorerViewStyle.Density.COMPACT)

        countMatching(file.permissions!!.toReadableString()) shouldBe 0
        countMatching("aUser") shouldBe 0
    }

    @Test
    fun `a comfortable row keeps permissions and ownership`() {
        fileRowAt(ExplorerViewStyle.Density.COMFORTABLE)

        countMatching(file.permissions!!.toReadableString()) shouldBe 1
        countMatching("aUser") shouldBe 1
    }

    @Test
    fun `a comfortable row carries no creation date`() {
        fileRowAt(ExplorerViewStyle.Density.COMFORTABLE)

        countMatching("Created") shouldBe 0
    }

    @Test
    fun `a detailed row carries the creation date`() {
        fileRowAt(ExplorerViewStyle.Density.DETAILED)

        countMatching("Created") shouldBe 1
    }

    @Test
    fun `a detailed row without a creation time omits the line`() {
        composeTestRule.setContent {
            PreviewWrapper {
                DirectoryRow(
                    item = directoryWithoutCreatedAt,
                    density = ExplorerViewStyle.Density.DETAILED,
                    isSelected = false,
                    onToggleSelection = {},
                    onClick = {},
                    showSelection = false,
                )
            }
        }

        countMatching("Created") shouldBe 0
    }
}
