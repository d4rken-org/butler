package eu.darken.butler.editor.ui.editor.text

import androidx.compose.ui.geometry.Offset
import io.kotest.matchers.shouldBe
import org.junit.Test

class MagnifierSourceInContainerTest {

    private val caretLine = CaretLine(x = 30f, top = 40f, bottom = 60f)

    private fun source(
        gutterWidthPx: Float = 0f,
        horizontalScrollPx: Float = 0f,
        wordWrap: Boolean = false,
    ) = magnifierSourceInContainer(
        itemContainerY = 100f,
        caretLine = caretLine,
        gutterWidthPx = gutterWidthPx,
        textInsetPx = 8f,
        lineTopInsetPx = 2f,
        horizontalScrollPx = horizontalScrollPx,
        wordWrap = wordWrap,
    )

    @Test
    fun `centres on the visual line below the top inset`() {
        source() shouldBe Offset(x = 8f + 30f, y = 100f + 2f + 50f)
    }

    @Test
    fun `adds the gutter`() {
        source(gutterWidthPx = 48f).x shouldBe 48f + 8f + 30f
    }

    @Test
    fun `subtracts the horizontal scroll without word wrap`() {
        source(gutterWidthPx = 48f, horizontalScrollPx = 20f).x shouldBe 48f + 8f + 30f - 20f
    }

    @Test
    fun `ignores the horizontal scroll with word wrap`() {
        source(gutterWidthPx = 48f, horizontalScrollPx = 20f, wordWrap = true).x shouldBe 48f + 8f + 30f
    }

    @Test
    fun `scroll and wrap leave y alone`() {
        source(horizontalScrollPx = 20f).y shouldBe 152f
        source(horizontalScrollPx = 20f, wordWrap = true).y shouldBe 152f
    }
}
