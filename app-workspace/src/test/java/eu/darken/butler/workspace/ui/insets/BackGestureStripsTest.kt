package eu.darken.butler.workspace.ui.insets

import androidx.compose.ui.unit.LayoutDirection
import eu.darken.butler.workspace.ui.manager.WorkspaceDesign.PaneEdges
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import testhelpers.BaseTest

class BackGestureStripsTest : BaseTest() {

    @Test
    fun `a pane that reaches both edges gets the wider inset on both sides`() {
        edgeStripWidths(PaneEdges.All, LayoutDirection.Ltr, leftInsetPx = 40, rightInsetPx = 60) shouldBe (60 to 60)
        edgeStripWidths(PaneEdges.All, LayoutDirection.Rtl, leftInsetPx = 40, rightInsetPx = 60) shouldBe (60 to 60)
    }

    @Test
    fun `a start rail removes the left strip in LTR`() {
        val besideStartRail = PaneEdges.All.withoutEdges(start = true)

        edgeStripWidths(besideStartRail, LayoutDirection.Ltr, leftInsetPx = 40, rightInsetPx = 60) shouldBe (0 to 60)
    }

    @Test
    fun `a start rail removes the right strip in RTL`() {
        val besideStartRail = PaneEdges.All.withoutEdges(start = true)

        edgeStripWidths(besideStartRail, LayoutDirection.Rtl, leftInsetPx = 40, rightInsetPx = 60) shouldBe (60 to 0)
    }

    @Test
    fun `a bottom rail leaves both strips in place`() {
        val aboveBottomRail = PaneEdges.All.withoutEdges(bottom = true)

        edgeStripWidths(aboveBottomRail, LayoutDirection.Ltr, leftInsetPx = 40, rightInsetPx = 60) shouldBe (60 to 60)
    }

    @Test
    fun `no gesture insets means no strips`() {
        edgeStripWidths(PaneEdges.All, LayoutDirection.Ltr, leftInsetPx = 0, rightInsetPx = 0) shouldBe (0 to 0)
    }
}
