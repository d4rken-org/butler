package eu.darken.butler.editor.ui.editor.text

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.LayoutInfo
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.unit.dp
import eu.darken.butler.common.compose.PreviewWrapper
import eu.darken.butler.editor.core.engine.EditorEngine
import eu.darken.butler.editor.core.engine.TextPosition
import io.kotest.matchers.floats.plusOrMinus
import io.kotest.matchers.floats.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlinx.coroutines.CompletableDeferred
import org.junit.Test
import org.robolectric.annotation.GraphicsMode
import testhelpers.ComposeTest
import kotlin.math.abs
import kotlin.uuid.Uuid

/**
 * A jump into a region whose lines are not loaded yet centres against one-row placeholders. With
 * word wrap on, the loaded lines are several rows tall, so the caret line has to be centred again
 * once they arrive, unless the user scrolled in the meantime. NATIVE graphics so lines really wrap.
 */
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LazyTextEditorWrapSettleTest : ComposeTest() {

    private val engineEpoch = Uuid.random()
    private var lines: List<String> = (0 until TOTAL_LINES).map(::wrappedLine)
    private var cursor by mutableStateOf(TextPosition(0L, 0L, 0))
    private var scrollTrigger by mutableIntStateOf(0)
    private var windowStart by mutableLongStateOf(0L)
    private var windowEnd by mutableLongStateOf(INITIAL_WINDOW_END)
    private var windowVersion by mutableLongStateOf(0L)

    private fun wrappedLine(n: Int) = "[$n] " + "lorem ipsum dolor sit amet ".repeat(8)

    private fun setEditor() {
        composeTestRule.setContent {
            PreviewWrapper {
                LazyTextEditor(
                    contentPadding = PaddingValues(top = TOP_PADDING, bottom = BOTTOM_PADDING),
                    content = lines.subList(windowStart.toInt(), windowEnd.toInt() + 1).joinToString("\n"),
                    totalLines = TOTAL_LINES.toLong(),
                    cursorPosition = cursor,
                    selection = null,
                    windowToken = EditorEngine.DocumentToken(engineEpoch, windowVersion),
                    windowRangeStart = windowStart,
                    wordWrap = true,
                    scrollTrigger = scrollTrigger,
                    onEnqueueDelta = {
                        CompletableDeferred(
                            EditorEngine.MutationResult.Applied(EditorEngine.DocumentToken(engineEpoch, windowVersion)),
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

    private fun triggerTo(line: Int) {
        cursor = TextPosition(0L, line.toLong(), 0)
        scrollTrigger += 1
        composeTestRule.waitForIdle()
    }

    private fun publishWindow(start: Int, end: Int) {
        windowStart = start.toLong()
        windowEnd = end.toLong()
        windowVersion += 1
        composeTestRule.waitForIdle()
    }

    private fun SemanticsNode.isPlacedInTree(): Boolean {
        var info: LayoutInfo? = layoutInfo
        while (info != null) {
            if (!info.isPlaced) return false
            info = info.parentInfo
        }
        return true
    }

    private fun gutterLabelNode(line: Int) = composeTestRule.onNode(
        hasText((line + 1).toString()) and !hasAnyAncestor(hasTestTag(EDITOR_CONTENT_TEST_TAG)),
        useUnmergedTree = true,
    )

    /** Height of one visual line: a gutter label is a single line in the editor's text style. */
    private fun visualLineHeight(): Float = gutterLabelNode(0).fetchSemanticsNode().boundsInRoot.height

    private fun visibleCentre(): Float {
        val viewport = composeTestRule.onNodeWithTag(EDITOR_CONTENT_TEST_TAG).fetchSemanticsNode().boundsInRoot
        return with(composeTestRule.density) {
            ((viewport.top + TOP_PADDING.toPx()) + (viewport.bottom - BOTTOM_PADDING.toPx())) / 2f
        }
    }

    /** Top of the line's text, i.e. of its first visual line, or null when it is not laid out. */
    private fun textTop(line: Int): Float? = composeTestRule
        .onAllNodes(hasText(lines[line]) and hasAnyAncestor(hasTestTag(EDITOR_CONTENT_TEST_TAG)), useUnmergedTree = true)
        .fetchSemanticsNodes()
        .singleOrNull { it.isPlacedInTree() }
        ?.positionInRoot
        ?.y

    private fun assertCaretOnFirstVisualLineCentred(line: Int, lineHeight: Float) {
        val top = textTop(line)
        top shouldNotBe null
        (top!! + lineHeight / 2f) shouldBe (visibleCentre() plusOrMinus lineHeight)
    }

    private fun assertCaretLineFarFromCentre(line: Int, lineHeight: Float) {
        val top = textTop(line) ?: return
        abs(top + lineHeight / 2f - visibleCentre()) shouldBeGreaterThan FAR_FROM_CENTRE_LINES * lineHeight
    }

    @Test
    fun `a jump into unloaded lines is centred again once the wrapped lines arrive`() {
        setEditor()
        val lineHeight = visualLineHeight()

        triggerTo(TARGET)
        publishWindow(TARGET - 40, TARGET + 60)

        assertCaretOnFirstVisualLineCentred(TARGET, lineHeight)
    }

    @Test
    fun `a jump stays centred when a tall line loaded above pushes the target out of the layout`() {
        lines = lines.toMutableList().apply { this[TARGET - 4] = "[${TARGET - 4}] " + "x".repeat(3000) }
        setEditor()
        val lineHeight = visualLineHeight()

        triggerTo(TARGET)
        publishWindow(TARGET - 40, TARGET + 60)

        assertCaretOnFirstVisualLineCentred(TARGET, lineHeight)
    }

    @Test
    fun `a content swipe before the lines arrive stops the jump from correcting the view`() {
        setEditor()
        val lineHeight = visualLineHeight()
        triggerTo(TARGET)

        composeTestRule.onNodeWithTag(EDITOR_CONTENT_TEST_TAG).performTouchInput { swipeUp() }
        composeTestRule.waitForIdle()

        publishWindow(TARGET - 40, TARGET + 60)

        assertCaretLineFarFromCentre(TARGET, lineHeight)
    }

    @Test
    fun `a gutter swipe before the lines arrive stops the jump from correcting the view`() {
        setEditor()
        val lineHeight = visualLineHeight()
        triggerTo(TARGET)

        gutterLabelNode(TARGET).performTouchInput {
            swipe(center, Offset(centerX, center.y - 300.dp.toPx()), durationMillis = 200)
        }
        composeTestRule.waitForIdle()

        publishWindow(TARGET - 40, TARGET + 60)

        assertCaretLineFarFromCentre(TARGET, lineHeight)
    }

    @Test
    fun `a second jump while the first is settling ends centred on the second target`() {
        setEditor()
        val lineHeight = visualLineHeight()

        triggerTo(TARGET)
        triggerTo(SECOND_TARGET)
        publishWindow(TARGET - 40, SECOND_TARGET + 60)

        assertCaretOnFirstVisualLineCentred(SECOND_TARGET, lineHeight)
    }

    companion object {
        private const val TOTAL_LINES = 1000
        private const val INITIAL_WINDOW_END = 60L
        private const val TARGET = 500
        private const val SECOND_TARGET = 800
        private const val FAR_FROM_CENTRE_LINES = 3
        private val TOP_PADDING = 120.dp
        private val BOTTOM_PADDING = 220.dp
    }
}
