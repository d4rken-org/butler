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
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import eu.darken.butler.workspace.ui.manager.WorkspaceDesign.PaneEdges
import io.kotest.matchers.shouldBe
import org.junit.Test
import testhelpers.ComposeTest

/**
 * The guard exists so an in-flight system back gesture is not cancelled by the pager starting a
 * drag. What it must not cost is the ordinary swipe, so both halves are pinned here.
 *
 * The pixel-taking overload is used directly: Robolectric reports no system gesture insets, so the
 * composable overload would size the strips to zero and every case below would pass vacuously. The
 * pager starts on its middle page so a drag from either edge, in either direction, has somewhere to go.
 */
class EdgeBackGestureGuardTest : ComposeTest() {

    private lateinit var pagerState: PagerState

    private fun composePager(leftPx: Int, rightPx: Int) {
        composeTestRule.setContent {
            pagerState = rememberPagerState(initialPage = 1, pageCount = { 3 })
            HorizontalPager(
                state = pagerState,
                modifier = Modifier
                    .size(500.dp)
                    .testTag(PAGER)
                    .ignoreEdgeHorizontalDrags(leftPx, rightPx),
            ) {
                Box(modifier = Modifier.fillMaxSize())
            }
        }
        composeTestRule.waitForIdle()
    }

    private fun swipeFrom(startX: (width: Float) -> Float, towardsRight: Boolean) {
        composeTestRule.onNodeWithTag(PAGER).performTouchInput {
            val x = startX(width.toFloat())
            val endX = if (towardsRight) x + width / 2f else x - width / 2f
            swipe(start = Offset(x, centerY), end = Offset(endX, centerY))
        }
        composeTestRule.waitForIdle()
    }

    @Test
    fun `a drag starting in the left strip does not turn the page`() {
        composePager(leftPx = 60, rightPx = 60)

        swipeFrom({ 5f }, towardsRight = true)

        pagerState.settledPage shouldBe 1
    }

    @Test
    fun `a drag starting in the right strip does not turn the page`() {
        composePager(leftPx = 60, rightPx = 60)

        swipeFrom({ it - 5f }, towardsRight = false)

        pagerState.settledPage shouldBe 1
    }

    @Test
    fun `a drag starting away from the edges still turns the page`() {
        composePager(leftPx = 60, rightPx = 60)

        swipeFrom({ it / 2f }, towardsRight = false)

        pagerState.settledPage shouldBe 2
    }

    /** What a start rail leaves in LTR: the left edge sits next to the rail, not the window edge. */
    @Test
    fun `a side without a strip lets a drag from its very first pixel turn the page`() {
        composePager(leftPx = 0, rightPx = 60)

        swipeFrom({ 0f }, towardsRight = true)

        pagerState.settledPage shouldBe 0
    }

    @Test
    fun `a side without a strip lets a drag from just inside it turn the page`() {
        composePager(leftPx = 0, rightPx = 60)

        swipeFrom({ 5f }, towardsRight = true)

        pagerState.settledPage shouldBe 0
    }

    @Test
    fun `the other side keeps its strip when one side has none`() {
        composePager(leftPx = 0, rightPx = 60)

        swipeFrom({ it - 5f }, towardsRight = false)

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
                    .ignoreEdgeHorizontalDrags(60, 60),
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

    @Test
    fun `a pane that reaches both edges gets the wider inset on both sides`() {
        edgeStripWidths(PaneEdges.All, LayoutDirection.Ltr, leftInsetPx = 40, rightInsetPx = 60) shouldBe (60 to 60)
        edgeStripWidths(PaneEdges.All, LayoutDirection.Rtl, leftInsetPx = 40, rightInsetPx = 60) shouldBe (60 to 60)
    }

    @Test
    fun `a start rail removes the left strip in LTR`() {
        val besideStartRail = PaneEdges.All.withoutEdges(start = true)

        edgeStripWidths(besideStartRail, LayoutDirection.Ltr, leftInsetPx = 40, rightInsetPx = 60) shouldBe (0 to 60)
    }

    @Test
    fun `a start rail removes the right strip in RTL`() {
        val besideStartRail = PaneEdges.All.withoutEdges(start = true)

        edgeStripWidths(besideStartRail, LayoutDirection.Rtl, leftInsetPx = 40, rightInsetPx = 60) shouldBe (60 to 0)
    }

    @Test
    fun `a bottom rail leaves both strips in place`() {
        val aboveBottomRail = PaneEdges.All.withoutEdges(bottom = true)

        edgeStripWidths(aboveBottomRail, LayoutDirection.Ltr, leftInsetPx = 40, rightInsetPx = 60) shouldBe (60 to 60)
    }

    @Test
    fun `no gesture insets means no strips`() {
        edgeStripWidths(PaneEdges.All, LayoutDirection.Ltr, leftInsetPx = 0, rightInsetPx = 0) shouldBe (0 to 0)
    }

    companion object {
        private const val PAGER = "pager"
        private const val CONTENT = "content"
    }
}
