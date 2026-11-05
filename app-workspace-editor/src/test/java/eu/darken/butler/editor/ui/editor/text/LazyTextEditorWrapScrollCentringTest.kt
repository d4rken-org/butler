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
import androidx.compose.ui.unit.dp
import eu.darken.butler.common.compose.PreviewWrapper
import eu.darken.butler.editor.core.engine.EditorEngine
import eu.darken.butler.editor.core.engine.TextPosition
import io.kotest.matchers.floats.plusOrMinus
import io.kotest.matchers.floats.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CompletableDeferred
import org.junit.Test
import org.robolectric.annotation.GraphicsMode
import testhelpers.ComposeTest
import kotlin.uuid.Uuid

/**
 * A wrapped line taller than the visible region is centred on the caret's visual line, not on the
 * whole line. NATIVE graphics so the line really wraps.
 */
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LazyTextEditorWrapScrollCentringTest : ComposeTest() {

    private val engineEpoch = Uuid.random()
    private var cursor by mutableStateOf(TextPosition(0L, 0L, 0))
    private var scrollTrigger by mutableIntStateOf(0)

    private val longLine = "0123456789".repeat(300)
    private val lines = (0 until TOTAL_LINES).map { if (it == LONG_LINE) longLine else "row-$it" }

    private fun setEditor() {
        composeTestRule.setContent {
            PreviewWrapper {
                LazyTextEditor(
                    contentPadding = PaddingValues(top = TOP_PADDING, bottom = BOTTOM_PADDING),
                    content = lines.joinToString("\n"),
                    totalLines = TOTAL_LINES.toLong(),
                    cursorPosition = cursor,
                    selection = null,
                    windowToken = EditorEngine.DocumentToken(engineEpoch, 0L),
                    wordWrap = true,
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

    private fun bounds(text: String): Rect = composeTestRule
        .onNode(hasText(text) and hasAnyAncestor(hasTestTag(EDITOR_CONTENT_TEST_TAG)), useUnmergedTree = true)
        .fetchSemanticsNode()
        .boundsInRoot

    private fun visibleRegion(): Pair<Float, Float> {
        val viewport = composeTestRule.onNodeWithTag(EDITOR_CONTENT_TEST_TAG).fetchSemanticsNode().boundsInRoot
        return with(composeTestRule.density) {
            (viewport.top + TOP_PADDING.toPx()) to (viewport.bottom - BOTTOM_PADDING.toPx())
        }
    }

    /** Height of one visual line, taken from an unwrapped row that is on screen at the start. */
    private fun visualLineHeight(): Float = bounds("row-0").height

    private fun triggerTo(column: Int) {
        composeTestRule.onNodeWithTag(EDITOR_CONTENT_TEST_TAG).performScrollToIndex(0)
        composeTestRule.waitForIdle()
        cursor = TextPosition(0L, LONG_LINE.toLong(), column)
        scrollTrigger += 1
        composeTestRule.waitForIdle()
    }

    private fun assertCaretLineCentred(caretTop: Float, lineHeight: Float) {
        val (visibleTop, visibleBottom) = visibleRegion()
        (caretTop + lineHeight / 2f) shouldBe ((visibleTop + visibleBottom) / 2f plusOrMinus lineHeight)
    }

    @Test
    fun `caret at the start of a tall wrapped line is centred`() {
        setEditor()
        val lineHeight = visualLineHeight()

        triggerTo(0)

        val row = bounds(longLine)
        val (visibleTop, visibleBottom) = visibleRegion()
        row.height shouldBeGreaterThan visibleBottom - visibleTop
        assertCaretLineCentred(caretTop = row.top, lineHeight = lineHeight)
    }

    @Test
    fun `caret at the end of a tall wrapped line is centred`() {
        setEditor()
        val lineHeight = visualLineHeight()

        triggerTo(longLine.length)

        val row = bounds(longLine)
        assertCaretLineCentred(caretTop = row.bottom - lineHeight, lineHeight = lineHeight)
    }

    companion object {
        private const val TOTAL_LINES = 60
        private const val LONG_LINE = 30
        private val TOP_PADDING = 120.dp
        private val BOTTOM_PADDING = 220.dp
    }
}
