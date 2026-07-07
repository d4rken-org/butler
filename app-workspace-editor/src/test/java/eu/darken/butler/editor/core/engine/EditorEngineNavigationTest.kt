package eu.darken.butler.editor.core.engine

import eu.darken.butler.editor.ui.editor.text.CursorDirection
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

/**
 * Cursor navigation through [EditorEngine.moveCursor] - previously fully untested despite
 * being the largest untested surface in the engine.
 */
class EditorEngineNavigationTest : EditorEngineTestBase() {

    // Content layout (offsets):
    // line 0: "alpha beta"   offsets 0..9,  break at 10
    // line 1: "gamma_x d"    offsets 11..19, break at 20
    // line 2: "end"          offsets 21..23
    private val content = "alpha beta\ngamma_x d\nend"

    private suspend fun engineAt(line: Long, column: Int): EditorEngine {
        val engine = createEngine(content)
        engine.setCursorPosition(TextPosition(offset = 0, line = line, column = column))
        return engine
    }

    @Test
    fun `right moves within the line and wraps to the next line start`() = runTest {
        val engine = engineAt(line = 0, column = 9)

        engine.moveCursor(CursorDirection.RIGHT, extendSelection = false)
        engine.cursorPosition.value.let { it.line shouldBe 0L; it.column shouldBe 10; it.offset shouldBe 10L }

        engine.moveCursor(CursorDirection.RIGHT, extendSelection = false)
        engine.cursorPosition.value.let { it.line shouldBe 1L; it.column shouldBe 0; it.offset shouldBe 11L }
    }

    @Test
    fun `left wraps to the previous line end and stops at document start`() = runTest {
        val engine = engineAt(line = 1, column = 0)

        engine.moveCursor(CursorDirection.LEFT, extendSelection = false)
        engine.cursorPosition.value.let { it.line shouldBe 0L; it.column shouldBe 10; it.offset shouldBe 10L }

        engine.setCursorPosition(TextPosition(offset = 0, line = 0, column = 0))
        engine.moveCursor(CursorDirection.LEFT, extendSelection = false)
        engine.cursorPosition.value.let { it.line shouldBe 0L; it.column shouldBe 0 }
    }

    @Test
    fun `vertical movement clamps the column to the target line length`() = runTest {
        val engine = engineAt(line = 1, column = 9)

        engine.moveCursor(CursorDirection.DOWN, extendSelection = false)
        // "end" is 3 chars - column clamps from 9 to 3
        engine.cursorPosition.value.let { it.line shouldBe 2L; it.column shouldBe 3; it.offset shouldBe 24L }

        engine.moveCursor(CursorDirection.DOWN, extendSelection = false)
        // Already on the last line - no movement
        engine.cursorPosition.value.line shouldBe 2L

        engine.moveCursor(CursorDirection.UP, extendSelection = false)
        engine.moveCursor(CursorDirection.UP, extendSelection = false)
        engine.cursorPosition.value.let { it.line shouldBe 0L; it.column shouldBe 3 }

        engine.moveCursor(CursorDirection.UP, extendSelection = false)
        engine.cursorPosition.value.line shouldBe 0L
    }

    @Test
    fun `word movement skips word chars and whitespace`() = runTest {
        val engine = engineAt(line = 0, column = 0)

        engine.moveCursor(CursorDirection.WORD_RIGHT, extendSelection = false)
        // Past "alpha" and the following space
        engine.cursorPosition.value.let { it.column shouldBe 6; it.offset shouldBe 6L }

        engine.moveCursor(CursorDirection.WORD_LEFT, extendSelection = false)
        engine.cursorPosition.value.let { it.column shouldBe 0; it.offset shouldBe 0L }
    }

    @Test
    fun `word movement treats underscore as a word char and crosses line ends`() = runTest {
        val engine = engineAt(line = 1, column = 0)

        engine.moveCursor(CursorDirection.WORD_RIGHT, extendSelection = false)
        // "gamma_x" is one word (underscore included), then the space is skipped
        engine.cursorPosition.value.let { it.column shouldBe 8; it.offset shouldBe 19L }

        engine.moveCursor(CursorDirection.WORD_RIGHT, extendSelection = false)
        // Past "d" = end of line -> start of next line
        engine.cursorPosition.value.let { it.line shouldBe 2L; it.column shouldBe 0; it.offset shouldBe 21L }
    }

    @Test
    fun `line start and end jump within the current line`() = runTest {
        val engine = engineAt(line = 1, column = 4)

        engine.moveCursor(CursorDirection.LINE_END, extendSelection = false)
        engine.cursorPosition.value.let { it.line shouldBe 1L; it.column shouldBe 9; it.offset shouldBe 20L }

        engine.moveCursor(CursorDirection.LINE_START, extendSelection = false)
        engine.cursorPosition.value.let { it.line shouldBe 1L; it.column shouldBe 0; it.offset shouldBe 11L }
    }

    @Test
    fun `extending selection anchors at the start and follows the cursor`() = runTest {
        val engine = engineAt(line = 0, column = 0)

        engine.moveCursor(CursorDirection.RIGHT, extendSelection = true)
        engine.moveCursor(CursorDirection.RIGHT, extendSelection = true)

        val selection = engine.selectionRange.value
        selection shouldNotBe null
        selection!!.first.offset shouldBe 0L
        selection.second.offset shouldBe 2L

        // Plain movement clears the selection
        engine.moveCursor(CursorDirection.RIGHT, extendSelection = false)
        engine.selectionRange.value shouldBe null
    }

    @Test
    fun `extending selection backwards keeps the range ordered`() = runTest {
        val engine = engineAt(line = 0, column = 5)
        // setCursorPosition resolves the real offset internally
        engine.setCursorPosition(TextPosition(offset = 0, line = 0, column = 5))

        engine.moveCursor(CursorDirection.LEFT, extendSelection = true)
        engine.moveCursor(CursorDirection.LEFT, extendSelection = true)

        val selection = engine.selectionRange.value!!
        selection.first.offset shouldBe 3L
        selection.second.offset shouldBe 5L
    }

    @Test
    fun `selectAll spans the whole document`() = runTest {
        val engine = createEngine(content)

        val (start, end) = engine.selectAll().getOrThrow()

        start.offset shouldBe 0L
        end.offset shouldBe content.length.toLong()
        end.line shouldBe 2L
        end.column shouldBe 3
        engine.selectionRange.value shouldBe (start to end)
    }
}
