package eu.darken.butler.common.compose

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import io.kotest.matchers.shouldBe
import org.junit.Test
import testhelpers.ComposeTest

/**
 * A chip without an `onClick` is decoration. Rendered as a clickable [androidx.compose.material3.Surface]
 * it still swallows presses (a disabled clickable consumes pointer input) and claims a 48dp minimum
 * touch target, which puts an invisible dead zone over the row it sits on.
 */
class ButlerChipTouchTargetTest : ComposeTest() {

    @Composable
    private fun Case(
        onRowClick: () -> Unit,
        chipOnClick: (() -> Unit)?,
    ) {
        PreviewWrapper {
            Box(
                modifier = Modifier
                    .size(width = 300.dp, height = 96.dp)
                    .clickable(onClick = onRowClick)
                    .testTag(ROW_TAG),
                contentAlignment = Alignment.Center,
            ) {
                ButlerChip(
                    modifier = Modifier.testTag(CHIP_TAG),
                    label = "Tag",
                    onClick = chipOnClick,
                )
            }
        }
    }

    private fun pressChip() {
        composeTestRule.onNodeWithTag(CHIP_TAG, useUnmergedTree = true).performTouchInput {
            down(center)
            up()
        }
        composeTestRule.waitForIdle()
    }

    @Test
    fun `a decorative chip lets presses through to the content beneath it`() {
        var rowClicks = 0
        composeTestRule.setContent { Case(onRowClick = { rowClicks++ }, chipOnClick = null) }

        pressChip()

        rowClicks shouldBe 1
    }

    @Test
    fun `a clickable chip consumes the press itself`() {
        var rowClicks = 0
        var chipClicks = 0
        composeTestRule.setContent { Case(onRowClick = { rowClicks++ }, chipOnClick = { chipClicks++ }) }

        pressChip()

        chipClicks shouldBe 1
        rowClicks shouldBe 0
    }

    companion object {
        private const val ROW_TAG = "chip.row"
        private const val CHIP_TAG = "chip.under.test"
    }
}
