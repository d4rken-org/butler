package eu.darken.butler.editor.ui.editor.text

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import eu.darken.butler.editor.core.engine.TextPosition
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.shouldBe
import org.junit.Test
import testhelpers.ComposeTest
import eu.darken.butler.common.compose.PreviewWrapper

/**
 * Regression tests for the hidden-field input arbitration (the `isUserEditing` ownership model):
 * typed input must reach the engine as single contiguous ReplaceRange edits, the engine echo
 * must converge with the field, and rapid input must not drop characters. This state machine
 * was fixed once before (dropped autocorrect/IME input) and had no composable-level coverage.
 */
class LazyTextEditorInputTest : ComposeTest() {

    private data class ReplaceEvent(
        val start: TextPosition,
        val end: TextPosition,
        val inserted: String,
        val caret: TextPosition,
    )

    /**
     * Minimal engine stand-in: applies each ReplaceRange to a plain string and echoes it back
     * as the new content, exactly like EditorEngine echoes buffer state into currentContent.
     */
    private class FakeEngine(initial: String) {
        var content = initial
        val events = mutableListOf<ReplaceEvent>()

        private fun flatOffset(lines: List<String>, position: TextPosition): Int {
            var offset = 0
            for (line in 0 until position.line.toInt()) offset += lines[line].length + 1
            return offset + position.column
        }

        fun apply(event: ReplaceEvent): String {
            events += event
            val lines = content.split('\n')
            val start = flatOffset(lines, event.start)
            val end = flatOffset(lines, event.end)
            val (from, to) = if (start <= end) start to end else end to start
            content = content.substring(0, from) + event.inserted + content.substring(to)
            return content
        }
    }

    /**
     * [echoDelayMs] > 0 defers the engine echo like the real launched edit round-trip does -
     * the window the isUserEditing arbitration exists to protect. 0 echoes synchronously.
     */
    private fun ComposeContentTestRule.setEditor(
        engine: FakeEngine,
        readOnly: Boolean = false,
        echoDelayMs: Long = 0L,
    ) {
        setContent {
            PreviewWrapper {
                var content by remember { mutableStateOf(engine.content) }
                var cursor by remember { mutableStateOf(TextPosition(0, 0, 0)) }
                val scope = rememberCoroutineScope()
                LazyTextEditor(
                    content = content,
                    totalLines = content.split('\n').size.toLong(),
                    cursorPosition = cursor,
                    selection = null,
                    visibleRange = 0L..(content.split('\n').size.toLong() - 1),
                    readOnly = readOnly,
                    onTextReplace = { start, end, inserted, caret ->
                        val echoed = engine.apply(ReplaceEvent(start, end, inserted, caret))
                        if (echoDelayMs > 0) {
                            scope.launch {
                                delay(echoDelayMs)
                                content = engine.content
                                cursor = caret
                            }
                        } else {
                            content = echoed
                            cursor = caret
                        }
                    },
                    onCursorPositionChange = { cursor = it },
                    onSelectionChange = {},
                    onVisibleRangeChange = {},
                    onCursorMove = { _, _ -> },
                    onForwardDelete = {},
                )
            }
        }
    }

    @Test
    fun `typed input reaches the engine and the echo converges`() {
        val engine = FakeEngine("hello world")
        composeTestRule.setEditor(engine)

        composeTestRule.onNodeWithTag(EDITOR_INPUT_TEST_TAG).performTextInput("abc")
        composeTestRule.waitForIdle()

        engine.events.shouldNotBeEmpty()
        engine.content shouldBe "abchello world"
    }

    @Test
    fun `rapid sequential input drops nothing`() {
        val engine = FakeEngine("base")
        composeTestRule.setEditor(engine)

        val field = composeTestRule.onNodeWithTag(EDITOR_INPUT_TEST_TAG)
        field.performTextInput("one ")
        field.performTextInput("two ")
        field.performTextInput("three ")
        composeTestRule.waitForIdle()

        engine.content shouldBe "one two three base"
    }

    @Test
    fun `input landing before the engine echo is not clobbered`() {
        // The echo lags each edit like the real launched round-trip; typing continues while
        // stale content flows back - the field must stay authoritative until it converges
        val engine = FakeEngine("base")
        composeTestRule.setEditor(engine, echoDelayMs = 50L)

        val field = composeTestRule.onNodeWithTag(EDITOR_INPUT_TEST_TAG)
        field.performTextInput("one ")
        field.performTextInput("two ")
        field.performTextInput("three ")

        composeTestRule.waitUntil(timeoutMillis = 10_000) { engine.content == "one two three base" }
    }

    @Test
    fun `autocorrect-style replacement arrives as a single contiguous edit`() {
        val engine = FakeEngine("teh cat")
        composeTestRule.setEditor(engine)

        // Replace the entire field content in one IME commit, like predictive text does
        composeTestRule.onNodeWithTag(EDITOR_INPUT_TEST_TAG).performTextReplacement("the cat")
        composeTestRule.waitForIdle()

        engine.content shouldBe "the cat"
        // The diff must be contiguous: exactly one replace event for the changed region
        engine.events.size shouldBe 1
        engine.events.single().inserted shouldBe "he"
    }

    @Test
    fun `multi-line content maps line and column correctly`() {
        val engine = FakeEngine("first\nsecond\nthird")
        composeTestRule.setEditor(engine)

        composeTestRule.onNodeWithTag(EDITOR_INPUT_TEST_TAG).performTextInput("X")
        composeTestRule.waitForIdle()

        engine.content shouldBe "Xfirst\nsecond\nthird"
        engine.events.single().start.line shouldBe 0L
    }

    @Test
    fun `read-only editors expose no text-input semantics at all`() {
        val engine = FakeEngine("locked content")
        composeTestRule.setEditor(engine, readOnly = true)

        // readOnly strips the SetText action from the field - input is impossible by
        // construction, not merely ignored
        composeTestRule.onNodeWithTag(EDITOR_INPUT_TEST_TAG)
            .assert(hasSetTextAction().not())

        engine.events.shouldBeEmpty()
        engine.content shouldBe "locked content"
    }
}
