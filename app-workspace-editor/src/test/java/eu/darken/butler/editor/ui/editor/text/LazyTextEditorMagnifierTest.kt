package eu.darken.butler.editor.ui.editor.text

import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.TouchInjectionScope
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import eu.darken.butler.common.compose.PreviewWrapper
import eu.darken.butler.editor.core.engine.EditorEngine
import eu.darken.butler.editor.core.engine.TextPosition
import io.kotest.assertions.withClue
import io.kotest.matchers.floats.plusOrMinus
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CompletableDeferred
import org.junit.Test
import testhelpers.ComposeTest
import kotlin.uuid.Uuid

/**
 * The magnifier shown while a selection handle is dragged, through the real editor gesture stack.
 *
 * The fixture follows [LazyTextEditorSelectionHandleTest]: selection endpoints sit on DIFFERENT
 * lines because per-glyph geometry is degenerate under Robolectric, and touches are injected in
 * root coordinates because `graphicsLayer` translations reach hit testing but not `boundsInRoot`.
 * Every step runs in its own `performTouchInput` block so each event sees the recomposed handles.
 *
 * The editor fills the root, so its container coordinates are root coordinates, which is the space
 * foundation publishes the magnifier's source in.
 */
class LazyTextEditorMagnifierTest : ComposeTest() {

    private val engineEpoch = Uuid.random()

    private val lines = listOf(
        "0123456789".repeat(12),
        "abcdefghij".repeat(12),
        "ABCDEFGHIJ".repeat(12),
    ) + (3 until 60).map { "line $it" }

    private val initialSelection = createUiTextPosition(line = 1L, column = 10) to
        createUiTextPosition(line = 2L, column = 60)

    private var topPaddingPx by mutableFloatStateOf(40f)
    private var selection by mutableStateOf<Pair<TextPosition, TextPosition>?>(initialSelection)
    private var cursor by mutableStateOf(initialSelection.second)

    /** Takes focus off the editor: under Robolectric, clearFocus() leaves the editor focused. */
    private val elsewhere = FocusRequester()

    /** Both handles sit at the full measured line width (degenerate `getBoundingBox`). */
    private val handleCenterX = 8f + lines[1].length

    private fun setEditor() {
        composeTestRule.setContent {
            PreviewWrapper {
                Box {
                    LazyTextEditor(
                        contentPadding = PaddingValues(top = with(LocalDensity.current) { topPaddingPx.toDp() }),
                        content = lines.joinToString("\n"),
                        totalLines = lines.size.toLong(),
                        cursorPosition = cursor,
                        selection = selection,
                        visibleRange = 0L until lines.size.toLong(),
                        windowToken = EditorEngine.DocumentToken(engineEpoch, 0L),
                        showLineNumbers = false,
                        onEnqueueDelta = {
                            CompletableDeferred(
                                EditorEngine.MutationResult.Applied(EditorEngine.DocumentToken(engineEpoch, 0L)),
                            )
                        },
                        onCursorPositionChange = { cursor = it },
                        onSelectionChange = { pair ->
                            selection = pair
                            if (pair != null) cursor = pair.second
                        },
                        onVisibleRangeChange = {},
                        onCursorMove = { _, _ -> },
                        onForwardDelete = {},
                    )
                    Box(modifier = Modifier.size(1.dp).focusRequester(elsewhere).focusable())
                }
            }
        }
        focusEditor()
    }

    private fun focusEditor() {
        composeTestRule.onNodeWithTag(EDITOR_INPUT_TEST_TAG).requestFocus()
        composeTestRule.waitForIdle()
    }

    /** Touch Y inside the handle drawn on [line]: handles hang from the top of their line item. */
    private fun handleY(line: Int): Float = textBoundsTop(line) + 8f

    private fun textBoundsTop(line: Int): Float =
        composeTestRule.onAllNodesWithText(lines[line])[0].fetchSemanticsNode().boundsInRoot.top

    /**
     * Where the magnifier has to point for [position]: the rendered text's own root position plus
     * the caret's x and the vertical centre of its visual line in that text's layout.
     */
    private fun expectedSource(position: TextPosition): Offset {
        val line = lines[position.line.toInt()]
        val node = composeTestRule.onAllNodesWithText(line)[0].fetchSemanticsNode()
        val layouts = mutableListOf<TextLayoutResult>()
        node.config[SemanticsActions.GetTextLayoutResult].action!!.invoke(layouts)
        val caretLine = caretGeometry(layouts.first(), line, position.column, tabSize = 4).measuredLine!!
        return node.boundsInRoot.topLeft + Offset(caretLine.x, (caretLine.top + caretLine.bottom) / 2f)
    }

    /**
     * The magnifier node publishes its source through foundation's internal
     * `MagnifierPositionInRoot` semantics property, so it is looked up by the key's name.
     */
    private fun magnifierSource(): Offset {
        val hasMagnifier = SemanticsMatcher("has $MAGNIFIER_KEY") { node ->
            node.config.any { it.key.name == MAGNIFIER_KEY }
        }
        val node = composeTestRule.onNode(hasMagnifier, useUnmergedTree = true).fetchSemanticsNode()

        @Suppress("UNCHECKED_CAST")
        val position = node.config.first { it.key.name == MAGNIFIER_KEY }.value as () -> Offset
        return position()
    }

    private fun assertMagnifies(position: TextPosition) {
        val actual = magnifierSource()
        val expected = expectedSource(position)
        withClue("magnifier at $actual, expected $expected for $position") {
            actual.isSpecified shouldBe true
            actual.x shouldBe (expected.x plusOrMinus 0.5f)
            actual.y shouldBe (expected.y plusOrMinus 0.5f)
        }
    }

    private fun assertHidden() {
        val actual = magnifierSource()
        withClue("magnifier at $actual") { actual.isSpecified shouldBe false }
    }

    private fun touch(block: TouchInjectionScope.() -> Unit) {
        composeTestRule.onRoot().performTouchInput(block)
        composeTestRule.waitForIdle()
    }

    /**
     * Presses the end handle and drags it along its line, past the touch slop. The handle is
     * centred on its caret, which an earlier drag may have moved off the line's end.
     */
    private fun startEndHandleDrag(): TextPosition {
        val x = expectedSource(selection!!.second).x
        val y = handleY(2)
        touch { down(Offset(x, y)) }
        touch { moveTo(Offset(60f, y)) }
        touch { moveTo(Offset(50f, y)) }
        return selection!!.second
    }

    @Test
    fun `magnifies the moving position during a drag, following the content padding`() {
        setEditor()

        val moving = startEndHandleDrag()
        withClue("the drag moved the end") { moving.line shouldBe 2L }
        assertMagnifies(moving)
        touch { up() }

        topPaddingPx = 96f
        composeTestRule.waitForIdle()

        val movedAgain = startEndHandleDrag()
        assertMagnifies(movedAgain)
        touch { up() }
    }

    @Test
    fun `follows the moving position across a crossover`() {
        setEditor()

        val anchor = selection!!.first
        val y = handleY(2)
        touch { down(Offset(handleCenterX, y)) }
        touch { moveTo(Offset(60f, y)) }
        // Up onto line 0, above the start anchor on line 1: the end handle's finger now moves the start
        touch { moveTo(Offset(40f, handleY(0))) }
        touch { moveTo(Offset(30f, handleY(0))) }

        val (movedStart, end) = selection!!
        withClue("crossed over: $movedStart..$end") {
            movedStart.line shouldBe 0L
            end shouldBe anchor
        }
        assertMagnifies(movedStart)
        touch { up() }
    }

    @Test
    fun `hides once the finger lifts`() {
        setEditor()

        assertMagnifies(startEndHandleDrag())
        touch { up() }

        assertHidden()
    }

    @Test
    fun `hides once the gesture is cancelled`() {
        setEditor()

        assertMagnifies(startEndHandleDrag())
        touch { cancel() }

        assertHidden()
    }

    @Test
    fun `hides when focus loss removes the handles mid-drag, and stays hidden once they return`() {
        setEditor()

        assertMagnifies(startEndHandleDrag())
        composeTestRule.runOnIdle { elsewhere.requestFocus() }
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag(EDITOR_SELECTION_HANDLE_END_TEST_TAG).assertDoesNotExist()
        assertHidden()

        touch { up() }
        assertHidden()

        // The lift never reached the removed handle, so the drag must have ended with its removal
        focusEditor()
        assertHidden()
    }

    @Test
    fun `hides when a cleared selection removes the handles mid-drag, and stays hidden once they return`() {
        setEditor()

        val moving = startEndHandleDrag()
        assertMagnifies(moving)
        val restored = selection
        composeTestRule.runOnIdle { selection = null }
        composeTestRule.waitForIdle()
        assertHidden()

        touch { up() }
        assertHidden()

        composeTestRule.runOnIdle { selection = restored }
        composeTestRule.waitForIdle()
        assertHidden()
    }

    @Test
    fun `two drags - follows the one that moved last, hands over on release, hides when both end`() {
        setEditor()

        val startY = handleY(1)
        val endY = handleY(2)
        touch {
            down(0, Offset(handleCenterX, startY))
            down(1, Offset(handleCenterX, endY))
        }
        touch { moveTo(0, Offset(60f, startY)) }
        touch { moveTo(1, Offset(60f, endY)) }
        val endMoving = selection!!.second
        withClue("the end finger moved last") { endMoving.line shouldBe 2L }
        assertMagnifies(endMoving)

        touch { moveTo(0, Offset(50f, startY)) }
        val startMoving = selection!!.first
        withClue("the start finger moved last") { startMoving.line shouldBe 1L }
        assertMagnifies(startMoving)

        // No further move: the end finger's drag takes over right away, at its last position
        touch { up(0) }
        assertMagnifies(endMoving)

        touch { up(1) }
        assertHidden()
    }

    @Test
    fun `hides when the moving line scrolls out of view during the drag`() {
        setEditor()

        assertMagnifies(startEndHandleDrag())

        // The editor centres the caret's line when the caret leaves the viewport
        composeTestRule.runOnIdle { cursor = createUiTextPosition(line = 50L, column = 0) }
        composeTestRule.waitForIdle()
        withClue("line 2 scrolled away") {
            composeTestRule.onAllNodesWithText(lines[2]).fetchSemanticsNodes().size shouldBe 0
        }

        assertHidden()
        touch { up() }
    }

    @Test
    fun `stays hidden once the dragged handle's line scrolls out and back in during the drag`() {
        setEditor()

        assertMagnifies(startEndHandleDrag())

        composeTestRule.runOnIdle { cursor = createUiTextPosition(line = 50L, column = 0) }
        composeTestRule.waitForIdle()
        withClue("line 2 scrolled away") {
            composeTestRule.onAllNodesWithText(lines[2]).fetchSemanticsNodes().size shouldBe 0
        }

        composeTestRule.runOnIdle { cursor = createUiTextPosition(line = 2L, column = 0) }
        composeTestRule.waitForIdle()
        withClue("line 2 is back") {
            composeTestRule.onAllNodesWithText(lines[2]).fetchSemanticsNodes().isNotEmpty() shouldBe true
        }
        composeTestRule.onNodeWithTag(EDITOR_SELECTION_HANDLE_END_TEST_TAG).assertExists()

        // The finger is still down, but its gesture died with the handle it started on
        assertHidden()
        touch { up() }
        assertHidden()
    }

    private companion object {
        const val MAGNIFIER_KEY = "MagnifierPositionInRoot"
    }
}
