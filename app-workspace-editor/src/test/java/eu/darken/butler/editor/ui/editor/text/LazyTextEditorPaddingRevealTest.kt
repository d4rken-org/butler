package eu.darken.butler.editor.ui.editor.text

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import eu.darken.butler.common.compose.PreviewWrapper
import eu.darken.butler.editor.core.engine.EditorEngine
import eu.darken.butler.editor.core.engine.TextPosition
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.floats.plusOrMinus
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CompletableDeferred
import org.junit.Test
import testhelpers.ComposeTest
import kotlin.uuid.Uuid

/**
 * The bottom content padding can grow after the caret was scrolled into view (the keyboard opens a
 * moment after a tap, a bar slides in after navigation). A caret it covers shortly after its move is
 * revealed again, unless the user scrolled since. A jump made while the paddings filled the whole
 * viewport is centred once there is room.
 */
class LazyTextEditorPaddingRevealTest : ComposeTest() {

    private val engineEpoch = Uuid.random()
    private var cursor by mutableStateOf(TextPosition(0L, 0L, 0))
    private var scrollTrigger by mutableIntStateOf(0)
    private var bottomPadding by mutableStateOf(BOTTOM_PADDING)

    private fun setEditor() {
        composeTestRule.setContent {
            PreviewWrapper {
                LazyTextEditor(
                    contentPadding = PaddingValues(top = TOP_PADDING, bottom = bottomPadding),
                    content = (0 until TOTAL_LINES).joinToString("\n") { "row-$it" },
                    totalLines = TOTAL_LINES.toLong(),
                    cursorPosition = cursor,
                    selection = null,
                    windowToken = EditorEngine.DocumentToken(engineEpoch, 0L),
                    scrollTrigger = scrollTrigger,
                    onEnqueueDelta = {
                        CompletableDeferred(
                            EditorEngine.MutationResult.Applied(EditorEngine.DocumentToken(engineEpoch, 0L)),
                        )
                    },
                    onCursorPositionChange = { cursor = it },
                    onSelectionChange = {},
                    onVisibleRangeChange = {},
                    onCursorMove = { _, _ -> },
                    onForwardDelete = {},
                )
            }
        }
        composeTestRule.waitForIdle()
    }

    private fun moveCursor(line: Int) {
        cursor = TextPosition(0L, line.toLong(), 0)
        composeTestRule.waitForIdle()
    }

    private fun scrollToLine(line: Int) {
        composeTestRule.onNodeWithTag(EDITOR_CONTENT_TEST_TAG).performScrollToIndex(line)
        composeTestRule.waitForIdle()
    }

    private fun rowNodes(line: Int) = composeTestRule
        .onAllNodes(
            hasText("row-$line") and hasAnyAncestor(hasTestTag(EDITOR_CONTENT_TEST_TAG)),
            useUnmergedTree = true,
        )
        .fetchSemanticsNodes()

    /** The row's text bounds. */
    private fun rowBounds(line: Int): Rect = rowNodes(line).single().boundsInRoot

    private fun Dp.px(): Float = with(composeTestRule.density) { toPx() }

    private fun Float.toDp(): Dp = with(composeTestRule.density) { toDp() }

    /** Distance between the tops of [line] and the row below it. */
    private fun itemHeightAt(line: Int) = rowBounds(line + 1).top - rowBounds(line).top

    /** Visible region in root coordinates as (top, bottom), for the current [bottomPadding]. */
    private fun visibleRegion(): Pair<Float, Float> {
        val viewport = composeTestRule.onNodeWithTag(EDITOR_CONTENT_TEST_TAG).fetchSemanticsNode().boundsInRoot
        return (viewport.top + TOP_PADDING.px()) to (viewport.bottom - bottomPadding.px())
    }

    private fun isFullyVisible(line: Int): Boolean {
        if (rowNodes(line).isEmpty()) return false
        val (visibleTop, visibleBottom) = visibleRegion()
        val row = rowBounds(line)
        return row.top >= visibleTop - 1f && row.bottom <= visibleBottom + 1f
    }

    /** Last row whose text lies fully inside the visible region. */
    private fun lastFullyVisibleRow(): Int = (TOTAL_LINES - 1 downTo 0).first { isFullyVisible(it) }

    /** Grows the bottom padding so it covers the lower half of [line]'s text. */
    private fun coverRow(line: Int) {
        val (_, visibleBottom) = visibleRegion()
        val row = rowBounds(line)
        bottomPadding += (visibleBottom - row.center.y).toDp()
    }

    private fun assertCentred(line: Int, rowHeight: Float) {
        val (visibleTop, visibleBottom) = visibleRegion()
        rowBounds(line).center.y shouldBe ((visibleTop + visibleBottom) / 2f plusOrMinus rowHeight)
    }

    @Test
    fun `padding growing over the caret right after its move reveals it`() {
        setEditor()
        scrollToLine(400)
        val caretRow = lastFullyVisibleRow()
        moveCursor(caretRow)
        isFullyVisible(caretRow).shouldBeTrue()

        coverRow(caretRow)
        composeTestRule.waitForIdle()

        isFullyVisible(caretRow).shouldBeTrue()
    }

    @Test
    fun `padding shrinking under a visible caret does not scroll`() {
        setEditor()
        scrollToLine(400)
        val caretRow = lastFullyVisibleRow()
        moveCursor(caretRow)
        val referenceBefore = rowBounds(400).top

        bottomPadding = 100.dp
        composeTestRule.waitForIdle()

        rowBounds(400).top shouldBe referenceBefore
    }

    @Test
    fun `padding growing over the caret after a drag does not reveal it`() {
        setEditor()
        scrollToLine(400)
        val caretRow = lastFullyVisibleRow()
        moveCursor(caretRow)
        val rowHeight = itemHeightAt(caretRow - 1)

        composeTestRule.onNodeWithTag(EDITOR_CONTENT_TEST_TAG).performTouchInput {
            swipe(center, center - Offset(0f, rowHeight), durationMillis = 200)
        }
        composeTestRule.waitForIdle()
        isFullyVisible(caretRow).shouldBeTrue()

        coverRow(caretRow)
        composeTestRule.waitForIdle()

        isFullyVisible(caretRow).shouldBeFalse()
    }

    @Test
    fun `padding growing over the caret long after its move does not reveal it`() {
        setEditor()
        scrollToLine(400)
        val caretRow = lastFullyVisibleRow()
        moveCursor(caretRow)

        composeTestRule.mainClock.advanceTimeBy(1_600L)
        composeTestRule.waitForIdle()
        coverRow(caretRow)
        composeTestRule.waitForIdle()

        isFullyVisible(caretRow).shouldBeFalse()
    }

    @Test
    fun `jump made while the paddings fill the viewport is centred once they shrink`() {
        setEditor()
        val rowHeight = itemHeightAt(0)
        bottomPadding = 3000.dp
        composeTestRule.waitForIdle()

        cursor = TextPosition(0L, 600L, 0)
        scrollTrigger += 1
        composeTestRule.waitForIdle()

        bottomPadding = BOTTOM_PADDING
        composeTestRule.waitForIdle()

        assertCentred(600, rowHeight)
    }

    @Test
    fun `jump made while the paddings fill the viewport is centred when they shrink right after it`() {
        setEditor()
        val rowHeight = itemHeightAt(0)
        bottomPadding = 3000.dp
        composeTestRule.waitForIdle()

        composeTestRule.mainClock.autoAdvance = false
        cursor = TextPosition(0L, 600L, 0)
        scrollTrigger += 1
        composeTestRule.mainClock.advanceTimeByFrame()
        bottomPadding = BOTTOM_PADDING
        composeTestRule.mainClock.autoAdvance = true
        composeTestRule.waitForIdle()

        assertCentred(600, rowHeight)
    }

    companion object {
        private const val TOTAL_LINES = 1000
        private val TOP_PADDING = 120.dp
        private val BOTTOM_PADDING = 220.dp
    }
}
