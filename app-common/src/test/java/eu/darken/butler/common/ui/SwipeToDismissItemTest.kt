package eu.darken.butler.common.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.unit.dp
import eu.darken.butler.common.compose.PreviewWrapper
import io.kotest.matchers.shouldBe
import org.junit.Test
import testhelpers.ComposeTest

class SwipeToDismissItemTest : ComposeTest() {

    @Test
    fun `full swipe invokes onDismiss once`() {
        var dismissCount = 0

        composeTestRule.setContent {
            PreviewWrapper {
                SwipeToDismissItem(
                    onDismiss = { dismissCount++ },
                    dismissContent = {},
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(56.dp)
                            .testTag("item"),
                    )
                }
            }
        }

        composeTestRule.onNodeWithTag("item").performTouchInput { swipeLeft() }
        composeTestRule.waitForIdle()

        dismissCount shouldBe 1
    }

    @Test
    fun `partial slow swipe below threshold does not dismiss`() {
        var dismissCount = 0

        composeTestRule.setContent {
            PreviewWrapper {
                SwipeToDismissItem(
                    onDismiss = { dismissCount++ },
                    dismissThreshold = 0.5f,
                    dismissContent = {},
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(56.dp)
                            .testTag("item"),
                    )
                }
            }
        }

        composeTestRule.onNodeWithTag("item").performTouchInput {
            swipe(
                start = center,
                end = center - Offset(width * 0.2f, 0f),
                durationMillis = 1000,
            )
        }
        composeTestRule.waitForIdle()

        dismissCount shouldBe 0
    }

    @Test
    fun `disabled item does not dismiss on swipe`() {
        var dismissCount = 0

        composeTestRule.setContent {
            PreviewWrapper {
                SwipeToDismissItem(
                    onDismiss = { dismissCount++ },
                    enabled = false,
                    dismissContent = {},
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(56.dp)
                            .testTag("item"),
                    )
                }
            }
        }

        composeTestRule.onNodeWithTag("item").performTouchInput { swipeLeft() }
        composeTestRule.waitForIdle()

        dismissCount shouldBe 0
    }

    @Test
    fun `programmatic dismiss trigger invokes onDismiss`() {
        var dismissCount = 0
        var trigger by mutableStateOf(0L)

        composeTestRule.setContent {
            PreviewWrapper {
                SwipeToDismissItem(
                    onDismiss = { dismissCount++ },
                    programmaticDismissTrigger = trigger,
                    dismissContent = {},
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(56.dp)
                            .testTag("item"),
                    )
                }
            }
        }

        composeTestRule.runOnIdle { trigger = 1L }
        composeTestRule.waitForIdle()

        dismissCount shouldBe 1
    }

    @Test
    fun `crossing dismiss threshold mid-drag does not invoke onDismiss until settled`() {
        var dismissCount = 0

        composeTestRule.setContent {
            PreviewWrapper {
                SwipeToDismissItem(
                    onDismiss = { dismissCount++ },
                    dismissContent = {},
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(56.dp)
                            .testTag("item"),
                    )
                }
            }
        }

        // Drag past the halfway anchor point but keep the pointer down.
        // Start near the right edge so the pointer stays inside the root bounds —
        // off-screen positions break gesture continuation across performTouchInput blocks.
        composeTestRule.onNodeWithTag("item").performTouchInput {
            down(Offset(right - 2f, centerY))
            moveBy(Offset(-width * 0.75f, 0f))
        }
        composeTestRule.waitForIdle()

        // Regression: the old confirmValueChange-based implementation fired here, mid-gesture
        dismissCount shouldBe 0

        composeTestRule.onNodeWithTag("item").performTouchInput { up() }
        composeTestRule.waitForIdle()

        dismissCount shouldBe 1
    }

    @Test
    fun `held dismiss gesture inside pager does not remove item mid-gesture or move the pager`() {
        lateinit var pagerState: PagerState
        var dismissCount = 0

        composeTestRule.setContent {
            PagerTestContent(
                onPagerState = { pagerState = it },
                onDismiss = { dismissCount++ },
            )
        }

        // Multi-part gesture: recomposition runs between blocks while the pointer is down.
        // Drag past the halfway anchor point, where the old confirmValueChange-based
        // implementation fired onDismiss mid-gesture.
        composeTestRule.onNodeWithTag("Item A").performTouchInput {
            down(Offset(right - 2f, centerY))
            moveBy(Offset(-width * 0.62f, 0f))
        }
        composeTestRule.waitForIdle()

        // Regression: the old implementation dismissed and removed the item here,
        // mid-gesture, handing the rest of the drag to the pager
        composeTestRule.onNodeWithTag("Item A").assertExists()
        dismissCount shouldBe 0

        composeTestRule.onNodeWithTag("pager").performTouchInput { up() }
        composeTestRule.waitForIdle()

        pagerState.currentPage shouldBe 0
    }

    @Test
    fun `completed swipe inside pager dismisses item without changing page`() {
        lateinit var pagerState: PagerState
        var dismissCount = 0

        composeTestRule.setContent {
            PagerTestContent(
                onPagerState = { pagerState = it },
                onDismiss = { dismissCount++ },
            )
        }

        composeTestRule.onNodeWithTag("Item A").performTouchInput { swipeLeft() }
        composeTestRule.waitForIdle()

        dismissCount shouldBe 1
        composeTestRule.onNodeWithTag("Item A").assertDoesNotExist()
        pagerState.currentPage shouldBe 0
    }

    @Composable
    private fun PagerTestContent(
        onPagerState: (PagerState) -> Unit,
        onDismiss: () -> Unit,
    ) {
        PreviewWrapper {
            val pagerState = rememberPagerState(pageCount = { 2 })
            onPagerState(pagerState)
            var items by remember { mutableStateOf(listOf("Item A")) }

            HorizontalPager(
                state = pagerState,
                modifier = Modifier
                    .fillMaxSize()
                    .testTag("pager"),
            ) { page ->
                if (page == 0) {
                    Column(modifier = Modifier.fillMaxSize()) {
                        items.forEach { item ->
                            key(item) {
                                SwipeToDismissItem(
                                    onDismiss = {
                                        onDismiss()
                                        items = items - item
                                    },
                                    dismissContent = {},
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(56.dp)
                                            .testTag(item),
                                    )
                                }
                            }
                        }
                    }
                } else {
                    Text("Page 2")
                }
            }
        }
    }
}
