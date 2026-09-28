package eu.darken.butler.common.compose

import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.LayoutDirection
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import testhelpers.BaseTest

class PathTextStyleTest : BaseTest() {

    private fun style(align: TextAlign) = TextStyle(textAlign = align)

    private fun TextStyle.alignIn(layoutDirection: LayoutDirection, override: TextAlign? = null) =
        asPathStyle(layoutDirection, textAlign = override).textAlign

    @Test
    fun `the direction is left to right by default`() {
        TextStyle().asPathStyle(LayoutDirection.Rtl).textDirection shouldBe TextDirection.Ltr
        TextStyle().asPathStyle(LayoutDirection.Ltr).textDirection shouldBe TextDirection.Ltr
    }

    @Test
    fun `a content direction passes through`() {
        TextStyle().asPathStyle(LayoutDirection.Rtl, textDirection = TextDirection.Content)
            .textDirection shouldBe TextDirection.Content
    }

    @Test
    fun `an unspecified alignment stays on the start edge of the layout`() {
        style(TextAlign.Unspecified).alignIn(LayoutDirection.Rtl) shouldBe TextAlign.Right
        style(TextAlign.Unspecified).alignIn(LayoutDirection.Ltr) shouldBe TextAlign.Left
    }

    @Test
    fun `an explicit alignment beats an unspecified one`() {
        style(TextAlign.Unspecified).alignIn(LayoutDirection.Rtl, TextAlign.Center) shouldBe TextAlign.Center
        style(TextAlign.Unspecified).alignIn(LayoutDirection.Ltr, TextAlign.End) shouldBe TextAlign.Right
        style(TextAlign.Unspecified).alignIn(LayoutDirection.Rtl, TextAlign.End) shouldBe TextAlign.Left
    }

    @Test
    fun `a centered style stays centered`() {
        style(TextAlign.Center).alignIn(LayoutDirection.Rtl) shouldBe TextAlign.Center
        style(TextAlign.Center).alignIn(LayoutDirection.Ltr) shouldBe TextAlign.Center
    }

    @Test
    fun `an explicit alignment beats a centered style`() {
        style(TextAlign.Center).alignIn(LayoutDirection.Rtl, TextAlign.Start) shouldBe TextAlign.Right
        style(TextAlign.Center).alignIn(LayoutDirection.Ltr, TextAlign.Start) shouldBe TextAlign.Left
    }

    @Test
    fun `a physical right alignment is kept as is`() {
        style(TextAlign.Right).alignIn(LayoutDirection.Rtl) shouldBe TextAlign.Right
        style(TextAlign.Right).alignIn(LayoutDirection.Ltr) shouldBe TextAlign.Right
    }

    @Test
    fun `an explicit alignment beats a right aligned style`() {
        style(TextAlign.Right).alignIn(LayoutDirection.Rtl, TextAlign.Left) shouldBe TextAlign.Left
        style(TextAlign.Right).alignIn(LayoutDirection.Ltr, TextAlign.Center) shouldBe TextAlign.Center
    }

    @Test
    fun `an end aligned style resolves to the end edge of the layout`() {
        style(TextAlign.End).alignIn(LayoutDirection.Rtl) shouldBe TextAlign.Left
        style(TextAlign.End).alignIn(LayoutDirection.Ltr) shouldBe TextAlign.Right
    }

    @Test
    fun `an explicit alignment beats an end aligned style`() {
        style(TextAlign.End).alignIn(LayoutDirection.Rtl, TextAlign.Start) shouldBe TextAlign.Right
        style(TextAlign.End).alignIn(LayoutDirection.Ltr, TextAlign.Justify) shouldBe TextAlign.Justify
    }

    @Test
    fun `other style fields survive`() {
        val source = TextStyle(fontFamily = FontFamily.Monospace)

        source.asPathStyle(LayoutDirection.Rtl).fontFamily shouldBe FontFamily.Monospace
    }
}
