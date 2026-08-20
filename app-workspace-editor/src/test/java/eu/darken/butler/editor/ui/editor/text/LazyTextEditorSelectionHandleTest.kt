package eu.darken.butler.editor.ui.editor.text

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.requestFocus
import eu.darken.butler.common.compose.PreviewWrapper
import eu.darken.butler.editor.core.engine.EditorEngine
import eu.darken.butler.editor.core.engine.TextPosition
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CompletableDeferred
import org.junit.Test
import testhelpers.ComposeTest
import kotlin.uuid.Uuid

/**
 * Dragging one selection handle PAST the other one, through the real editor gesture stack.
 *
 * The handle that is NOT being dragged is the gesture's anchor and must stay where it was when the
 * finger went down - for the whole gesture, crossover included. Deriving that anchor from the LIVE
 * selection instead makes it follow the finger once the endpoints swap order: the pair is ordered,
 * so after the crossover the moving endpoint occupies the slot the anchor used to sit in, and every
 * further event selects "finger to previous finger" - a collapsing selection that drags the other
 * handle along with it.
 *
 * Two Robolectric properties shape the fixture:
 * - Per-glyph geometry is degenerate. A line measures 1px per character, but `getBoundingBox` maps
 *   EVERY column past the first onto the line's full width, so both handles of a same-line
 *   selection land on the same pixel and only the later-composed one is reachable. The endpoints
 *   therefore sit on DIFFERENT LINES, which separates them vertically.
 * - `graphicsLayer` translations reach hit testing but not `boundsInRoot`, which keeps reporting
 *   the handle's untranslated layout box at the origin. Injection-scope coordinates are therefore
 *   already root coordinates, and the drag is expressed in them.
 *
 * Compose does not recompose between calls inside one `performTouchInput` block, so the drag is
 * split across several blocks: only then does each event see the selection the previous produced.
 */
class LazyTextEditorSelectionHandleTest : ComposeTest() {

    private val engineEpoch = Uuid.random()

    private val firstLine = "0123456789".repeat(12)
    private val secondLine = "abcdefghij".repeat(12)

    /** Column 10 on line 0, the anchor of the end-handle drag. */
    private val selectionStart = createUiTextPosition(line = 0L, column = 10)

    /** Column 60 on line 1, the anchor of the start-handle drag. */
    private val selectionEnd = createUiTextPosition(line = 1L, column = 60)

    /**
     * Both handles render at the line's full measured width (see the degenerate `getBoundingBox`
     * above), offset by the content padding and half the handle: `8 + width - 12`.
     */
    private val handleLeft = 8f + firstLine.length - 12f
    private val handleCenterX = handleLeft + 12f

    /**
     * Editor host that feeds every selection back into itself the way the engine does: the pair is
     * stored verbatim and the caret follows its second element ([EditorEngine.setSelection]).
     */
    private fun ComposeContentTestRule.setSelectionEditor(
        emitted: MutableList<Pair<TextPosition, TextPosition>>,
    ) {
        setContent {
            PreviewWrapper {
                var selection by remember { mutableStateOf(selectionStart to selectionEnd) }
                var cursor by remember { mutableStateOf(selection.second) }
                LazyTextEditor(
                    content = "$firstLine\n$secondLine",
                    totalLines = 2L,
                    cursorPosition = cursor,
                    selection = selection,
                    visibleRange = 0L..1L,
                    windowToken = EditorEngine.DocumentToken(engineEpoch, 0L),
                    showLineNumbers = false,
                    onEnqueueDelta = {
                        CompletableDeferred(
                            EditorEngine.MutationResult.Applied(EditorEngine.DocumentToken(engineEpoch, 0L)),
                        )
                    },
                    onCursorPositionChange = { cursor = it },
                    onSelectionChange = { pair ->
                        if (pair != null) {
                            emitted += pair
                            selection = pair
                            cursor = pair.second
                        }
                    },
                    onVisibleRangeChange = {},
                    onCursorMove = { _, _ -> },
                    onForwardDelete = {},
                )
            }
        }
    }

    /** Y inside the handle sitting on the second line, read off the rendered line itself. */
    private fun secondLineY(): Float =
        composeTestRule.onAllNodesWithText(secondLine)[0].fetchSemanticsNode().boundsInRoot.top + 8f

    /** One step per block, so every event sees the selection its predecessor produced. */
    private fun dragHandle(tag: String, from: Offset, steps: List<Offset>) {
        composeTestRule.onNodeWithTag(tag).performTouchInput { down(from) }
        composeTestRule.waitForIdle()
        steps.forEach { target ->
            composeTestRule.onNodeWithTag(tag).performTouchInput { moveTo(target) }
            composeTestRule.waitForIdle()
        }
        composeTestRule.onNodeWithTag(tag).performTouchInput { up() }
        composeTestRule.waitForIdle()
    }

    private fun Pair<TextPosition, TextPosition>.holds(position: TextPosition) =
        listOf(first, second).any { it.line == position.line && it.column == position.column }

    private fun Pair<TextPosition, TextPosition>.otherThan(position: TextPosition) =
        if (first.line == position.line && first.column == position.column) second else first

    private fun TextPosition.isBefore(other: TextPosition) =
        line < other.line || (line == other.line && column < other.column)

    @Test
    fun `dragging the end handle past the start keeps the start anchored`() {
        val emitted = mutableListOf<Pair<TextPosition, TextPosition>>()
        composeTestRule.setSelectionEditor(emitted)
        composeTestRule.onNodeWithTag(EDITOR_INPUT_TEST_TAG).requestFocus()
        composeTestRule.waitForIdle()

        val secondLineY = secondLineY()
        dragHandle(
            tag = EDITOR_SELECTION_HANDLE_END_TEST_TAG,
            from = Offset(handleCenterX, secondLineY),
            steps = listOf(
                // Past the touch slop, still to the right of the anchor
                Offset(200f, secondLineY),
                // Up onto the first line and left of the anchor: the crossover
                Offset(20f, 12f),
                Offset(30f, 12f),
                Offset(40f, 12f),
            ),
        )

        withClue(emitted) {
            (emitted.size >= 3) shouldBe true
            emitted.all { it.holds(selectionStart) } shouldBe true
            // The drag really did cross over, otherwise the anchor assertion above is vacuous
            emitted.last().otherThan(selectionStart).isBefore(selectionStart) shouldBe true
        }
    }

    @Test
    fun `dragging the start handle past the end keeps the end anchored`() {
        val emitted = mutableListOf<Pair<TextPosition, TextPosition>>()
        composeTestRule.setSelectionEditor(emitted)
        composeTestRule.onNodeWithTag(EDITOR_INPUT_TEST_TAG).requestFocus()
        composeTestRule.waitForIdle()

        val secondLineY = secondLineY()
        dragHandle(
            tag = EDITOR_SELECTION_HANDLE_START_TEST_TAG,
            from = Offset(handleCenterX, 12f),
            steps = listOf(
                // Past the touch slop, still above the anchor
                Offset(200f, 12f),
                // Down onto the second line and right of the anchor: the crossover
                Offset(200f, secondLineY),
                Offset(210f, secondLineY),
                Offset(220f, secondLineY),
            ),
        )

        withClue(emitted) {
            (emitted.size >= 3) shouldBe true
            emitted.all { it.holds(selectionEnd) } shouldBe true
            selectionEnd.isBefore(emitted.last().otherThan(selectionEnd)) shouldBe true
        }
    }
}
