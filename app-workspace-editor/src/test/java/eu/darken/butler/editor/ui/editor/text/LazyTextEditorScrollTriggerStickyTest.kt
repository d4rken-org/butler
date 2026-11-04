package eu.darken.butler.editor.ui.editor.text

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollToIndex
import eu.darken.butler.common.compose.PreviewWrapper
import eu.darken.butler.editor.core.engine.EditorEngine
import eu.darken.butler.editor.core.engine.TextPosition
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlinx.coroutines.CompletableDeferred
import org.junit.Test
import testhelpers.ComposeTest
import kotlin.uuid.Uuid

/** A past scrollTrigger bump must not turn every later cursor move into a re-centre. */
class LazyTextEditorScrollTriggerStickyTest : ComposeTest() {

    private val engineEpoch = Uuid.random()
    private var scrollTrigger by mutableIntStateOf(0)
    private var cursorLine by mutableLongStateOf(START_CURSOR_LINE)
    private var totalLines by mutableLongStateOf(TOTAL_LINES)

    private fun setEditor() {
        composeTestRule.setContent {
            PreviewWrapper {
                LazyTextEditor(
                    content = if (totalLines > 0) (0 until totalLines).joinToString("\n") { "row-$it" } else "",
                    totalLines = totalLines,
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

    /** Lowest "row-N" whose bounds reach into the content viewport. */
    private fun topLine(): Int {
        val viewport = composeTestRule.onNodeWithTag(EDITOR_CONTENT_TEST_TAG).fetchSemanticsNode().boundsInRoot
        return composeTestRule
            .onAllNodes(hasAnyAncestor(hasTestTag(EDITOR_CONTENT_TEST_TAG)), useUnmergedTree = true)
            .fetchSemanticsNodes()
            .filter { it.boundsInRoot.bottom > viewport.top && it.boundsInRoot.top < viewport.bottom }
            .mapNotNull { node ->
                node.config.getOrNull(SemanticsProperties.Text)
                    ?.joinToString("") { it.text }
                    ?.takeIf { it.startsWith("row-") }
                    ?.removePrefix("row-")
                    ?.toIntOrNull()
            }
            .min()
    }

    private fun scrollTo(index: Int) {
        composeTestRule.onNodeWithTag(EDITOR_CONTENT_TEST_TAG).performScrollToIndex(index)
        composeTestRule.waitForIdle()
    }

    private fun moveCursorWithinViewAndAssertNoScroll() {
        scrollTo(VIEW_TOP)
        val before = topLine()
        println("DIAG before cursor move: topLine=$before trigger=$scrollTrigger cursor=$cursorLine")
        before shouldBe VIEW_TOP

        cursorLine = MOVED_CURSOR_LINE
        composeTestRule.waitForIdle()

        val after = topLine()
        println("DIAG after cursor move: topLine=$after trigger=$scrollTrigger cursor=$cursorLine")
        after shouldBe before
    }

    @Test
    fun `control - cursor move within view does not scroll with trigger 0`() {
        setEditor()
        moveCursorWithinViewAndAssertNoScroll()
    }

    @Test
    fun `cursor move within view does not scroll after a past trigger bump`() {
        setEditor()
        // As after one Go to line: the bump re-centres on the cursor line
        scrollTrigger = 1
        composeTestRule.waitForIdle()
        println("DIAG after bump: topLine=${topLine()}")

        moveCursorWithinViewAndAssertNoScroll()
    }

    @Test
    fun `trigger bump still forces one scroll when the cursor line is already visible`() {
        setEditor()
        scrollTo(VIEW_TOP)
        topLine() shouldBe VIEW_TOP

        scrollTrigger += 1
        composeTestRule.waitForIdle()

        val after = topLine()
        println("DIAG forced re-centre: topLine=$after")
        after shouldNotBe VIEW_TOP
    }

    @Test
    fun `trigger bump that arrives before the list can scroll is honoured on the next run`() {
        totalLines = 0L
        setEditor()
        // The effect runs on this bump but bails at the totalLines / totalItemsCount guard
        scrollTrigger = 1
        composeTestRule.waitForIdle()

        totalLines = TOTAL_LINES
        composeTestRule.waitForIdle()
        scrollTo(VIEW_TOP)
        topLine() shouldBe VIEW_TOP

        // First effect run that gets past the guard: the pending bump forces a re-centre
        cursorLine = MOVED_CURSOR_LINE
        composeTestRule.waitForIdle()
        val after = topLine()
        println("DIAG pending bump honoured: topLine=$after")
        after shouldNotBe VIEW_TOP

        // Consumed: a further in-view move no longer scrolls
        scrollTo(VIEW_TOP)
        cursorLine = START_CURSOR_LINE
        composeTestRule.waitForIdle()
        topLine() shouldBe VIEW_TOP
    }

    companion object {
        private const val TOTAL_LINES = 500L
        private const val VIEW_TOP = 100
        private const val START_CURSOR_LINE = 102L
        private const val MOVED_CURSOR_LINE = 104L
    }
}
