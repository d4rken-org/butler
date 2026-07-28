package eu.darken.butler.workspace.ui.common

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertRangeInfoEquals
import androidx.compose.ui.test.assertTopPositionInRootIsEqualTo
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import eu.darken.butler.common.compose.PreviewWrapper
import eu.darken.butler.workspace.ui.floatingbar.BarPosition
import eu.darken.butler.workspace.ui.floatingbar.rememberFloatingBarStackState
import io.kotest.matchers.shouldBe
import org.junit.Test
import testhelpers.ComposeTest

/**
 * The pop-in indicator sits at a fixed anchor below the top bar stack: it may not exist while
 * idle (it would cover the first content row) and it may not move as the pull progresses.
 */
class WorkspacePullToRefreshTest : ComposeTest() {

    private val indicatorTag = "ptr-indicator"

    private val progressNode = SemanticsMatcher.keyIsDefined(SemanticsProperties.ProgressBarRangeInfo)

    @Test
    fun `nothing is composed while idle`() {
        composeTestRule.setContent {
            PreviewWrapper {
                WorkspacePullToRefreshIndicator(
                    modifier = Modifier.testTag(indicatorTag),
                    progress = { 0f },
                    isRefreshing = false,
                )
            }
        }

        composeTestRule.onNodeWithTag(indicatorTag).assertDoesNotExist()
    }

    @Test
    fun `pulling shows the pull distance as determinate progress`() {
        composeTestRule.setContent {
            PreviewWrapper {
                WorkspacePullToRefreshIndicator(
                    modifier = Modifier.testTag(indicatorTag),
                    progress = { 0.5f },
                    isRefreshing = false,
                )
            }
        }

        composeTestRule.onNodeWithTag(indicatorTag).assertExists()
        composeTestRule.onNode(progressNode).assertRangeInfoEquals(ProgressBarRangeInfo(0.5f, 0f..1f))
    }

    @Test
    fun `refreshing shows indeterminate progress`() {
        composeTestRule.setContent {
            PreviewWrapper {
                WorkspacePullToRefreshIndicator(
                    modifier = Modifier.testTag(indicatorTag),
                    progress = { 1f },
                    isRefreshing = true,
                )
            }
        }

        composeTestRule.onNodeWithTag(indicatorTag).assertExists()
        composeTestRule.onNode(progressNode).assertRangeInfoEquals(ProgressBarRangeInfo.Indeterminate)
    }

    @Test
    fun `pull progress never moves the indicator`() {
        val progress = mutableFloatStateOf(0.25f)
        composeTestRule.setContent {
            PreviewWrapper {
                Box(modifier = Modifier.size(200.dp)) {
                    WorkspacePullToRefreshIndicator(
                        modifier = Modifier.testTag(indicatorTag),
                        progress = { progress.floatValue },
                        isRefreshing = false,
                    )
                }
            }
        }

        // Measured on the spinner, not the tagged container: only the spinner is composed inside
        // the indicator's graphicsLayer, so only its bounds observe the layer transform. Centre,
        // not top: the pull's scale is about the default centre origin and leaves the centre
        // untouched, while a pull-driven translation moves it. boundsInRoot is the transformed rect;
        // getUnclippedBoundsInRoot() is position + untransformed size and shifts under scale alone.
        val quarterPull = composeTestRule.onNode(progressNode).fetchSemanticsNode().boundsInRoot.center.y

        composeTestRule.runOnIdle { progress.floatValue = 1f }

        composeTestRule.onNode(progressNode).fetchSemanticsNode().boundsInRoot.center.y shouldBe quarterPull
    }

    @Test
    fun `the indicator is anchored below the top bar stack`() {
        composeTestRule.setContent {
            PreviewWrapper {
                WorkspacePullToRefreshBox(
                    isRefreshing = true,
                    onRefresh = {},
                    topBarStackState = rememberFloatingBarStackState(
                        position = BarPosition.TOP,
                        estimatedContentPadding = ANCHOR,
                    ),
                    content = {},
                )
            }
        }

        // No bars register in a test, so the stack reports its estimate. The indicator container
        // starts there; the assertable node is the spinner centred inside it.
        val spinnerInset = (INDICATOR_CONTAINER_SIZE - SPINNER_SIZE) / 2
        composeTestRule.onNode(progressNode).assertTopPositionInRootIsEqualTo(ANCHOR + spinnerInset)
    }

    @Test
    fun `the indicator stays visible briefly after refreshing ends`() {
        composeTestRule.mainClock.autoAdvance = false
        val refreshing = mutableStateOf(true)
        composeTestRule.setContent {
            PreviewWrapper {
                WorkspacePullToRefreshBox(
                    isRefreshing = refreshing.value,
                    onRefresh = {},
                    topBarStackState = rememberFloatingBarStackState(
                        position = BarPosition.TOP,
                        estimatedContentPadding = ANCHOR,
                    ),
                    content = {},
                )
            }
        }

        composeTestRule.onNode(progressNode).assertExists()

        refreshing.value = false
        composeTestRule.mainClock.advanceTimeBy(200)
        composeTestRule.onNode(progressNode).assertExists()

        composeTestRule.mainClock.advanceTimeBy(1200)
        composeTestRule.onNode(progressNode).assertDoesNotExist()
    }

    companion object {
        private val ANCHOR = 100.dp
        private val INDICATOR_CONTAINER_SIZE = 40.dp
        private val SPINNER_SIZE = 20.dp
    }
}
