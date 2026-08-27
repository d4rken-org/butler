package eu.darken.butler.editor.ui.editor.text

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import eu.darken.butler.common.compose.PreviewWrapper
import eu.darken.butler.editor.core.engine.TextPosition
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.shouldBe
import org.junit.Test
import testhelpers.ComposeTest

/**
 * A selection handle over a [LazyColumn] that carries top content padding: the handle has to sit on
 * its line, and the offsets its drag emits have to land back on that same line when they are run
 * through [calculatePositionFromOffset].
 *
 * Both halves are asserted by one drag. The handle is only 24px tall, so a touch aimed at its line
 * reaches the gesture at all only when the handle is placed there; `graphicsLayer` translations
 * reach hit testing but not `boundsInRoot`, so injection coordinates are root coordinates.
 *
 * Items are 20px tall and the padding is 64px, so a handle or a drag offset that is off by the
 * padding lands three lines away.
 */
class SelectionHandlePaddingTest : ComposeTest() {

    private val handleTag = "test.selection.handle"

    private val itemCount = 12
    private val itemHeightPx = 20f
    private val handleLine = 5L
    private val column = 30

    /** Long enough that [column] is not clamped away. */
    private val visibleLineContent = (0 until itemCount).associate { it.toLong() to "x".repeat(60) }

    /** No `TextLayoutResult` is reported, so the handle falls back to the measured advance. */
    private val handleCenterX = 8f + column * 1f - 12f + 12f

    private var topPaddingPx by mutableFloatStateOf(64f)

    private lateinit var listState: LazyListState
    private lateinit var density: Density
    private val dragged = mutableListOf<Offset>()

    private fun setHandle() {
        composeTestRule.setContent {
            PreviewWrapper {
                density = LocalDensity.current
                listState = rememberLazyListState()
                Box(modifier = Modifier.fillMaxSize()) {
                    LazyColumn(
                        state = listState,
                        contentPadding = PaddingValues(top = with(density) { topPaddingPx.toDp() }),
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        items(itemCount) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(with(density) { itemHeightPx.toDp() }),
                            )
                        }
                    }
                    SelectionHandle(
                        modifier = Modifier.testTag(handleTag),
                        position = TextPosition(offset = 0L, line = handleLine, column = column),
                        contentListState = listState,
                        lineNumberWidth = 0.dp,
                        horizontalScrollState = rememberScrollState(),
                        actualCharWidth = 1f,
                        onDrag = { dragged += it },
                        visibleLineContent = visibleLineContent,
                    )
                }
            }
        }
        composeTestRule.waitForIdle()
    }

    /** Touches the handle at the vertical centre of its line and reports where the drag lands. */
    private fun dragOnHandleLine(): List<Long> {
        val lineCentreY = topPaddingPx + handleLine * itemHeightPx + itemHeightPx / 2f
        dragged.clear()
        composeTestRule.onNodeWithTag(handleTag).performTouchInput {
            down(Offset(handleCenterX, lineCentreY))
            // Far enough to clear touch slop, and horizontal so the line under the finger is fixed.
            moveTo(Offset(handleCenterX + 40f, lineCentreY))
            moveTo(Offset(handleCenterX + 60f, lineCentreY))
            up()
        }
        composeTestRule.waitForIdle()

        return dragged.map { offset ->
            calculatePositionFromOffset(
                offset = offset,
                contentListState = listState,
                visibleLineContent = visibleLineContent,
                density = density,
                charWidthPx = 1f,
                tabSize = 4,
            )!!.position.line
        }
    }

    @Test
    fun `a drag on the handle's line stays on that line`() {
        setHandle()

        val lines = dragOnHandleLine()

        lines.shouldNotBeEmpty()
        lines.distinct() shouldBe listOf(handleLine)
    }

    @Test
    fun `the handle follows a padding change on an already laid out list`() {
        setHandle()
        dragOnHandleLine().distinct() shouldBe listOf(handleLine)

        topPaddingPx = 12f
        composeTestRule.waitForIdle()

        val lines = dragOnHandleLine()

        lines.shouldNotBeEmpty()
        lines.distinct() shouldBe listOf(handleLine)
    }
}
