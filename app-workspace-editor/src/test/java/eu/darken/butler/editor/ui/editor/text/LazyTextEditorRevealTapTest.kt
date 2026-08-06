package eu.darken.butler.editor.ui.editor.text

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import eu.darken.butler.common.compose.PreviewWrapper
import eu.darken.butler.editor.core.engine.EditorEngine
import eu.darken.butler.editor.core.engine.TextPosition
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CompletableDeferred
import org.junit.Test
import testhelpers.ComposeTest
import kotlin.uuid.Uuid

/**
 * Tap-to-reveal on the truncation marker chips, tested through the REAL editor gesture stack:
 * the chip's clickable must fire the reveal AND suppress the ancestor tap handler (caret
 * placement, multi-tap tracking), and a reveal-driven window slide must not corrupt the
 * hidden-field edit authority ([LazyTextEditorInputTest] covers the arbitration itself).
 */
class LazyTextEditorRevealTapTest : ComposeTest() {

    private val engineEpoch = Uuid.random()

    /**
     * Engine stand-in with the sliding window: display shows each long line's [cap]-sized window
     * at the shared [anchor]; [reveal] pages the anchor like [EditorEngine.revealMoreColumns].
     */
    private class WindowedFakeEngine(var content: String, val cap: Int) {
        var anchor = 0
        val insertedTexts = mutableListOf<String>()

        private fun lineAnchor(line: String): Int =
            if (line.length > cap) anchor.coerceAtMost(line.length - cap) else 0

        fun displayText(): String = content.split('\n').joinToString("\n") { line ->
            line.substring(lineAnchor(line)).take(cap)
        }

        fun startColumns(): Map<Long, Long> = content.split('\n')
            .mapIndexedNotNull { index, line ->
                val la = lineAnchor(line)
                if (la > 0) index.toLong() to la.toLong() else null
            }
            .toMap()

        fun truncatedLines(): Map<Long, Long> = content.split('\n')
            .mapIndexedNotNull { index, line ->
                val hidden = line.length - (lineAnchor(line) + cap)
                if (hidden > 0) index.toLong() to hidden.toLong() else null
            }
            .toMap()

        fun reveal(forward: Boolean) {
            val page = cap / 2
            val maxAnchor = (content.split('\n').maxOf { it.length } - cap).coerceAtLeast(0)
            anchor = (anchor + if (forward) page else -page).coerceIn(0, maxAnchor)
        }

        fun applyInsert(position: TextPosition, inserted: String) {
            insertedTexts += inserted
            val lines = content.split('\n')
            var offset = 0
            for (line in 0 until position.line.toInt()) offset += lines[line].length + 1
            offset += position.column
            content = content.substring(0, offset) + inserted + content.substring(offset)
        }
    }

    private fun ComposeContentTestRule.setWindowedEditor(
        engine: WindowedFakeEngine,
        reveals: MutableList<Boolean>,
        cursorChanges: MutableList<TextPosition>,
        initialCursor: TextPosition = TextPosition(0, 0, 0),
    ) {
        setContent {
            PreviewWrapper {
                var display by remember { mutableStateOf(engine.displayText()) }
                var truncated by remember { mutableStateOf(engine.truncatedLines()) }
                var startCols by remember { mutableStateOf(engine.startColumns()) }
                var cursor by remember { mutableStateOf(initialCursor) }
                var version by remember { mutableLongStateOf(0L) }
                LazyTextEditor(
                    content = display,
                    totalLines = display.split('\n').size.toLong(),
                    cursorPosition = cursor,
                    selection = null,
                    visibleRange = 0L..(display.split('\n').size.toLong() - 1),
                    truncatedLines = truncated,
                    startColumns = startCols,
                    windowToken = EditorEngine.DocumentToken(engineEpoch, version),
                    onEnqueueDelta = { delta ->
                        engine.applyInsert(delta.start, delta.newText)
                        display = engine.displayText()
                        truncated = engine.truncatedLines()
                        startCols = engine.startColumns()
                        cursor = delta.caret
                        version += 1
                        CompletableDeferred(
                            EditorEngine.MutationResult.Applied(
                                EditorEngine.DocumentToken(engineEpoch, version),
                            ),
                        )
                    },
                    onCursorPositionChange = {
                        cursorChanges += it
                        cursor = it
                    },
                    onSelectionChange = {},
                    onVisibleRangeChange = {},
                    onRevealMoreColumns = { forward ->
                        reveals += forward
                        engine.reveal(forward)
                        display = engine.displayText()
                        truncated = engine.truncatedLines()
                        startCols = engine.startColumns()
                        version += 1
                    },
                    onCursorMove = { _, _ -> },
                    onForwardDelete = {},
                )
            }
        }
    }

    @Test
    fun `marker taps reveal in both directions without placing the caret`() {
        val engine = WindowedFakeEngine("0123456789ABCDEFGHIJ", cap = 10)
        val reveals = mutableListOf<Boolean>()
        val cursorChanges = mutableListOf<TextPosition>()
        composeTestRule.setWindowedEditor(engine, reveals, cursorChanges)
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag(EDITOR_TRUNCATION_MARKER_TEST_TAG).performClick()
        composeTestRule.waitForIdle()
        engine.anchor shouldBe 5

        composeTestRule.onNodeWithTag(EDITOR_LEADING_TRUNCATION_MARKER_TEST_TAG).performClick()
        composeTestRule.waitForIdle()
        engine.anchor shouldBe 0

        reveals shouldBe listOf(true, false)
        // The chip's clickable must have consumed both taps: the ancestor tap handler would have
        // hit-tested them into caret placements
        cursorChanges.shouldBeEmpty()
    }

    @Test
    fun `reveal right after typing rebuilds the field from the new window without corrupting the edit`() {
        val engine = WindowedFakeEngine("0123456789ABCDEFGHIJ", cap = 10)
        val reveals = mutableListOf<Boolean>()
        val cursorChanges = mutableListOf<TextPosition>()
        composeTestRule.setWindowedEditor(
            engine,
            reveals,
            cursorChanges,
            initialCursor = TextPosition(0, 0, 2),
        )
        composeTestRule.waitForIdle()

        // Take edit authority, then immediately slide the window via the marker
        composeTestRule.onNodeWithTag(EDITOR_INPUT_TEST_TAG).performTextInput("XY")
        composeTestRule.onNodeWithTag(EDITOR_TRUNCATION_MARKER_TEST_TAG).performClick()
        composeTestRule.waitForIdle()

        engine.content shouldBe "01XY23456789ABCDEFGHIJ"
        engine.insertedTexts shouldBe listOf("XY")
        // The startColumns change must have released authority and rebuilt the field to the
        // slid window - not left it showing the stale pre-reveal text
        val fieldText = composeTestRule.onNodeWithTag(EDITOR_INPUT_TEST_TAG)
            .fetchSemanticsNode().config[SemanticsProperties.EditableText].text
        fieldText shouldBe engine.displayText()
    }
}
