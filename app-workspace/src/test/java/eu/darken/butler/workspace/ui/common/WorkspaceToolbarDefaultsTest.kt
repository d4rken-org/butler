package eu.darken.butler.workspace.ui.common

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredHeightIn
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import eu.darken.butler.common.compose.PreviewWrapper
import io.kotest.matchers.shouldBe
import org.junit.Test
import testhelpers.ComposeTest

class WorkspaceToolbarDefaultsTest : ComposeTest() {

    private val cardTag = "card"

    // 20dp plus 2 * 6dp padding stays below both floors, so the height comes from the floor alone.
    private fun cardWith(isCollapsed: Boolean) {
        composeTestRule.setContent {
            PreviewWrapper {
                CutoutCard(
                    modifier = Modifier
                        .requiredHeightIn(min = WorkspaceToolbarDefaults.animatedMinHeight(isCollapsed))
                        .testTag(cardTag),
                    cutoutContent = null,
                    contentPadding = CutoutCardDefaults.contentPadding(6.dp),
                ) {
                    Box(modifier = Modifier.size(20.dp))
                }
            }
        }
    }

    @Test
    fun `a collapsed toolbar card is as tall as the compact workspace button`() {
        cardWith(isCollapsed = true)

        val card = composeTestRule.onNodeWithTag(cardTag).getUnclippedBoundsInRoot()

        (card.bottom - card.top) shouldBe WorkspaceToolbarDefaults.MinHeightCollapsed
    }

    @Test
    fun `an expanded toolbar card is as tall as the default workspace button`() {
        cardWith(isCollapsed = false)

        val card = composeTestRule.onNodeWithTag(cardTag).getUnclippedBoundsInRoot()

        (card.bottom - card.top) shouldBe WorkspaceToolbarDefaults.MinHeightExpanded
    }
}
