package eu.darken.butler.workspace.ui.floatingbar

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import eu.darken.butler.common.compose.PreviewWrapper
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.runBlocking
import org.junit.Test
import testhelpers.ComposeTest

/**
 * A [BarScrollBehavior.CollapseOnScroll] bar occupies exactly the height it was measured at, so the
 * bar below it keeps its spacing however far the collapsing bar's content has shrunk.
 *
 * This is a forward-looking invariant rather than a reproduction: the drift it guards against needed
 * a separately declared collapsed height to disagree with the measured one, and there is no such
 * declaration left to make.
 */
class FloatingBarCollapseGapTest : ComposeTest() {

    private val toolbarTag = "toolbar"
    private val infoTag = "info"

    private fun gapBelowToolbar(): Dp {
        val toolbar = composeTestRule.onNodeWithTag(toolbarTag).getUnclippedBoundsInRoot()
        val info = composeTestRule.onNodeWithTag(infoTag).getUnclippedBoundsInRoot()
        return info.top - toolbar.bottom
    }

    @Test
    fun `the bar below a collapsing bar keeps its spacing at every toolbar height`() {
        var toolbarHeight by mutableStateOf(64.dp)
        lateinit var stackState: FloatingBarStackState

        composeTestRule.setContent {
            PreviewWrapper {
                Box(
                    modifier = Modifier
                        .width(300.dp)
                        .height(400.dp),
                ) {
                    stackState = rememberFloatingBarStackState(
                        position = BarPosition.TOP,
                        defaultSpacing = 8.dp,
                    )
                    FloatingBarStack(
                        position = BarPosition.TOP,
                        defaultSpacing = 8.dp,
                        state = stackState,
                    ) {
                        FloatingBar(
                            key = "toolbar",
                            scrollBehavior = BarScrollBehavior.CollapseOnScroll,
                        ) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(toolbarHeight)
                                    .testTag(toolbarTag),
                            )
                        }
                        FloatingBar(key = "info") {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(32.dp)
                                    .testTag(infoTag),
                            )
                        }
                    }
                }
            }
        }

        gapBelowToolbar() shouldBe 8.dp
        val expandedPaddingPx = composeTestRule.runOnIdle { stackState.contentPaddingPx }

        // Mid-collapse height: the collapse path holds no fraction term, so settled samples across
        // the range are equivalent evidence to sampling the spring itself.
        composeTestRule.runOnIdle { toolbarHeight = 52.dp }
        composeTestRule.waitForIdle()

        gapBelowToolbar() shouldBe 8.dp

        composeTestRule.runOnIdle { toolbarHeight = 40.dp }
        runBlocking { stackState.applyCollapse(mapOf("toolbar" to 1f)) }
        composeTestRule.waitForIdle()

        gapBelowToolbar() shouldBe 8.dp

        val collapsedPaddingPx = composeTestRule.runOnIdle { stackState.contentPaddingPx }
        // Content padding shares effectiveHeight, so it has to track the same measured height
        (expandedPaddingPx - collapsedPaddingPx) shouldBe with(composeTestRule.density) { 24.dp.toPx() }
    }
}
