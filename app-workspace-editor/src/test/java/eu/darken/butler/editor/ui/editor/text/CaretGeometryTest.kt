package eu.darken.butler.editor.ui.editor.text

import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.sp
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.Test
import org.robolectric.annotation.GraphicsMode
import testhelpers.ComposeTest

/**
 * Runs under [GraphicsMode.Mode.NATIVE]: the default mode measures about 1px per character and maps
 * every column past the first onto the line's full width, so neither wrapping nor per-glyph x exists.
 */
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CaretGeometryTest : ComposeTest() {

    private val style = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 14.sp)

    /** Ten glyphs per visual line: a digit run has no word breaks, so it wraps between glyphs. */
    private val wrappedLine = "0123456789".repeat(3)

    private fun measurer(): TextMeasurer {
        lateinit var measurer: TextMeasurer
        composeTestRule.setContent { measurer = rememberTextMeasurer() }
        composeTestRule.waitForIdle()
        return measurer
    }

    private fun TextMeasurer.layout(text: String, maxWidth: Int = Constraints.Infinity) = measure(
        text = text,
        style = style,
        softWrap = true,
        constraints = Constraints(maxWidth = maxWidth),
    )

    private fun TextMeasurer.wrappedLayout(): TextLayoutResult {
        val glyphWidth = layout("0").size.width
        val layout = layout(wrappedLine, maxWidth = glyphWidth * 10 + glyphWidth / 2)
        layout.lineCount shouldBeGreaterThan 1
        layout.getBoundingBox(2) shouldNotBe layout.getBoundingBox(5)
        return layout
    }

    @Test
    fun `column on a wrapped continuation line takes that visual line and glyph`() {
        val layout = measurer().wrappedLayout()
        val wrapAt = layout.getLineStart(1)
        wrapAt shouldBeGreaterThan 0
        layout.getLineTop(1) shouldNotBe layout.getLineTop(0)

        val geometry = caretGeometry(layout, wrappedLine, localColumn = wrapAt + 2, tabSize = 4)

        geometry.x shouldBe layout.getBoundingBox(wrapAt + 2).left
        geometry.lineTop shouldBe layout.getLineTop(1)
        geometry.measuredLine shouldBe CaretLine(
            x = layout.getBoundingBox(wrapAt + 2).left,
            top = layout.getLineTop(1),
            bottom = layout.getLineBottom(1),
        )
    }

    @Test
    fun `column at a wrap point sits at the end of the previous visual line`() {
        val layout = measurer().wrappedLayout()
        val wrapAt = layout.getLineStart(1)
        layout.getBoundingBox(wrapAt - 1).right shouldNotBe layout.getBoundingBox(wrapAt).left

        val geometry = caretGeometry(layout, wrappedLine, localColumn = wrapAt, tabSize = 4)

        geometry.x shouldBe layout.getBoundingBox(wrapAt - 1).right
        geometry.lineTop shouldBe layout.getLineTop(0)
        geometry.measuredLine shouldBe CaretLine(
            x = layout.getBoundingBox(wrapAt - 1).right,
            top = layout.getLineTop(0),
            bottom = layout.getLineBottom(0),
        )
    }

    @Test
    fun `column past the end sits after the last glyph on the last visual line`() {
        val layout = measurer().wrappedLayout()
        val lastLine = layout.lineCount - 1

        val geometry = caretGeometry(layout, wrappedLine, localColumn = wrappedLine.length + 5, tabSize = 4)

        geometry.x shouldBe layout.getBoundingBox(wrappedLine.length - 1).right
        geometry.measuredLine shouldBe CaretLine(
            x = layout.getBoundingBox(wrappedLine.length - 1).right,
            top = layout.getLineTop(lastLine),
            bottom = layout.getLineBottom(lastLine),
        )
    }

    @Test
    fun `raw column is tab expanded before indexing the layout`() {
        val measurer = measurer()
        val rawLine = "\tab"
        val layout = measurer.layout(rawLine.toDisplayText(4))
        layout.getBoundingBox(1) shouldNotBe layout.getBoundingBox(4)

        caretGeometry(layout, rawLine, localColumn = 1, tabSize = 4).x shouldBe layout.getBoundingBox(4).left
    }

    @Test
    fun `empty line keeps the caret at the origin and measures the placeholder`() {
        val layout = measurer().layout(" ")

        val geometry = caretGeometry(layout, "", localColumn = 0, tabSize = 4)

        geometry.x shouldBe 0f
        geometry.lineTop shouldBe 0f
        val line = geometry.measuredLine.shouldNotBeNull()
        line shouldBe CaretLine(x = 0f, top = layout.getLineTop(0), bottom = layout.getLineBottom(0))
        (line.bottom > line.top) shouldBe true
    }

    @Test
    fun `empty line without a layout has no measured line`() {
        caretGeometry(null, "", localColumn = 0, tabSize = 4) shouldBe
            CaretGeometry(x = 0f, lineTop = 0f, measuredLine = null)
    }

    @Test
    fun `no layout falls back and has no measured line`() {
        caretGeometry(null, "abcdef", localColumn = 3, tabSize = 4) shouldBe
            CaretGeometry(x = null, lineTop = 0f, measuredLine = null)
    }

    @Test
    fun `stale layout shorter than the line falls back and has no measured line`() {
        val stale = measurer().layout("abc")

        caretGeometry(stale, "abcdef", localColumn = 3, tabSize = 4) shouldBe
            CaretGeometry(x = null, lineTop = 0f, measuredLine = null)
    }
}
