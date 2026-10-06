package eu.darken.butler.workspace.ui.floatingbar

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import androidx.compose.ui.unit.lerp
import eu.darken.butler.common.compose.PreviewWrapper
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.runBlocking
import org.junit.Test
import testhelpers.ComposeTest

/**
 * Content laid out with [FloatingBarContentPadding] has to follow a collapsing bar on its own. Short
 * content never scrolls, so nothing but the padding read itself can bring it up under the bar.
 */
class FloatingBarContentPaddingCollapseTest : ComposeTest() {

    private val toolbarTag = "toolbar"
    private val itemTag = "item"

    @Composable
    private fun FloatingBarScope.CollapsingToolbar() {
        FloatingBar(
            key = toolbarTag,
            scrollBehavior = BarScrollBehavior.CollapseOnScroll,
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(lerp(120.dp, 48.dp, collapsedFraction))
                    .testTag(toolbarTag),
            )
        }
    }

    private fun collapseAndAssertItemFollows(stackState: FloatingBarStackState) {
        val toolbarBefore = composeTestRule.onNodeWithTag(toolbarTag).getUnclippedBoundsInRoot()
        composeTestRule.onNodeWithTag(itemTag).getUnclippedBoundsInRoot().top shouldBe toolbarBefore.bottom

        runBlocking { stackState.applyCollapse(mapOf(toolbarTag to 1f)) }
        composeTestRule.waitForIdle()

        val toolbarAfter = composeTestRule.onNodeWithTag(toolbarTag).getUnclippedBoundsInRoot()
        toolbarAfter.height shouldBe 48.dp
        composeTestRule.onNodeWithTag(itemTag).getUnclippedBoundsInRoot().top shouldBe toolbarAfter.bottom
    }

    @Test
    fun `a single short list item follows the collapsing bar`() {
        lateinit var stackState: FloatingBarStackState

        composeTestRule.setContent {
            PreviewWrapper {
                Box(
                    modifier = Modifier
                        .width(300.dp)
                        .height(600.dp),
                ) {
                    stackState = rememberFloatingBarStackState(
                        position = BarPosition.TOP,
                        includeSystemBarInset = false,
                    )
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxSize()
                            .nestedScroll(stackState.nestedScrollConnection),
                        contentPadding = rememberFloatingBarContentPadding(topStackState = stackState),
                    ) {
                        item {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(40.dp)
                                    .testTag(itemTag),
                            )
                        }
                    }
                    FloatingBarStack(
                        position = BarPosition.TOP,
                        state = stackState,
                    ) {
                        CollapsingToolbar()
                    }
                }
            }
        }

        collapseAndAssertItemFollows(stackState)
    }

    /**
     * One measure observation that reads the padding while the stack is still empty (the estimate)
     * and again once its bar has registered and been measured, e.g.
     *
     * ```
     * read padding   -> 192 (estimate, no bar yet)
     * bar registers  -> measured at 120
     * read padding   -> 128
     * bar collapses  -> 48, padding has to become 56
     * ```
     */
    @Test
    fun `a padding read that started before the bars registered keeps tracking their heights`() {
        lateinit var stackState: FloatingBarStackState

        composeTestRule.setContent {
            PreviewWrapper {
                Box(
                    modifier = Modifier
                        .width(300.dp)
                        .height(600.dp),
                ) {
                    stackState = rememberFloatingBarStackState(
                        position = BarPosition.TOP,
                        includeSystemBarInset = false,
                        estimatedContentPadding = 192.dp,
                    )
                    val padding = rememberFloatingBarContentPadding(topStackState = stackState)
                    SubcomposeLayout(modifier = Modifier.fillMaxSize()) { constraints ->
                        padding.calculateTopPadding()
                        val bars = subcompose("bars") {
                            FloatingBarStack(
                                position = BarPosition.TOP,
                                state = stackState,
                            ) {
                                CollapsingToolbar()
                            }
                        }.map { it.measure(constraints) }
                        val top = padding.calculateTopPadding().roundToPx()
                        val content = subcompose("content") {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(40.dp)
                                    .testTag(itemTag),
                            )
                        }.map { it.measure(constraints.copy(minHeight = 0)) }
                        layout(constraints.maxWidth, constraints.maxHeight) {
                            content.forEach { it.place(0, top) }
                            bars.forEach { it.place(0, 0) }
                        }
                    }
                }
            }
        }

        collapseAndAssertItemFollows(stackState)
    }
}
