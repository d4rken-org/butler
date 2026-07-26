package eu.darken.butler.workspace.ui.floatingbar

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import eu.darken.butler.common.compose.PreviewWrapper
import eu.darken.butler.workspace.ui.common.WorkspacePaddings
import io.kotest.matchers.shouldBe
import org.junit.Test
import testhelpers.ComposeTest

/**
 * The pane-edge inset of a floating bar belongs to the stack, not to each call site.
 */
class FloatingBarStackPaddingTest : ComposeTest() {

    private val containerTag = "container"
    private val barTag = "bar"

    private fun assertBarInset(expected: Dp) {
        val container = composeTestRule.onNodeWithTag(containerTag).getUnclippedBoundsInRoot()
        val bar = composeTestRule.onNodeWithTag(barTag).getUnclippedBoundsInRoot()

        (bar.left - container.left) shouldBe expected
        (container.right - bar.right) shouldBe expected
    }

    @Test
    fun `bars are inset from both pane edges by default`() {
        composeTestRule.setContent {
            PreviewWrapper {
                Box(
                    modifier = Modifier
                        .width(300.dp)
                        .height(200.dp)
                        .testTag(containerTag),
                ) {
                    FloatingBarStack(position = BarPosition.BOTTOM) {
                        FloatingBar(key = "bar") {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(40.dp)
                                    .testTag(barTag),
                            )
                        }
                    }
                }
            }
        }

        assertBarInset(WorkspacePaddings.BarHorizontal)
    }

    @Test
    fun `an explicit horizontal padding overrides the default and reacts to changes`() {
        var padding by mutableStateOf(24.dp)

        composeTestRule.setContent {
            PreviewWrapper {
                Box(
                    modifier = Modifier
                        .width(300.dp)
                        .height(200.dp)
                        .testTag(containerTag),
                ) {
                    FloatingBarStack(
                        position = BarPosition.BOTTOM,
                        horizontalPadding = padding,
                    ) {
                        FloatingBar(key = "bar") {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(40.dp)
                                    .testTag(barTag),
                            )
                        }
                    }
                }
            }
        }

        assertBarInset(24.dp)

        composeTestRule.runOnIdle { padding = 4.dp }
        composeTestRule.waitForIdle()

        assertBarInset(4.dp)
    }

    @Test
    fun `the inset stays symmetric in right to left layouts`() {
        composeTestRule.setContent {
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                PreviewWrapper {
                    Box(
                        modifier = Modifier
                            .width(300.dp)
                            .height(200.dp)
                            .testTag(containerTag),
                    ) {
                        FloatingBarStack(position = BarPosition.BOTTOM) {
                            FloatingBar(key = "bar") {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(40.dp)
                                        .testTag(barTag),
                                )
                            }
                        }
                    }
                }
            }
        }

        assertBarInset(WorkspacePaddings.BarHorizontal)
    }

    @Test
    fun `a caller supplied state does not bypass the stack inset`() {
        composeTestRule.setContent {
            PreviewWrapper {
                Box(
                    modifier = Modifier
                        .width(300.dp)
                        .height(200.dp)
                        .testTag(containerTag),
                ) {
                    FloatingBarStack(
                        position = BarPosition.BOTTOM,
                        horizontalPadding = 24.dp,
                        state = rememberFloatingBarStackState(position = BarPosition.BOTTOM),
                    ) {
                        FloatingBar(key = "bar") {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(40.dp)
                                    .testTag(barTag),
                            )
                        }
                    }
                }
            }
        }

        assertBarInset(24.dp)
    }
}
