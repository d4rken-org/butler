package eu.darken.butler.editor.ui.editor.text

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import eu.darken.butler.common.compose.PreviewWrapper
import eu.darken.butler.editor.core.engine.EditorEngine
import eu.darken.butler.editor.core.engine.TextPosition
import io.kotest.matchers.floats.plusOrMinus
import io.kotest.matchers.floats.shouldBeGreaterThanOrEqual
import io.kotest.matchers.floats.shouldBeLessThanOrEqual
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CompletableDeferred
import org.junit.Test
import testhelpers.ComposeTest
import kotlin.uuid.Uuid

/**
 * Scrolling to the caret centres its line in the part of the editor the user can see: below the top
 * content padding and above the bottom one, which the floating bars cover.
 */
class LazyTextEditorScrollCentringTest : ComposeTest() {

    private val engineEpoch = Uuid.random()
    private var cursorLine by mutableStateOf(0L)
    private var scrollTrigger by mutableIntStateOf(0)

    private fun setEditor(top: Dp = TOP_PADDING, bottom: Dp = BOTTOM_PADDING) {
        composeTestRule.setContent {
            PreviewWrapper {
                LazyTextEditor(
                    contentPadding = PaddingValues(top = top, bottom = bottom),
                    content = (0 until TOTAL_LINES).joinToString("\n") { "row-$it" },
                    totalLines = TOTAL_LINES.toLong(),
                    cursorPosition = TextPosition(0L, cursorLine, 0),
                    selection = null,
                    windowToken = EditorEngine.DocumentToken(engineEpoch, 0L),
                    scrollTrigger = scrollTrigger,
                    onEnqueueDelta = {
                        CompletableDeferred(
                            EditorEngine.MutationResult.Applied(EditorEngine.DocumentToken(engineEpoch, 0L)),
                        )
                    },
                    onCursorPositionChange = {},
                    onSelectionChange = {},
                    onVisibleRangeChange = {},
                    onCursorMove = { _, _ -> },
                    onForwardDelete = {},
                )
            }
        }
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

    private fun Dp.px(): Float = with(composeTestRule.density) { toPx() }

    /** Visible region in root coordinates as (top, bottom). */
    private fun visibleRegion(top: Dp = TOP_PADDING, bottom: Dp = BOTTOM_PADDING): Pair<Float, Float> {
        val viewport = composeTestRule.onNodeWithTag(EDITOR_CONTENT_TEST_TAG).fetchSemanticsNode().boundsInRoot
        return (viewport.top + top.px()) to (viewport.bottom - bottom.px())
    }

    private fun scrollAwayAndTrigger(line: Long, scrollTo: Int = 0) {
        composeTestRule.onNodeWithTag(EDITOR_CONTENT_TEST_TAG).performScrollToIndex(scrollTo)
        composeTestRule.waitForIdle()
        cursorLine = line
        scrollTrigger += 1
        composeTestRule.waitForIdle()
    }

    private fun assertCentred(line: Int, top: Dp = TOP_PADDING, bottom: Dp = BOTTOM_PADDING) {
        val (visibleTop, visibleBottom) = visibleRegion(top, bottom)
        val row = rowBounds(line)
        val rowHeight = row.height + 2 * ROW_INSET.px()
        row.center.y shouldBe ((visibleTop + visibleBottom) / 2f plusOrMinus rowHeight)
    }

    @Test
    fun `trigger centres the caret line in the region between the paddings`() {
        cursorLine = 400L
        setEditor()

        scrollAwayAndTrigger(400L)
        rowNodes(400).size shouldBe 1

        assertCentred(400)
    }

    @Test
    fun `caret moving off screen centres its line in the region between the paddings`() {
        cursorLine = 2L
        setEditor()
        rowNodes(2).size shouldBe 1

        cursorLine = 600L
        composeTestRule.waitForIdle()

        assertCentred(600)
    }

    @Test
    fun `without padding the caret line is centred on the viewport`() {
        cursorLine = 400L
        setEditor(top = 0.dp, bottom = 0.dp)

        scrollAwayAndTrigger(400L)

        assertCentred(400, top = 0.dp, bottom = 0.dp)
    }

    @Test
    fun `lines at the document end stay above the bottom padding`() {
        setEditor()
        val (visibleTop, visibleBottom) = visibleRegion()

        (TOTAL_LINES - 4 until TOTAL_LINES).forEach { line ->
            scrollAwayAndTrigger(line.toLong())

            val row = rowBounds(line)
            row.top - ROW_INSET.px() shouldBeGreaterThanOrEqual visibleTop - 1f
            row.bottom + ROW_INSET.px() shouldBeLessThanOrEqual visibleBottom + 1f
        }
    }

    @Test
    fun `the first line sits at the top of the visible region`() {
        setEditor()

        scrollAwayAndTrigger(0L, scrollTo = 500)

        val (visibleTop, _) = visibleRegion()
        rowBounds(0).top - ROW_INSET.px() shouldBe (visibleTop plusOrMinus 1f)
    }

    @Test
    fun `padding that fills the viewport still composes the target line`() {
        setEditor(top = 300.dp, bottom = 300.dp)

        scrollAwayAndTrigger(400L)

        rowNodes(400).size shouldBe 1
    }

    companion object {
        private const val TOTAL_LINES = 1000
        private val TOP_PADDING = 120.dp
        private val BOTTOM_PADDING = 220.dp
        private val ROW_INSET = 2.dp
    }
}
