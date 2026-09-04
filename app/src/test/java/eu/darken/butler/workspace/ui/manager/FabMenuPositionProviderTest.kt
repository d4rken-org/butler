package eu.darken.butler.workspace.ui.manager

import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import testhelpers.BaseTest

class FabMenuPositionProviderTest : BaseTest() {

    private val provider = FabMenuPositionProvider(gapPx = 21, edgeInsetPx = 21)
    private val anchor = IntRect(700, 2200, 1000, 2350)
    private val window = IntSize(1080, 2400)

    private fun position(
        layoutDirection: LayoutDirection = LayoutDirection.Ltr,
        popupContentSize: IntSize = IntSize(400, 700),
    ) = provider.calculatePosition(
        anchorBounds = anchor,
        windowSize = window,
        layoutDirection = layoutDirection,
        popupContentSize = popupContentSize,
    )

    @Test
    fun `ltr aligns the visible chips with the anchor's right edge above the gap`() {
        position(LayoutDirection.Ltr) shouldBe IntOffset(621, 1479)
    }

    @Test
    fun `rtl aligns the visible chips with the anchor's left edge above the gap`() {
        position(LayoutDirection.Rtl) shouldBe IntOffset(679, 1479)
    }

    @Test
    fun `a popup wider than the window is clamped to the left edge`() {
        position(popupContentSize = IntSize(1200, 700)).x shouldBe 0
    }

    @Test
    fun `a popup taller than the space above the anchor is clamped to the top`() {
        position(popupContentSize = IntSize(400, 2500)).y shouldBe 0
    }
}
