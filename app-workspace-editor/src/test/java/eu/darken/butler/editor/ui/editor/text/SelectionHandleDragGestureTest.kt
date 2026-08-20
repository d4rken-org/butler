package eu.darken.butler.editor.ui.editor.text

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import eu.darken.butler.common.compose.PreviewWrapper
import eu.darken.butler.editor.core.engine.TextPosition
import io.kotest.matchers.shouldBe
import org.junit.Test
import testhelpers.ComposeTest

/**
 * The gesture boundaries [SelectionDragCoordinator] depends on: the anchor is captured on drag start
 * and released on drag end, so a missing or duplicated callback would either drop the anchor
 * mid-gesture or carry a stale one into the next drag.
 */
class SelectionHandleDragGestureTest : ComposeTest() {

    private val handleTag = "test.selection.handle"

    /** Real content, so the handle's column is not clamped to 0 by an empty line. */
    private val line = "0123456789".repeat(12)

    private val column = 60

    /**
     * With no [androidx.compose.ui.text.TextLayoutResult] reported for the line, the handle falls
     * back to the measured advance: `padding + column * charWidth - halfHandle`.
     */
    private val handleCenterX = 8f + column * 1f - 12f + 12f

    @Test
    fun `a drag reports exactly one start before the first move and one end on lift`() {
        val events = mutableListOf<String>()
        composeTestRule.setContent {
            PreviewWrapper {
                val listState = rememberLazyListState()
                Box(modifier = Modifier.fillMaxSize()) {
                    LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                        items(1) {
                            Text(
                                text = line,
                                style = TextStyle(fontSize = 14.sp, fontFamily = FontFamily.Monospace),
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                    SelectionHandle(
                        modifier = Modifier.testTag(handleTag),
                        position = TextPosition(offset = 0L, line = 0L, column = column),
                        contentListState = listState,
                        lineNumberWidth = 0.dp,
                        horizontalScrollState = rememberScrollState(),
                        actualCharWidth = 1f,
                        onDragStart = { events += "start" },
                        onDragEnd = { events += "end" },
                        onDrag = { events += "drag" },
                        visibleLineContent = mapOf(0L to line),
                    )
                }
            }
        }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag(handleTag).performTouchInput {
            down(Offset(handleCenterX, 12f))
            moveTo(Offset(handleCenterX - 40f, 12f))
            moveTo(Offset(handleCenterX - 60f, 12f))
            up()
        }
        composeTestRule.waitForIdle()

        events.first() shouldBe "start"
        events.last() shouldBe "end"
        events.count { it == "start" } shouldBe 1
        events.count { it == "end" } shouldBe 1
        events.count { it == "drag" } shouldBe events.size - 2
    }

    /**
     * The engine nulls the selection on reloads, document switches and every cursor move, which
     * takes the handles out of composition mid-gesture - no pointer ever ends. Compose still
     * closes the gesture: `Modifier.Node.runDetachLifecycle` dispatches a synthesized cancel to
     * the hit path BEFORE cancelling the pointer input coroutine, so `detectDragGestures` reaches
     * `onDragCancel`. This pins that ordering, because the anchor release depends on it.
     */
    @Test
    fun `a drag cut short by the handle leaving composition still reports its end`() {
        val events = mutableListOf<String>()
        val handleVisible = mutableStateOf(true)
        composeTestRule.setContent {
            PreviewWrapper {
                val listState = rememberLazyListState()
                Box(modifier = Modifier.fillMaxSize()) {
                    LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                        items(1) {
                            Text(
                                text = line,
                                style = TextStyle(fontSize = 14.sp, fontFamily = FontFamily.Monospace),
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                    if (handleVisible.value) {
                        SelectionHandle(
                            modifier = Modifier.testTag(handleTag),
                            position = TextPosition(offset = 0L, line = 0L, column = column),
                            contentListState = listState,
                            lineNumberWidth = 0.dp,
                            horizontalScrollState = rememberScrollState(),
                            actualCharWidth = 1f,
                            onDragStart = { events += "start" },
                            onDragEnd = { events += "end" },
                            onDrag = { events += "drag" },
                            visibleLineContent = mapOf(0L to line),
                        )
                    }
                }
            }
        }
        composeTestRule.waitForIdle()

        // Finger stays down: no up(), no cancel() - the gesture is still running.
        composeTestRule.onNodeWithTag(handleTag).performTouchInput {
            down(Offset(handleCenterX, 12f))
            moveTo(Offset(handleCenterX - 40f, 12f))
        }
        composeTestRule.waitForIdle()

        events.count { it == "start" } shouldBe 1
        events.count { it == "end" } shouldBe 0

        handleVisible.value = false
        composeTestRule.waitForIdle()

        events.count { it == "end" } shouldBe 1
    }
}
