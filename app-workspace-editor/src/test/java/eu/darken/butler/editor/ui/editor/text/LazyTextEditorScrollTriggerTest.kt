package eu.darken.butler.editor.ui.editor.text

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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

/** A scrollTrigger bump brings the cursor line back into view even though the cursor itself is unchanged. */
class LazyTextEditorScrollTriggerTest : ComposeTest() {

    private val engineEpoch = Uuid.random()
    private var scrollTrigger by mutableIntStateOf(0)

    private fun cursorLineComposed(): Boolean = composeTestRule
        .onAllNodes(
            hasText("row-$CURSOR_LINE") and hasAnyAncestor(hasTestTag(EDITOR_CONTENT_TEST_TAG)),
            useUnmergedTree = true,
        )
        .fetchSemanticsNodes()
        .isNotEmpty()

    @Test
    fun `scroll trigger re-centres on an unchanged cursor line`() {
        composeTestRule.setContent {
            PreviewWrapper {
                LazyTextEditor(
                    content = (0 until TOTAL_LINES).joinToString("\n") { "row-$it" },
                    totalLines = TOTAL_LINES.toLong(),
                    cursorPosition = TextPosition(0L, CURSOR_LINE.toLong(), 0),
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

        // The cursor line is out of view (the user scrolled away); the cursor stays on its line
        composeTestRule.onNodeWithTag(EDITOR_CONTENT_TEST_TAG).performScrollToIndex(0)
        composeTestRule.waitForIdle()
        cursorLineComposed() shouldBe false

        scrollTrigger += 1
        composeTestRule.waitForIdle()

        cursorLineComposed() shouldBe true
    }

    companion object {
        private const val TOTAL_LINES = 1000
        private const val CURSOR_LINE = 400
    }
}
