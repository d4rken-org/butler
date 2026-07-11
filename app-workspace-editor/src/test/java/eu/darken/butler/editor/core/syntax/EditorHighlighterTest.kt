package eu.darken.butler.editor.core.syntax

import eu.darken.butler.editor.core.engine.DocumentBuffer
import eu.darken.butler.editor.core.engine.DocumentBufferTestBase
import eu.darken.butler.editor.core.engine.EditorEngine
import eu.darken.butler.editor.core.engine.TextPosition
import io.kotest.matchers.maps.shouldBeEmpty
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import testhelpers.coroutine.TestDispatcherProvider

class EditorHighlighterTest : DocumentBufferTestBase() {

    private class Harness(
        buffer: DocumentBuffer?,
        language: Language? = Language.JAVASCRIPT,
        enabledInitially: Boolean = true,
    ) {
        val enabled = MutableStateFlow(enabledInitially)
        val visibleContent = MutableStateFlow(EditorEngine.VisibleContent())
        val visibleRange = MutableStateFlow<LongRange>(0L..0L)
        val highlighter = EditorHighlighter(
            language = language,
            enabled = enabled,
            visibleContent = visibleContent,
            visibleRange = visibleRange,
            structuralVersion = buffer?.structuralVersionFlow ?: MutableStateFlow(-1L),
            bufferProvider = { buffer },
            dispatcherProvider = TestDispatcherProvider(),
        )

        suspend fun showWindow(buffer: DocumentBuffer, range: LongRange) {
            val window = buffer.getDisplayRange(range.first, range.last).getOrThrow()
            visibleRange.value = range
            visibleContent.value = EditorEngine.VisibleContent(window.text, window.truncatedLines, window.startColumns)
        }
    }

    /** Line 0 opens a block comment that never closes; lines 1..600 are plain JS-ish text. */
    private fun commentDocument(): String = buildString {
        appendLine("/* opened")
        repeat(600) { appendLine("body line $it") }
        append("tail")
    }

    @Test
    fun `tokenizes the visible window`() = runTest {
        val buffer = createBuffer("const x = 1\n// comment\nplain")
        val harness = Harness(buffer)
        harness.showWindow(buffer, 0L..2L)

        val result = harness.highlighter.highlightedLines.first { it.isNotEmpty() }

        result.getValue(0L).map { it.type } shouldBe listOf(TokenType.KEYWORD, TokenType.NUMBER)
        result.getValue(1L).single().type shouldBe TokenType.COMMENT
        result.containsKey(2L) shouldBe false // no tokens on plain identifier lines
    }

    @Test
    fun `lookback within bound resolves cross-line state exactly`() = runTest {
        val buffer = createBuffer(commentDocument())
        val harness = Harness(buffer)
        // windowStart 300: lookback clamps to line 0, so the unclosed comment is found
        harness.showWindow(buffer, 300L..310L)

        val result = harness.highlighter.highlightedLines.first { it.containsKey(300L) }

        result.getValue(300L).single().type shouldBe TokenType.COMMENT
    }

    @Test
    fun `cold jump past the lookback bound misses the opener - pinned inaccuracy`() = runTest {
        val buffer = createBuffer(commentDocument())
        val harness = Harness(buffer)
        // windowStart 500: lookback reaches line 100, the opener at line 0 stays unseen
        harness.showWindow(buffer, 500L..510L)

        val result = harness.highlighter.highlightedLines.first { it.containsKey(500L) }

        result.getValue(500L).single().type shouldBe TokenType.NUMBER
    }

    @Test
    fun `checkpoints keep sequential scrolling exact past the lookback bound`() = runTest {
        val buffer = createBuffer(commentDocument())
        val harness = Harness(buffer)
        harness.showWindow(buffer, 300L..310L)
        harness.highlighter.highlightedLines.first { it.containsKey(300L) }

        // 550 is >400 lines past the opener, but the 300-window scan left checkpoints behind
        harness.showWindow(buffer, 550L..560L)
        val result = harness.highlighter.highlightedLines.first { it.containsKey(550L) }

        result.getValue(550L).single().type shouldBe TokenType.COMMENT
    }

    @Test
    fun `edits invalidate checkpoints via structural version`() = runTest {
        val buffer = createBuffer(commentDocument())
        val harness = Harness(buffer)
        harness.showWindow(buffer, 300L..310L)
        harness.highlighter.highlightedLines.first { it.containsKey(300L) }
            .getValue(300L).single().type shouldBe TokenType.COMMENT

        // Break the opener: "/* opened" -> "/x* opened"; stale checkpoints must not survive
        buffer.insertText(TextPosition(offset = 1, line = 0, column = 1), "x").getOrThrow()
        harness.showWindow(buffer, 300L..310L)

        val result = harness.highlighter.highlightedLines.first {
            it.containsKey(300L) && it.getValue(300L).single().type != TokenType.COMMENT
        }
        result.getValue(300L).single().type shouldBe TokenType.NUMBER
    }

    @Test
    fun `mutation that leaves the window text unchanged still recomputes during active collection`() = runTest {
        val buffer = createBuffer(commentDocument())
        val harness = Harness(buffer)
        harness.showWindow(buffer, 300L..310L)

        val emissions = Channel<Map<Long, List<Token>>>(Channel.UNLIMITED)
        val collector = launch { harness.highlighter.highlightedLines.collect { emissions.send(it) } }
        while (emissions.receive()[300L]?.singleOrNull()?.type != TokenType.COMMENT) {
            // drain until the comment-state window is highlighted
        }

        // Break the opener above the window; the window's own text stays byte-identical, so only
        // the structural-version input can trigger the recompute (replace-all/undo scenario)
        buffer.insertText(TextPosition(offset = 1, line = 0, column = 1), "x").getOrThrow()
        while (emissions.receive()[300L]?.singleOrNull()?.type != TokenType.NUMBER) {
            // drain until the corrected tokens arrive
        }
        collector.cancel()
    }

    @Test
    fun `disabled setting yields no tokens and re-enabling recomputes`() = runTest {
        val buffer = createBuffer("const x = 1")
        val harness = Harness(buffer, enabledInitially = false)
        harness.showWindow(buffer, 0L..0L)

        harness.highlighter.highlightedLines.drop(1).first().shouldBeEmpty()

        harness.enabled.value = true
        harness.highlighter.highlightedLines.first { it.isNotEmpty() }
            .getValue(0L).map { it.type } shouldBe listOf(TokenType.KEYWORD, TokenType.NUMBER)

        harness.enabled.value = false
        harness.highlighter.highlightedLines.drop(1).first().shouldBeEmpty()
    }

    @Test
    fun `unsupported language yields a constant empty flow`() = runTest {
        val buffer = createBuffer("const x = 1")
        val harness = Harness(buffer, language = null)
        harness.showWindow(buffer, 0L..0L)

        harness.highlighter.highlightedLines.first().shouldBeEmpty()
    }

    @Test
    fun `mid-line-anchored slices are skipped and state passes through`() = runTest {
        val longLine = "abcdefghijklmnopqrstuvwxyz1234" // 30 chars, cap 20 -> anchor can stick
        val buffer = createBuffer("const a = 1\n$longLine\nconst b = 2", maxDisplayLineChars = 20)
        val harness = Harness(buffer)
        val window = buffer.getDisplayRange(0L, 2L, columnAnchors = mapOf(1L to 3L)).getOrThrow()
        window.startColumns.getValue(1L) shouldBe 3L // precondition: line 1 really is anchored
        harness.visibleRange.value = 0L..2L
        harness.visibleContent.value =
            EditorEngine.VisibleContent(window.text, window.truncatedLines, window.startColumns)

        val result = harness.highlighter.highlightedLines.first { it.isNotEmpty() }

        // Anchored line 1 got no tokens; the following line still tokenized from passed-through state
        result.containsKey(1L) shouldBe false
        result.getValue(2L).map { it.type } shouldBe listOf(TokenType.KEYWORD, TokenType.NUMBER)
    }
}
