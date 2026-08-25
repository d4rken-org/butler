package eu.darken.butler.workspace.ui.workspaces.classic

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.click
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.compose.ui.unit.dp
import io.kotest.matchers.shouldBe
import org.junit.Test
import testhelpers.ComposeTest

/**
 * The guard exists so an in-flight system back gesture is not cancelled by the pager starting a
 * drag. What it must not cost is the ordinary swipe, so both halves are pinned here.
 *
 * The pixel-taking overload is used directly: Robolectric reports no system gesture insets, so the
 * composable overload would size the strip to zero and every case below would pass vacuously.
 */
class EdgeBackGestureGuardTest : ComposeTest() {

    private val edgePx = 100

    private lateinit var pagerState: PagerState

    private fun composePager(pages: Int = 3) {
        composeTestRule.setContent {
            pagerState = rememberPagerState(pageCount = { pages })
            HorizontalPager(
                state = pagerState,
                modifier = Modifier
                    .size(500.dp)
                    .testTag(PAGER)
                    .ignoreEdgeHorizontalDrags(edgePx),
            ) {
                Box(modifier = Modifier.fillMaxSize())
            }
        }
        composeTestRule.waitForIdle()
    }

    @Test
    fun `a horizontal drag starting in the edge strip does not turn the page`() {
        composePager()

        composeTestRule.onNodeWithTag(PAGER).performTouchInput {
            swipe(start = Offset(5f, centerY), end = Offset(width - 5f, centerY))
        }
        composeTestRule.waitForIdle()

        pagerState.settledPage shouldBe 0
    }

    @Test
    fun `a horizontal drag starting away from the edge still turns the page`() {
        composePager()

        composeTestRule.onNodeWithTag(PAGER).performTouchInput {
            swipe(start = Offset(centerX, centerY), end = Offset(centerX - width / 2f, centerY))
        }
        composeTestRule.waitForIdle()

        pagerState.settledPage shouldBe 1
    }

    /** The strip is only closed to horizontal drags; everything else must still land. */
    @Test
    fun `a tap in the edge strip still reaches the content`() {
        var taps = 0
        composeTestRule.setContent {
            pagerState = rememberPagerState(pageCount = { 2 })
            HorizontalPager(
                state = pagerState,
                modifier = Modifier
                    .size(500.dp)
                    .testTag(PAGER)
                    .ignoreEdgeHorizontalDrags(edgePx),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .testTag(CONTENT)
                        .clickable { taps++ },
                )
            }
        }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag(CONTENT).performTouchInput {
            click(Offset(5f, centerY))
        }
        composeTestRule.waitForIdle()

        taps shouldBe 1
    }

    companion object {
        private const val PAGER = "pager"
        private const val CONTENT = "content"
    }
}
