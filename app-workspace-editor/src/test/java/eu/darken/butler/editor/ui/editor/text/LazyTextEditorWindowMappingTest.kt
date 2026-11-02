package eu.darken.butler.editor.ui.editor.text

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollToIndex
import eu.darken.butler.common.compose.PreviewWrapper
import eu.darken.butler.editor.core.engine.EditorEngine
import eu.darken.butler.editor.core.engine.TextPosition
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CompletableDeferred
import org.junit.Test
import testhelpers.ComposeTest
import kotlin.uuid.Uuid

/**
 * The window's lines must land on the rows of the lines they were read for. A row is identified by
 * its line-number cell: both columns scroll together and share the row height, so a content line
 * sits on line N's row exactly when its top matches the top of the "N + 1" label.
 */
class LazyTextEditorWindowMappingTest : ComposeTest() {

    private val engineEpoch = Uuid.random()

    private var content by mutableStateOf("alpha-line\nbravo-line\ncharlie-line")
    private var windowRangeStart by mutableLongStateOf(WINDOW_START)
    private var version by mutableLongStateOf(0L)

    private fun setEditor() {
        composeTestRule.setContent {
            PreviewWrapper {
                LazyTextEditor(
                    content = content,
                    totalLines = TOTAL_LINES,
                    // Parked on the window so the cursor auto-scroll has nothing to chase
                    cursorPosition = TextPosition(0L, WINDOW_START, 0),
                    selection = null,
                    windowToken = EditorEngine.DocumentToken(engineEpoch, version),
                    windowRangeStart = windowRangeStart,
                    onEnqueueDelta = {
                        CompletableDeferred(
                            EditorEngine.MutationResult.Applied(EditorEngine.DocumentToken(engineEpoch, version)),
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
        scrollTo(WINDOW_START)
    }

    private fun scrollTo(line: Long) {
        composeTestRule.onNodeWithTag(EDITOR_CONTENT_TEST_TAG).performScrollToIndex(line.toInt())
        composeTestRule.waitForIdle()
    }

    private fun contentTop(text: String): Float = composeTestRule
        .onNode(hasText(text) and hasAnyAncestor(hasTestTag(EDITOR_CONTENT_TEST_TAG)), useUnmergedTree = true)
        .fetchSemanticsNode()
        .boundsInRoot
        .top

    private fun rowTop(line: Long): Float = composeTestRule
        .onNode(
            hasText((line + 1).toString()) and !hasAnyAncestor(hasTestTag(EDITOR_CONTENT_TEST_TAG)),
            useUnmergedTree = true,
        )
        .fetchSemanticsNode()
        .boundsInRoot
        .top

    private fun assertOnRow(text: String, line: Long) {
        contentTop(text) shouldBe rowTop(line)
    }

    @Test
    fun `window lines render on their own rows when the visible range has moved on`() {
        setEditor()

        assertOnRow("alpha-line", WINDOW_START)
        assertOnRow("bravo-line", WINDOW_START + 1)
        assertOnRow("charlie-line", WINDOW_START + 2)
    }

    @Test
    fun `a new window start with the same content moves the lines to the new rows`() {
        setEditor()
        assertOnRow("alpha-line", WINDOW_START)

        windowRangeStart = WINDOW_START + 2
        version += 1
        composeTestRule.waitForIdle()

        assertOnRow("alpha-line", WINDOW_START + 2)
        assertOnRow("bravo-line", WINDOW_START + 3)
        assertOnRow("charlie-line", WINDOW_START + 4)
    }

    @Test
    fun `new content at the same window start replaces the rendered lines`() {
        setEditor()
        assertOnRow("alpha-line", WINDOW_START)

        content = "delta-line\necho-line\nfoxtrot-line"
        version += 1
        composeTestRule.waitForIdle()

        assertOnRow("delta-line", WINDOW_START)
        assertOnRow("echo-line", WINDOW_START + 1)
        assertOnRow("foxtrot-line", WINDOW_START + 2)
        composeTestRule
            .onAllNodes(hasText("alpha-line") and hasAnyAncestor(hasTestTag(EDITOR_CONTENT_TEST_TAG)), useUnmergedTree = true)
            .fetchSemanticsNodes()
            .size shouldBe 0
    }

    companion object {
        private const val TOTAL_LINES = 2000L
        private const val WINDOW_START = 1000L
    }
}
