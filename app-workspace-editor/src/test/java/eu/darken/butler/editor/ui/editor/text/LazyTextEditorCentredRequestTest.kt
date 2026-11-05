package eu.darken.butler.editor.ui.editor.text

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.LayoutInfo
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import eu.darken.butler.common.compose.PreviewWrapper
import eu.darken.butler.editor.core.engine.EditorEngine
import eu.darken.butler.editor.core.engine.TextPosition
import io.kotest.assertions.withClue
import io.kotest.matchers.floats.plusOrMinus
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlinx.coroutines.CompletableDeferred
import org.junit.Test
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import testhelpers.ComposeTest
import kotlin.uuid.Uuid

/** A line the caret scroll centred stays centred while wrapped content loads and the paddings change. */
class LazyTextEditorCentredRequestTest : ComposeTest() {

    private val engineEpoch = Uuid.random()
    private var lines: List<String> = (0 until TOTAL_LINES).map { "row-$it" }
    private var wordWrap = false
    private var cursor by mutableStateOf(TextPosition(0L, 0L, 0))
    private var scrollTrigger by mutableIntStateOf(0)
    private var bottomPadding by mutableStateOf(BOTTOM_PADDING)
    private var windowStart by mutableLongStateOf(0L)
    private var windowEnd by mutableLongStateOf((TOTAL_LINES - 1).toLong())
    private var windowVersion by mutableLongStateOf(0L)

    private fun wrappedLine(n: Int) = "[$n] " + "lorem ipsum dolor sit amet ".repeat(8)

    private fun setEditor() {
        composeTestRule.setContent {
            PreviewWrapper {
                LazyTextEditor(
                    contentPadding = PaddingValues(top = TOP_PADDING, bottom = bottomPadding),
                    content = lines.subList(windowStart.toInt(), windowEnd.toInt() + 1).joinToString("\n"),
                    totalLines = TOTAL_LINES.toLong(),
                    cursorPosition = cursor,
                    selection = null,
                    windowToken = EditorEngine.DocumentToken(engineEpoch, windowVersion),
                    windowRangeStart = windowStart,
                    wordWrap = wordWrap,
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

    private fun publishWindow(start: Int, end: Int) {
        windowStart = start.toLong()
        windowEnd = end.toLong()
        windowVersion += 1
        composeTestRule.waitForIdle()
    }

    private fun Dp.px(): Float = with(composeTestRule.density) { toPx() }

    private fun Float.toDp(): Dp = with(composeTestRule.density) { toDp() }

    private fun viewportBounds(): Rect =
        composeTestRule.onNodeWithTag(EDITOR_CONTENT_TEST_TAG).fetchSemanticsNode().boundsInRoot

    /** Centre of the region between the paddings, for the current [bottomPadding]. */
    private fun visibleCentre(): Float {
        val viewport = viewportBounds()
        return ((viewport.top + TOP_PADDING.px()) + (viewport.bottom - bottomPadding.px())) / 2f
    }

    // Wrap-off helpers (row-N lines), as in LazyTextEditorPaddingRevealTest

    private fun rowBounds(line: Int): Rect = composeTestRule
        .onAllNodes(
            hasText("row-$line") and hasAnyAncestor(hasTestTag(EDITOR_CONTENT_TEST_TAG)),
            useUnmergedTree = true,
        )
        .fetchSemanticsNodes()
        .single()
        .boundsInRoot

    private fun itemHeightAt(line: Int) = rowBounds(line + 1).top - rowBounds(line).top

    private fun assertRowCentred(line: Int, rowHeight: Float) {
        rowBounds(line).center.y shouldBe (visibleCentre() plusOrMinus rowHeight)
    }

    // Wrap-on helpers, as in LazyTextEditorWrapSettleTest

    private fun SemanticsNode.isPlacedInTree(): Boolean {
        var info: LayoutInfo? = layoutInfo
        while (info != null) {
            if (!info.isPlaced) return false
            info = info.parentInfo
        }
        return true
    }

    private fun visualLineHeight(): Float = composeTestRule.onNode(
        hasText("1") and !hasAnyAncestor(hasTestTag(EDITOR_CONTENT_TEST_TAG)),
        useUnmergedTree = true,
    ).fetchSemanticsNode().boundsInRoot.height

    private fun textTop(line: Int): Float? = composeTestRule
        .onAllNodes(hasText(lines[line]) and hasAnyAncestor(hasTestTag(EDITOR_CONTENT_TEST_TAG)), useUnmergedTree = true)
        .fetchSemanticsNodes()
        .singleOrNull { it.isPlacedInTree() }
        ?.positionInRoot
        ?.y

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun `non-forced far caret move with wrap on settles after wrapped content loads`() {
        lines = (0 until TOTAL_LINES).map(::wrappedLine)
        wordWrap = true
        windowEnd = WRAP_INITIAL_WINDOW_END
        setEditor()
        val lineHeight = visualLineHeight()

        cursor = TextPosition(0L, TARGET.toLong(), 0)
        composeTestRule.waitForIdle()
        publishWindow(TARGET - 40, TARGET + 60)

        val top = textTop(TARGET)
        top shouldNotBe null
        val viewport = viewportBounds()
        withClue("textTop=$top viewport=${viewport.top}..${viewport.bottom} lineHeight=$lineHeight") {
            (top!! + lineHeight / 2f) shouldBe (visibleCentre() plusOrMinus lineHeight)
        }
    }

    @Test
    fun `forced centring deferred by full-viewport padding completes when space returns later than the window`() {
        setEditor()
        val rowHeight = itemHeightAt(0)
        bottomPadding = 3000.dp
        composeTestRule.waitForIdle()

        cursor = TextPosition(0L, TARGET_ROW.toLong(), 0)
        scrollTrigger += 1
        composeTestRule.waitForIdle()

        composeTestRule.mainClock.advanceTimeBy(3_000L)
        composeTestRule.waitForIdle()

        bottomPadding = BOTTOM_PADDING
        composeTestRule.waitForIdle()

        withClue("rowHeight=$rowHeight") {
            assertRowCentred(TARGET_ROW, rowHeight)
        }
    }

    @Test
    @Config(qualifiers = "w400dp-h800dp")
    fun `forced centring re-centres when the bottom padding shrinks right after (keyboard closing)`() {
        setEditor()
        val rowHeight = itemHeightAt(0)
        // Keyboard up: leaves a 200dp region between the paddings. The taller window makes the keyboard
        // padding shrink far larger than one row, so the old and new centres are distinguishable.
        bottomPadding = viewportBounds().height.toDp() - TOP_PADDING - 200.dp
        composeTestRule.waitForIdle()

        cursor = TextPosition(0L, TARGET_ROW.toLong(), 0)
        scrollTrigger += 1
        composeTestRule.waitForIdle()

        composeTestRule.mainClock.advanceTimeBy(300L)
        val oldCentre = visibleCentre()
        val rowBeforeShrink = rowBounds(TARGET_ROW).center.y
        bottomPadding = BOTTOM_PADDING
        composeTestRule.waitForIdle()

        withClue(
            "row=${rowBounds(TARGET_ROW).center.y} rowBeforeShrink=$rowBeforeShrink oldCentre=$oldCentre " +
                "newCentre=${visibleCentre()} rowHeight=$rowHeight",
        ) {
            assertRowCentred(TARGET_ROW, rowHeight)
        }
    }

    companion object {
        private const val TOTAL_LINES = 1000
        private const val WRAP_INITIAL_WINDOW_END = 60L
        private const val TARGET = 500
        private const val TARGET_ROW = 600
        private val TOP_PADDING = 120.dp
        private val BOTTOM_PADDING = 220.dp
    }
}
