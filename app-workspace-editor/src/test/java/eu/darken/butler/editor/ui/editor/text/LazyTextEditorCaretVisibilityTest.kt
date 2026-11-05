package eu.darken.butler.editor.ui.editor.text

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import eu.darken.butler.common.compose.PreviewWrapper
import eu.darken.butler.editor.core.engine.EditorEngine
import eu.darken.butler.editor.core.engine.TextPosition
import io.kotest.matchers.floats.plusOrMinus
import io.kotest.matchers.floats.shouldBeGreaterThanOrEqual
import io.kotest.matchers.floats.shouldBeLessThan
import io.kotest.matchers.floats.shouldBeLessThanOrEqual
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CompletableDeferred
import org.junit.Test
import testhelpers.ComposeTest
import kotlin.math.abs
import kotlin.uuid.Uuid

/**
 * A caret moved without a scroll trigger (typing, arrow keys, a tap) stays in the region between the
 * content paddings: rows under the floating bars count as hidden. A caret just past an edge is nudged
 * into view, one deeper in a padding or off screen is centred.
 */
class LazyTextEditorCaretVisibilityTest : ComposeTest() {

    private val engineEpoch = Uuid.random()
    private var cursor by mutableStateOf(TextPosition(0L, 0L, 0))

    private fun setEditor() {
        composeTestRule.setContent {
            PreviewWrapper {
                LazyTextEditor(
                    contentPadding = PaddingValues(top = TOP_PADDING, bottom = BOTTOM_PADDING),
                    content = (0 until TOTAL_LINES).joinToString("\n") { "row-$it" },
                    totalLines = TOTAL_LINES.toLong(),
                    cursorPosition = cursor,
                    selection = null,
                    windowToken = EditorEngine.DocumentToken(engineEpoch, 0L),
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

    private fun rowNodes(line: Int) = composeTestRule
        .onAllNodes(
            hasText("row-$line") and hasAnyAncestor(hasTestTag(EDITOR_CONTENT_TEST_TAG)),
            useUnmergedTree = true,
        )
        .fetchSemanticsNodes()

    /** The row's text bounds; the item adds [ROW_INSET] above and below. */
    private fun rowBounds(line: Int): Rect = rowNodes(line).single().boundsInRoot

    private val inset get() = with(composeTestRule.density) { ROW_INSET.toPx() }

    private fun itemTop(line: Int) = rowBounds(line).top - inset

    private val itemHeight get() = itemTop(1) - itemTop(0)

    private fun viewport(): Rect =
        composeTestRule.onNodeWithTag(EDITOR_CONTENT_TEST_TAG).fetchSemanticsNode().boundsInRoot

    /** Visible region in root coordinates as (top, bottom). */
    private fun visibleRegion(): Pair<Float, Float> = with(composeTestRule.density) {
        val viewport = viewport()
        (viewport.top + TOP_PADDING.toPx()) to (viewport.bottom - BOTTOM_PADDING.toPx())
    }

    private fun scrollContentBy(px: Float) {
        composeTestRule.onNodeWithTag(EDITOR_CONTENT_TEST_TAG)
            .performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, px) }
        composeTestRule.waitForIdle()
    }

    /** First row whose item starts at or below the bottom edge of the visible region. */
    private fun firstRowBelowRegion(): Int {
        val (_, visibleBottom) = visibleRegion()
        return (0 until TOTAL_LINES).first { rowNodes(it).isNotEmpty() && itemTop(it) >= visibleBottom - 0.5f }
    }

    /** Scrolls so row [firstRowBelowRegion]'s item starts exactly at the region's bottom edge; returns it. */
    private fun alignRowToBottomEdge(): Int {
        val (_, visibleBottom) = visibleRegion()
        val row = firstRowBelowRegion()
        val overshoot = itemTop(row) - visibleBottom
        if (overshoot > 0.5f) scrollContentBy(overshoot)
        return row
    }

    private fun assertFullyVisible(line: Int) {
        val (visibleTop, visibleBottom) = visibleRegion()
        val row = rowBounds(line)
        row.top shouldBeGreaterThanOrEqual visibleTop - 1f
        row.bottom shouldBeLessThanOrEqual visibleBottom + 1f
    }

    private fun assertCentred(line: Int, rowHeight: Float) {
        val (visibleTop, visibleBottom) = visibleRegion()
        rowBounds(line).center.y shouldBe ((visibleTop + visibleBottom) / 2f plusOrMinus rowHeight)
    }

    @Test
    fun `caret moved onto the row under the bottom padding edge nudges it into view`() {
        setEditor()
        val rowHeight = itemHeight
        val hidden = alignRowToBottomEdge()
        moveCursor(hidden - 1)
        assertFullyVisible(hidden - 1)
        val referenceBefore = rowBounds(hidden - 1).top

        moveCursor(hidden)

        assertFullyVisible(hidden)
        abs(rowBounds(hidden - 1).top - referenceBefore) shouldBeLessThan 2 * rowHeight
    }

    @Test
    fun `caret moved between fully visible rows does not scroll`() {
        setEditor()
        assertFullyVisible(1)
        assertFullyVisible(2)
        moveCursor(1)
        val referenceBefore = rowBounds(0).top

        moveCursor(2)
        moveCursor(1)

        rowBounds(0).top shouldBe referenceBefore
    }

    @Test
    fun `tap on a row straddling the bottom edge brings it fully into view`() {
        setEditor()
        val rowHeight = itemHeight
        val straddling = alignRowToBottomEdge()
        scrollContentBy(rowHeight / 2f)
        val (_, visibleBottom) = visibleRegion()
        itemTop(straddling) shouldBeLessThan visibleBottom
        val referenceBefore = rowBounds(straddling - 1).top

        composeTestRule.onNodeWithTag(EDITOR_CONTENT_TEST_TAG).performTouchInput {
            click(Offset(center.x, visibleBottom - viewport().top - rowHeight / 4f))
        }
        composeTestRule.waitForIdle()

        cursor.line shouldBe straddling.toLong()
        assertFullyVisible(straddling)
        abs(rowBounds(straddling - 1).top - referenceBefore) shouldBeLessThan 2 * rowHeight
    }

    @Test
    fun `tap on a row straddling the top edge brings it fully into view`() {
        setEditor()
        val rowHeight = itemHeight
        scrollContentBy(5 * rowHeight + rowHeight / 2f)
        val (visibleTop, _) = visibleRegion()
        val straddling = 5
        itemTop(straddling) shouldBeLessThan visibleTop
        rowBounds(straddling).bottom + inset shouldBeGreaterThanOrEqual visibleTop
        val referenceBefore = rowBounds(straddling + 1).top

        composeTestRule.onNodeWithTag(EDITOR_CONTENT_TEST_TAG).performTouchInput {
            click(Offset(center.x, visibleTop - viewport().top + rowHeight / 4f))
        }
        composeTestRule.waitForIdle()

        cursor.line shouldBe straddling.toLong()
        assertFullyVisible(straddling)
        abs(rowBounds(straddling + 1).top - referenceBefore) shouldBeLessThan 2 * rowHeight
    }

    @Test
    fun `caret moved deep into the bottom padding centres its row`() {
        setEditor()
        val rowHeight = itemHeight
        val deep = firstRowBelowRegion() + 3
        rowNodes(deep).size shouldBe 1

        moveCursor(deep)

        assertCentred(deep, rowHeight)
    }

    @Test
    fun `caret moved far off screen centres its row`() {
        setEditor()
        val rowHeight = itemHeight

        moveCursor(600)

        assertCentred(600, rowHeight)
    }

    companion object {
        private const val TOTAL_LINES = 1000
        private val TOP_PADDING = 120.dp
        private val BOTTOM_PADDING = 220.dp
        private val ROW_INSET = 2.dp
    }
}
