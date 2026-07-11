package eu.darken.butler.editor.core.engine

import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

/**
 * Scroll-driven horizontal reveal ([EditorEngine.revealMoreColumns]): panning the shared display window
 * along a long line WITHOUT moving the caret. Complements [EditorEngineDisplayCapTest] (cursor-driven).
 */
class EditorEngineSlidingWindowTest : EditorEngineTestBase() {

    private val cap = 10

    // 20-char line 0 (windowed), short line 1. Furthest full-window start on line 0 is column 10.
    private val content = "0123456789ABCDEFGHIJ\nshort"

    @Test
    fun `reveal forward advances the window anchor by half a cap`() = runTest {
        val engine = createEngine(content, displayLineCap = cap)

        engine.revealMoreColumns(forward = true)

        // page = cap / 2 = 5 -> anchor 5, window [5,15)
        engine.visibleContent.value.text shouldBe "56789ABCDE\nshort"
        engine.visibleContent.value.startColumns shouldBe mapOf(0L to 5L)
        engine.visibleContent.value.truncatedLines shouldBe mapOf(0L to 5L)
    }

    @Test
    fun `reveal forward is bounded at the furthest full window and then a no-op`() = runTest {
        val engine = createEngine(content, displayLineCap = cap)

        engine.revealMoreColumns(forward = true) // anchor 5
        engine.revealMoreColumns(forward = true) // 10 (clamped to maxAnchor)
        engine.revealMoreColumns(forward = true) // no-op at the bound

        engine.visibleContent.value.text shouldBe "ABCDEFGHIJ\nshort"
        engine.visibleContent.value.startColumns shouldBe mapOf(0L to 10L)
        engine.visibleContent.value.truncatedLines shouldBe emptyMap() // window at the line end
    }

    @Test
    fun `reveal backward retreats the anchor and does not go below zero`() = runTest {
        val engine = createEngine(content, displayLineCap = cap)
        engine.revealMoreColumns(forward = true) // anchor 5

        engine.revealMoreColumns(forward = false) // back to 0
        engine.visibleContent.value.text shouldBe "0123456789\nshort"
        engine.visibleContent.value.startColumns shouldBe emptyMap()

        engine.revealMoreColumns(forward = false) // already at 0 -> no-op
        engine.visibleContent.value.startColumns shouldBe emptyMap()
    }

    @Test
    fun `one reveal slides the shared window for all visible long lines`() = runTest {
        // The window anchor is SHARED: revealing from any one line's marker moves every windowed
        // line, with per-line counts reflecting each line's own length
        val engine = createEngine("0123456789ABCDEFGHIJ\nabcdefghijklmnopqrstuvwxyz0123", displayLineCap = cap)

        engine.revealMoreColumns(forward = true)

        engine.visibleContent.value.text shouldBe "56789ABCDE\nfghijklmno"
        engine.visibleContent.value.startColumns shouldBe mapOf(0L to 5L, 1L to 5L)
        engine.visibleContent.value.truncatedLines shouldBe mapOf(0L to 5L, 1L to 15L)
    }

    @Test
    fun `reveal does not move the caret`() = runTest {
        val engine = createEngine(content, displayLineCap = cap)
        engine.setCursorPosition(TextPosition(offset = 0, line = 0, column = 2))

        engine.revealMoreColumns(forward = true)

        // The window slid, but the caret stayed put (scroll must not re-position the cursor).
        engine.cursorPosition.value.line shouldBe 0L
        engine.cursorPosition.value.column shouldBe 2
    }
}
