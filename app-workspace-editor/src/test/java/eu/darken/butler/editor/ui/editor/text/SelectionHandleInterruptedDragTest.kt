package eu.darken.butler.editor.ui.editor.text

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.TouchInjectionScope
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
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
 * Drags whose gesture stops before the finger lifts still end exactly once, so nothing a caller
 * captured on start outlives them, and the finger's later lift cannot end them a second time.
 */
class SelectionHandleInterruptedDragTest : ComposeTest() {

    private val handleTag = "test.selection.handle"

    private val line = "0123456789".repeat(12)

    private val column = 60

    /** Measured-advance fallback, as in [SelectionHandleDragGestureTest]. */
    private val handleCenterX = 8f + column * 1f

    private var ends = 0
    private var gutterWidth by mutableStateOf(0.dp)
    private lateinit var listState: LazyListState

    private fun setHandle() {
        composeTestRule.setContent {
            PreviewWrapper {
                listState = rememberLazyListState()
                Box(modifier = Modifier.fillMaxSize()) {
                    LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                        items(200) {
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
                        lineNumberWidth = gutterWidth,
                        horizontalScrollState = rememberScrollState(),
                        actualCharWidth = 1f,
                        onDragEnd = { ends++ },
                        onDrag = {},
                        visibleLineContent = mapOf(0L to line),
                    )
                }
            }
        }
        composeTestRule.waitForIdle()
    }

    /** Injected at the root: the handle's `graphicsLayer` translation never reaches its bounds. */
    private fun touch(block: TouchInjectionScope.() -> Unit) {
        composeTestRule.onRoot().performTouchInput(block)
        composeTestRule.waitForIdle()
    }

    private fun startDrag() {
        touch { down(Offset(handleCenterX, 12f)) }
        touch { moveTo(Offset(handleCenterX - 40f, 12f)) }
        touch { moveTo(Offset(handleCenterX - 60f, 12f)) }
        ends shouldBe 0
    }

    @Test
    fun `a lifted finger ends the drag exactly once`() {
        setHandle()

        startDrag()
        touch { up() }

        ends shouldBe 1
    }

    @Test
    fun `a drag whose line scrolls out of the list ends exactly once`() {
        setHandle()

        startDrag()
        // The handle stays composed, only its gesture box leaves with the line
        composeTestRule.runOnIdle { listState.requestScrollToItem(100) }
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag(handleTag).assertDoesNotExist()
        ends shouldBe 1

        touch { up() }
        ends shouldBe 1
    }

    @Test
    fun `a drag whose gesture restarts on a key change ends exactly once`() {
        setHandle()

        startDrag()
        composeTestRule.runOnIdle { gutterWidth = 1.dp }
        composeTestRule.waitForIdle()
        ends shouldBe 1

        touch { up() }
        ends shouldBe 1
    }
}
