package eu.darken.butler.editor.core.engine

import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

/**
 * The lazy-window mechanism the UI drives while scrolling large files - previously only
 * incidentally covered. Content is windowed by line range; sub-3-line shifts are debounced.
 */
class EditorEngineVisibleRangeTest : EditorEngineTestBase() {

    private val lines = (0 until 500).map { "Line %03d content".format(java.util.Locale.ROOT, it) }
    private val content = lines.joinToString("\n")

    @Test
    fun `initialization loads the first window`() = runTest {
        val engine = createEngine(content)

        engine.visibleRange.value shouldBe 0L..50L
        engine.currentContent.value shouldBe lines.subList(0, 51).joinToString("\n")
    }

    @Test
    fun `scrolling loads exactly the requested window's lines`() = runTest {
        val engine = createEngine(content)

        engine.updateVisibleRange(200, 260)

        engine.visibleRange.value shouldBe 200L..260L
        engine.currentContent.value shouldBe lines.subList(200, 261).joinToString("\n")
    }

    @Test
    fun `sub-3-line shifts are ignored to debounce scroll updates`() = runTest {
        val engine = createEngine(content)
        engine.updateVisibleRange(200, 260)

        engine.updateVisibleRange(202, 262)

        engine.visibleRange.value shouldBe 200L..260L
        engine.currentContent.value shouldBe lines.subList(200, 261).joinToString("\n")
    }

    @Test
    fun `window requests past the document end are clamped`() = runTest {
        val engine = createEngine(content)

        engine.updateVisibleRange(480, 600)

        engine.visibleRange.value shouldBe 480L..499L
        engine.currentContent.value shouldBe lines.subList(480, 500).joinToString("\n")
    }

    @Test
    fun `window content reflects edits in the visible range`() = runTest {
        val engine = createEngine(content)
        engine.updateVisibleRange(100, 150)

        engine.setCursorPosition(TextPosition(offset = 0, line = 120, column = 0))
        engine.insertText("XX")

        engine.currentContent.value.lineSequence().first { it.contains("120") } shouldBe "XXLine 120 content"
    }
}
