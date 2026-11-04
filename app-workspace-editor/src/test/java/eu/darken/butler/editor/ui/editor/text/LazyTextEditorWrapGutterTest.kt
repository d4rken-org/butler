package eu.darken.butler.editor.ui.editor.text

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import eu.darken.butler.common.compose.PreviewWrapper
import eu.darken.butler.editor.core.engine.EditorEngine
import eu.darken.butler.editor.core.engine.TextPosition
import io.kotest.matchers.floats.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CompletableDeferred
import org.junit.Test
import org.robolectric.annotation.GraphicsMode
import testhelpers.ComposeTest
import kotlin.uuid.Uuid

/**
 * With line wrap on, each line-number cell must be exactly as tall as its wrapped content row, or the
 * numbers drift further from their lines with every row. NATIVE graphics so the lines really wrap.
 */
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LazyTextEditorWrapGutterTest : ComposeTest() {

    private val engineEpoch = Uuid.random()

    private val lines = (1..LINE_COUNT).map { n -> "[$n] " + "lorem ipsum dolor sit amet ".repeat(8).trim() }

    private fun setEditor() {
        composeTestRule.setContent {
            PreviewWrapper {
                LazyTextEditor(
                    content = lines.joinToString("\n"),
                    totalLines = LINE_COUNT.toLong(),
                    cursorPosition = TextPosition(0L, 0L, 0),
                    selection = null,
                    windowToken = EditorEngine.DocumentToken(engineEpoch, 0L),
                    wordWrap = true,
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

    private fun contentBounds(line: Int): Rect = composeTestRule
        .onNode(hasText(lines[line]) and hasAnyAncestor(hasTestTag(EDITOR_CONTENT_TEST_TAG)), useUnmergedTree = true)
        .fetchSemanticsNode()
        .boundsInRoot

    private fun gutterBounds(line: Int): Rect = composeTestRule
        .onNode(
            hasText((line + 1).toString()) and !hasAnyAncestor(hasTestTag(EDITOR_CONTENT_TEST_TAG)),
            useUnmergedTree = true,
        )
        .fetchSemanticsNode()
        .boundsInRoot

    @Test
    fun `line numbers stay on their wrapped lines`() {
        setEditor()

        contentBounds(0).height shouldBeGreaterThan gutterBounds(0).height * 1.5f

        (0 until CHECKED_LINES).forEach { line ->
            gutterBounds(line).top shouldBe contentBounds(line).top
        }
        (0 until CHECKED_LINES - 1).forEach { line ->
            val gutterPitch = gutterBounds(line + 1).top - gutterBounds(line).top
            val contentPitch = contentBounds(line + 1).top - contentBounds(line).top
            gutterPitch shouldBe contentPitch
        }
    }

    companion object {
        private const val LINE_COUNT = 8
        private const val CHECKED_LINES = 4
    }
}
