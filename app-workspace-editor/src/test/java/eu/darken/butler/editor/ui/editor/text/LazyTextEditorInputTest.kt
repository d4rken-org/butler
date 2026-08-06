package eu.darken.butler.editor.ui.editor.text

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.text.TextRange
import eu.darken.butler.common.compose.PreviewWrapper
import eu.darken.butler.editor.core.engine.EditorEngine
import eu.darken.butler.editor.core.engine.TextPosition
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.junit.Test
import testhelpers.ComposeTest
import kotlin.uuid.Uuid

/**
 * Regression tests for the hidden-field input protocol: typed input must reach the engine as single
 * contiguous deltas mapped through the window the field last rebased on, a burst typed before the
 * first acknowledgement must still land where the user typed it, and rapid input must not drop
 * characters. This state machine was fixed once before (dropped autocorrect/IME input) and had no
 * composable-level coverage.
 */
class LazyTextEditorInputTest : ComposeTest() {

    /**
     * Minimal engine stand-in: applies each delta to a plain string, echoes the display window back
     * and hands out a fresh token, like the engine's verified-mutation path does.
     *
     * [cap] mirrors the display cap (each line is echoed truncated) and [anchor] the horizontal
     * window anchor for lines over it - the two cases where the echoed window can never equal the
     * field text again.
     */
    private class FakeEngine(
        initial: String,
        private val cap: Int = Int.MAX_VALUE,
        private val anchor: Int = 0,
    ) {
        var content = initial
            private set
        val deltas = mutableListOf<SessionDelta>()
        private val epoch = Uuid.random()
        private var version = 0L

        val token: EditorEngine.DocumentToken get() = EditorEngine.DocumentToken(epoch, version)

        private fun lines() = content.split('\n')

        private fun startColumn(line: String): Int =
            if (line.length > cap) anchor.coerceAtMost(line.length - cap) else 0

        private fun slice(line: String): String = line.drop(startColumn(line)).take(cap)

        fun displayText(): String = lines().joinToString("\n") { slice(it) }

        fun startColumns(): Map<Long, Long> = lines()
            .mapIndexedNotNull { index, line ->
                startColumn(line).takeIf { it > 0 }?.let { index.toLong() to it.toLong() }
            }
            .toMap()

        fun truncatedLines(): Map<Long, Long> = lines()
            .mapIndexedNotNull { index, line ->
                val hidden = line.length - startColumn(line) - slice(line).length
                hidden.takeIf { it > 0 }?.let { index.toLong() to it.toLong() }
            }
            .toMap()

        private fun flatOffset(lines: List<String>, position: TextPosition): Int {
            var offset = 0
            for (line in 0 until position.line.toInt()) offset += lines[line].length + 1
            return offset + position.column
        }

        /** Applies the delta against the FULL content, like the engine resolving line/column. */
        fun apply(delta: SessionDelta): EditorEngine.MutationResult {
            deltas += delta
            val lines = lines()
            val start = flatOffset(lines, delta.start)
            val end = flatOffset(lines, delta.end)
            val (from, to) = if (start <= end) start to end else end to start
            content = content.substring(0, from) + delta.newText + content.substring(to)
            version += 1
            return EditorEngine.MutationResult.Applied(token)
        }
    }

    /**
     * [echoDelayMs] > 0 defers the echo AND the acknowledgement like the real round-trip does -
     * the window the input session exists to protect. 0 echoes synchronously.
     */
    private fun ComposeContentTestRule.setEditor(
        engine: FakeEngine,
        readOnly: Boolean = false,
        echoDelayMs: Long = 0L,
        initialCursor: TextPosition = TextPosition(0, 0, 0),
        externalCursor: MutableState<TextPosition?> = mutableStateOf(null),
    ) {
        setContent {
            PreviewWrapper {
                var display by remember { mutableStateOf(engine.displayText()) }
                var truncated by remember { mutableStateOf(engine.truncatedLines()) }
                var startCols by remember { mutableStateOf(engine.startColumns()) }
                var windowToken by remember { mutableStateOf(engine.token) }
                var cursor by remember { mutableStateOf(initialCursor) }
                val scope = rememberCoroutineScope()
                // Engine-authoritative cursor moves driven from the test body
                val pendingExternal = externalCursor.value
                LaunchedEffect(pendingExternal) {
                    if (pendingExternal != null) cursor = pendingExternal
                }
                LazyTextEditor(
                    content = display,
                    totalLines = display.split('\n').size.toLong(),
                    cursorPosition = cursor,
                    selection = null,
                    visibleRange = 0L..(display.split('\n').size.toLong() - 1),
                    truncatedLines = truncated,
                    startColumns = startCols,
                    windowToken = windowToken,
                    windowRangeStart = 0L,
                    readOnly = readOnly,
                    onEnqueueDelta = { delta ->
                        val result = engine.apply(delta)
                        val publish = {
                            display = engine.displayText()
                            truncated = engine.truncatedLines()
                            startCols = engine.startColumns()
                            windowToken = engine.token
                            cursor = delta.caret
                        }
                        if (echoDelayMs > 0) {
                            val outcome = CompletableDeferred<EditorEngine.MutationResult>()
                            scope.launch {
                                delay(echoDelayMs)
                                publish()
                                outcome.complete(result)
                            }
                            outcome
                        } else {
                            publish()
                            CompletableDeferred(result)
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

    /**
     * Full manual control over window publication and delta outcomes: the interleavings below are
     * exactly the ones a fake engine cannot produce, because it publishes and acknowledges together.
     */
    private class ManualHost(initialText: String) {
        private val epoch = Uuid.random()
        val content = mutableStateOf(initialText)
        val token = mutableStateOf<EditorEngine.DocumentToken?>(EditorEngine.DocumentToken(epoch, 0L))
        val cursor = mutableStateOf(TextPosition(0, 0, initialText.length))
        val deltas = mutableListOf<SessionDelta>()
        val outcomes = mutableListOf<CompletableDeferred<EditorEngine.MutationResult>>()

        fun tokenAt(version: Long) = EditorEngine.DocumentToken(epoch, version)

        fun publish(text: String, version: Long, cursorColumn: Int = text.length) {
            content.value = text
            token.value = tokenAt(version)
            cursor.value = TextPosition(0, 0, cursorColumn)
        }

        fun snapshot(text: String, version: Long, cursorColumn: Int = text.length) = EditorEngine.WindowSnapshot(
            content = EditorEngine.VisibleContent(text = text, rangeStart = 0L, token = tokenAt(version)),
            cursor = TextPosition(0, 0, cursorColumn),
            selection = null,
        )
    }

    private fun ComposeContentTestRule.setManualEditor(host: ManualHost) {
        setContent {
            PreviewWrapper {
                val text = host.content.value
                LazyTextEditor(
                    content = text,
                    totalLines = text.split('\n').size.toLong(),
                    cursorPosition = host.cursor.value,
                    selection = null,
                    visibleRange = 0L..(text.split('\n').size.toLong() - 1),
                    windowToken = host.token.value,
                    windowRangeStart = 0L,
                    onEnqueueDelta = { delta ->
                        host.deltas += delta
                        CompletableDeferred<EditorEngine.MutationResult>().also { host.outcomes += it }
                    },
                    onCursorPositionChange = {},
                    onSelectionChange = {},
                    onVisibleRangeChange = {},
                    onCursorMove = { _, _ -> },
                    onForwardDelete = {},
                )
            }
        }
    }

    private fun ComposeContentTestRule.fieldText(): String = onNodeWithTag(EDITOR_INPUT_TEST_TAG)
        .fetchSemanticsNode().config[SemanticsProperties.EditableText].text

    private fun ComposeContentTestRule.fieldSelection(): TextRange = onNodeWithTag(EDITOR_INPUT_TEST_TAG)
        .fetchSemanticsNode().config[SemanticsProperties.TextSelectionRange]

    @Test
    fun `typed input reaches the engine and the field converges`() {
        val engine = FakeEngine("hello world")
        composeTestRule.setEditor(engine)

        composeTestRule.onNodeWithTag(EDITOR_INPUT_TEST_TAG).performTextInput("abc")
        composeTestRule.waitForIdle()

        engine.deltas.shouldNotBeEmpty()
        engine.content shouldBe "abchello world"
        composeTestRule.fieldText() shouldBe "abchello world"
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
    fun `input landing before the acknowledgement is not clobbered`() {
        // The echo and the ack both lag each edit like the real round-trip; typing continues while
        // stale content flows back - the field stays authoritative until its deltas are acked
        val engine = FakeEngine("base")
        composeTestRule.setEditor(engine, echoDelayMs = 50L)

        val field = composeTestRule.onNodeWithTag(EDITOR_INPUT_TEST_TAG)
        field.performTextInput("one ")
        field.performTextInput("two ")
        field.performTextInput("three ")

        composeTestRule.waitUntil(timeoutMillis = 10_000) { engine.content == "one two three base" }
    }

    @Test
    fun `autocorrect-style replacement arrives as a single contiguous delta`() {
        val engine = FakeEngine("teh cat")
        composeTestRule.setEditor(engine)

        // Replace the entire field content in one IME commit, like predictive text does
        composeTestRule.onNodeWithTag(EDITOR_INPUT_TEST_TAG).performTextReplacement("the cat")
        composeTestRule.waitForIdle()

        engine.content shouldBe "the cat"
        // The diff must be contiguous: exactly one delta for the changed region
        engine.deltas.size shouldBe 1
        engine.deltas.single().newText shouldBe "he"
        engine.deltas.single().oldText shouldBe "eh"
    }

    @Test
    fun `multi-line content maps line and column correctly`() {
        val engine = FakeEngine("first\nsecond\nthird")
        composeTestRule.setEditor(engine)

        composeTestRule.onNodeWithTag(EDITOR_INPUT_TEST_TAG).performTextInput("X")
        composeTestRule.waitForIdle()

        engine.content shouldBe "Xfirst\nsecond\nthird"
        engine.deltas.single().start.line shouldBe 0L
    }

    @Test
    fun `read-only editors expose no text-input semantics at all`() {
        val engine = FakeEngine("locked content")
        composeTestRule.setEditor(engine, readOnly = true)

        // readOnly strips the SetText action from the field - input is impossible by
        // construction, not merely ignored
        composeTestRule.onNodeWithTag(EDITOR_INPUT_TEST_TAG)
            .assert(hasSetTextAction().not())

        engine.deltas.shouldBeEmpty()
        engine.content shouldBe "locked content"
    }

    // ==================== Local mapping across unacknowledged edits ====================

    @Test
    fun `a character typed after an unacknowledged newline lands on the new line`() {
        // Nothing has been acknowledged yet, so only the session's own local mapping knows that the
        // caret moved to line 1 - mapping through the engine window would send the character to
        // (line 0, column 1) and the document would end up reading "ab\n".
        val engine = FakeEngine("")
        composeTestRule.setEditor(engine, echoDelayMs = 200L)

        val field = composeTestRule.onNodeWithTag(EDITOR_INPUT_TEST_TAG)
        field.performTextInput("a")
        field.performTextInput("\n")
        field.performTextInput("b")

        composeTestRule.waitUntil(timeoutMillis = 10_000) { engine.deltas.size == 3 }
        val third = engine.deltas.last()
        third.start.line shouldBe 1L
        third.start.column shouldBe 0
        composeTestRule.waitUntil(timeoutMillis = 10_000) { engine.content == "a\nb" }
    }

    @Test
    fun `the same holds on a horizontally windowed line`() {
        // Line 0 is windowed at column 6, so field column 0 is engine column 6: the newline's
        // successor must map to the NEW line at column 0, not to line 0 column 7.
        val engine = FakeEngine("0123456789ABCDEF", cap = 6, anchor = 6)
        composeTestRule.setEditor(engine, echoDelayMs = 200L, initialCursor = TextPosition(0, 0, 6))
        composeTestRule.waitForIdle()

        val field = composeTestRule.onNodeWithTag(EDITOR_INPUT_TEST_TAG)
        field.performTextInput("\n")
        field.performTextInput("b")

        composeTestRule.waitUntil(timeoutMillis = 10_000) { engine.deltas.size == 2 }
        engine.deltas.first().start.line shouldBe 0L
        engine.deltas.first().start.column shouldBe 6
        engine.deltas.last().start.line shouldBe 1L
        engine.deltas.last().start.column shouldBe 0
    }

    @Test
    fun `a tap mid-burst places the next character at the tapped position`() {
        // The tap's engine round-trip is queued behind the unacknowledged keystroke, so the field
        // caret has to move locally or the next character lands where the caret was before the tap.
        val engine = FakeEngine("abcdef")
        composeTestRule.setEditor(engine, echoDelayMs = 200L, initialCursor = TextPosition(0, 0, 6))
        composeTestRule.waitForIdle()

        val field = composeTestRule.onNodeWithTag(EDITOR_INPUT_TEST_TAG)
        field.performTextInput("X")
        composeTestRule.onNodeWithTag(EDITOR_CONTENT_TEST_TAG).performTouchInput { click(topLeft) }
        field.performTextInput("Y")

        composeTestRule.waitUntil(timeoutMillis = 10_000) { engine.deltas.size == 2 }
        engine.deltas.last().start.column shouldBe 0
    }

    // ==================== Stale windows and stale rebuilds ====================

    @Test
    fun `a rebuild snapshot older than the composed window is discarded`() {
        val host = ManualHost("AB")
        composeTestRule.setManualEditor(host)
        composeTestRule.waitForIdle()
        composeTestRule.fieldText() shouldBe "AB"

        // Typed, dispatched, outcome still pending
        composeTestRule.onNodeWithTag(EDITOR_INPUT_TEST_TAG).performTextInput("X")
        composeTestRule.waitForIdle()
        host.deltas.size shouldBe 1

        // A queued paste advanced the document and ITS window is composed first
        composeTestRule.runOnIdle { host.publish("AB!", version = 2L) }
        composeTestRule.waitForIdle()

        // Only now does the keystroke come back conflicted, carrying a snapshot of the state at
        // the time of the rejection - one version behind what the field already shows
        composeTestRule.runOnIdle {
            host.outcomes.single().complete(
                EditorEngine.MutationResult.Conflict(host.snapshot("AB", version = 1L)),
            )
        }
        composeTestRule.waitForIdle()

        // Rebuilding from that payload would strand the field a version behind with nothing left to
        // re-trigger the sync: every later keystroke would chain on V1 and be dropped.
        composeTestRule.fieldText() shouldBe "AB!"
        composeTestRule.onNodeWithTag(EDITOR_INPUT_TEST_TAG).performTextInput("Y")
        composeTestRule.waitForIdle()
        host.deltas.last().snapshotToken shouldBe host.tokenAt(2L)
    }

    @Test
    fun `an intermediate window from an earlier acknowledgement does not erase in-flight input`() {
        val host = ManualHost("")
        composeTestRule.setManualEditor(host)
        composeTestRule.waitForIdle()

        val field = composeTestRule.onNodeWithTag(EDITOR_INPUT_TEST_TAG)
        field.performTextInput("a")
        field.performTextInput("b")
        composeTestRule.waitForIdle()
        host.deltas.size shouldBe 2
        composeTestRule.fieldText() shouldBe "ab"

        // The first keystroke is acknowledged and its window - newer than what the session rebased
        // on, but one character behind the field - is composed while the second is still in flight
        composeTestRule.runOnIdle {
            host.outcomes[0].complete(EditorEngine.MutationResult.Applied(host.tokenAt(1L)))
        }
        composeTestRule.waitForIdle()
        composeTestRule.runOnIdle { host.publish("a", version = 1L, cursorColumn = 1) }
        composeTestRule.waitForIdle()

        // Now the second acknowledgement lands: the session goes idle while that intermediate
        // window is the composed one. Rebasing on it would rebuild the field to "a".
        composeTestRule.runOnIdle {
            host.outcomes[1].complete(EditorEngine.MutationResult.Applied(host.tokenAt(2L)))
        }
        composeTestRule.waitForIdle()

        composeTestRule.fieldText() shouldBe "ab"
    }

    // ==================== Display-cap arbitration ====================

    @Test
    fun `typing at the cap boundary keeps the field usable`() {
        // 15-char single line, cap 10: field shows "xxxxxxxxxx"; typing at col 10 appends into
        // the hidden region - the echo's TEXT never changes, only its truncation count
        val engine = FakeEngine("x".repeat(15), cap = 10)
        val externalCursor = mutableStateOf<TextPosition?>(null)
        composeTestRule.setEditor(
            engine,
            initialCursor = TextPosition(0, 0, 10),
            externalCursor = externalCursor,
        )
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag(EDITOR_INPUT_TEST_TAG).performTextInput("Z")
        composeTestRule.waitForIdle()

        engine.content shouldBe "x".repeat(10) + "Z" + "x".repeat(5)
        // The delta was acknowledged, so an engine-authoritative caret move reaches the field again
        externalCursor.value = TextPosition(0, 0, 3)
        composeTestRule.waitForIdle()
        composeTestRule.fieldSelection() shouldBe TextRange(3)
    }

    @Test
    fun `prefix delete on a truncated line rebuilds the field from the new window`() {
        val engine = FakeEngine("abcdefghijklmno", cap = 10)
        composeTestRule.setEditor(engine, initialCursor = TextPosition(0, 0, 1))
        composeTestRule.waitForIdle()

        // Replace the field content minus its first char: diffs to a pure prefix-delete
        composeTestRule.onNodeWithTag(EDITOR_INPUT_TEST_TAG).performTextReplacement("bcdefghij")
        composeTestRule.waitForIdle()

        engine.content shouldBe "bcdefghijklmno"
        engine.deltas.single().newText shouldBe ""
        // The echo pulled a hidden char into view; the new window rebased and rebuilt the field
        composeTestRule.fieldText() shouldBe "bcdefghijk"
    }

    @Test
    fun `prefix typing on a truncated line produces correct deltas`() {
        val engine = FakeEngine("0123456789ABCDE", cap = 10)
        composeTestRule.setEditor(engine, initialCursor = TextPosition(0, 0, 2))
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag(EDITOR_INPUT_TEST_TAG).performTextInput("XY")
        composeTestRule.waitForIdle()

        engine.content shouldBe "01XY23456789ABCDE"
        val delta = engine.deltas.first()
        delta.start.line shouldBe 0L
        delta.start.column shouldBe 2
        delta.end.column shouldBe 2
        delta.newText shouldBe "XY"
    }
}
