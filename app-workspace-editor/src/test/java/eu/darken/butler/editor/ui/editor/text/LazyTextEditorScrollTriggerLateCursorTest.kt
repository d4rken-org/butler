package eu.darken.butler.editor.ui.editor.text

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
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
import io.kotest.assertions.withClue
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CompletableDeferred
import org.junit.Test
import testhelpers.ComposeTest
import kotlin.uuid.Uuid

/**
 * Search Next bumps scrollTrigger before the engine publishes the new cursor. The forced scroll
 * must land on the navigation target, not be spent on the cursor that was already there.
 */
class LazyTextEditorScrollTriggerLateCursorTest : ComposeTest() {

    private val engineEpoch = Uuid.random()
    private var scrollTrigger by mutableIntStateOf(0)
    private var cursorLine by mutableLongStateOf(CURSOR_A)
    private var scrollTargetLine by mutableStateOf<Long?>(null)

    private fun setEditor() {
        composeTestRule.setContent {
            PreviewWrapper {
                LazyTextEditor(
                    content = (0 until TOTAL_LINES).joinToString("\n") { "row-$it" },
                    totalLines = TOTAL_LINES,
                    cursorPosition = TextPosition(0L, cursorLine, 0),
                    selection = null,
                    windowToken = EditorEngine.DocumentToken(engineEpoch, 0L),
                    scrollTrigger = scrollTrigger,
                    scrollTargetLine = scrollTargetLine,
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

    @Test
    fun `trigger that arrives before its cursor re-centres on the cursor that follows`() {
        setEditor()
        composeTestRule.onNodeWithTag(EDITOR_CONTENT_TEST_TAG).performScrollToIndex(VIEW_TOP)
        composeTestRule.waitForIdle()
        topLine() shouldBe VIEW_TOP

        // Frame 1: the new trigger, still with the old cursor A (visible)
        scrollTargetLine = CURSOR_B
        scrollTrigger += 1
        composeTestRule.waitForIdle()
        val topAfterTrigger = topLine()

        // Frame 2: the navigation target B, already visible but not centred
        cursorLine = CURSOR_B
        composeTestRule.waitForIdle()
        val topAfterCursor = topLine()

        // Centre offset as the editor computes it, measured from a forced scroll on a known line
        cursorLine = CURSOR_A
        scrollTargetLine = CURSOR_A
        scrollTrigger += 1
        composeTestRule.waitForIdle()
        val centreOffset = CURSOR_A.toInt() - topLine()

        println(
            "DIAG late cursor: viewTop=$VIEW_TOP topAfterTrigger=$topAfterTrigger " +
                "topAfterCursor=$topAfterCursor centreOffset=$centreOffset expectedTop=${CURSOR_B.toInt() - centreOffset}",
        )
        centreOffset shouldBeGreaterThan (CURSOR_B - CURSOR_A).toInt()
        withClue("view must be centred on B after the trigger that requested B") {
            topAfterCursor shouldBe CURSOR_B.toInt() - centreOffset
        }
    }

    companion object {
        private const val TOTAL_LINES = 500L
        private const val VIEW_TOP = 100
        private const val CURSOR_A = 102L
        private const val CURSOR_B = 106L
    }
}
