package eu.darken.butler.common.compose

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import io.kotest.matchers.floats.plusOrMinus
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test
import testhelpers.BaseTest

class CircularRevealTest : BaseTest() {

    private val size = Size(300f, 400f)

    private fun outlineAt(progress: Float, origin: Offset?) = CircularRevealShape(progress, origin)
        .createOutline(size, LayoutDirection.Ltr, Density(1f))

    @Test
    fun `a corner reaches across the diagonal`() {
        val diagonal = 500f
        maxRevealRadius(Offset(0f, 0f), size) shouldBe (diagonal plusOrMinus 0.01f)
        maxRevealRadius(Offset(300f, 0f), size) shouldBe (diagonal plusOrMinus 0.01f)
        maxRevealRadius(Offset(0f, 400f), size) shouldBe (diagonal plusOrMinus 0.01f)
        maxRevealRadius(Offset(300f, 400f), size) shouldBe (diagonal plusOrMinus 0.01f)
    }

    @Test
    fun `the centre reaches half the diagonal`() {
        maxRevealRadius(Offset(150f, 200f), size) shouldBe (250f plusOrMinus 0.01f)
    }

    @Test
    fun `an off-centre origin measures to the farthest corner`() {
        // Near the top-left, so the bottom-right corner is the one that decides: hypot(290, 390).
        maxRevealRadius(Offset(10f, 10f), size) shouldBe (486.0f plusOrMinus 0.1f)
    }

    @Test
    fun `a finished reveal is the plain rect`() {
        val outline = outlineAt(progress = 1f, origin = Offset(10f, 10f))
            .shouldBeInstanceOf<Outline.Rectangle>()
        outline.rect.left shouldBe 0f
        outline.rect.top shouldBe 0f
        outline.rect.right shouldBe 300f
        outline.rect.bottom shouldBe 400f
    }

    @Test
    fun `a running reveal is a circle around the origin`() {
        val origin = Offset(10f, 10f)
        val outline = outlineAt(progress = 0.5f, origin = origin)
            .shouldBeInstanceOf<Outline.Rounded>()

        val radius = 486.0f / 2f
        outline.roundRect.left shouldBe ((origin.x - radius) plusOrMinus 0.1f)
        outline.roundRect.top shouldBe ((origin.y - radius) plusOrMinus 0.1f)
        outline.roundRect.right shouldBe ((origin.x + radius) plusOrMinus 0.1f)
        outline.roundRect.bottom shouldBe ((origin.y + radius) plusOrMinus 0.1f)
        outline.roundRect.topLeftCornerRadius.x shouldBe (radius plusOrMinus 0.1f)
        outline.roundRect.topLeftCornerRadius.y shouldBe (radius plusOrMinus 0.1f)
    }

    @Test
    fun `no origin reveals from the centre`() {
        val outline = outlineAt(progress = 0.5f, origin = null)
            .shouldBeInstanceOf<Outline.Rounded>()

        val radius = 250f / 2f
        outline.roundRect.left shouldBe ((150f - radius) plusOrMinus 0.1f)
        outline.roundRect.top shouldBe ((200f - radius) plusOrMinus 0.1f)
        outline.roundRect.right shouldBe ((150f + radius) plusOrMinus 0.1f)
        outline.roundRect.bottom shouldBe ((200f + radius) plusOrMinus 0.1f)
    }
}
