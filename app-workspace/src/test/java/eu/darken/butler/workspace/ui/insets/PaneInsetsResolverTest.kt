package eu.darken.butler.workspace.ui.insets

import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import eu.darken.butler.workspace.ui.floatingbar.BarPosition
import eu.darken.butler.workspace.ui.manager.WorkspaceDesign.PaneEdges
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import testhelpers.BaseTest

/**
 * All four raw values differ so a swapped or dropped edge can't pass unnoticed.
 */
class PaneInsetsResolverTest : BaseTest() {

    private val raw = RawPaneInsets(top = 1.dp, bottom = 2.dp, left = 3.dp, right = 4.dp)

    @Test
    fun `a pane touching everything keeps every inset`() {
        PaneEdges.All.resolve(raw, LayoutDirection.Ltr) shouldBe
            WorkspacePaneInsets(top = 1.dp, bottom = 2.dp, left = 3.dp, right = 4.dp)
    }

    @Test
    fun `a pane touching nothing keeps no inset`() {
        PaneEdges.None.resolve(raw, LayoutDirection.Ltr) shouldBe WorkspacePaneInsets()
        PaneEdges.None.resolve(raw, LayoutDirection.Rtl) shouldBe WorkspacePaneInsets()
    }

    @Test
    fun `vertical edges are masked independently of layout direction`() {
        val topOnly = PaneEdges(touchesTop = true, touchesBottom = false, touchesStart = false, touchesEnd = false)
        val bottomOnly = PaneEdges(touchesTop = false, touchesBottom = true, touchesStart = false, touchesEnd = false)

        LayoutDirection.entries.forEach { direction ->
            withClue(direction) {
                topOnly.resolve(raw, direction) shouldBe WorkspacePaneInsets(top = 1.dp)
                bottomOnly.resolve(raw, direction) shouldBe WorkspacePaneInsets(bottom = 2.dp)
            }
        }
    }

    @Test
    fun `start resolves to left in Ltr and to right in Rtl`() {
        val startOnly = PaneEdges(touchesTop = false, touchesBottom = false, touchesStart = true, touchesEnd = false)

        startOnly.resolve(raw, LayoutDirection.Ltr) shouldBe WorkspacePaneInsets(left = 3.dp)
        startOnly.resolve(raw, LayoutDirection.Rtl) shouldBe WorkspacePaneInsets(right = 4.dp)
    }

    @Test
    fun `end resolves to right in Ltr and to left in Rtl`() {
        val endOnly = PaneEdges(touchesTop = false, touchesBottom = false, touchesStart = false, touchesEnd = true)

        endOnly.resolve(raw, LayoutDirection.Ltr) shouldBe WorkspacePaneInsets(right = 4.dp)
        endOnly.resolve(raw, LayoutDirection.Rtl) shouldBe WorkspacePaneInsets(left = 3.dp)
    }

    @Test
    fun `every edge combination is covered exactly once`() {
        val flags = listOf(false, true)
        for (top in flags) for (bottom in flags) for (start in flags) for (end in flags) {
            val edges = PaneEdges(touchesTop = top, touchesBottom = bottom, touchesStart = start, touchesEnd = end)
            for (direction in LayoutDirection.entries) {
                val startIsLeft = direction == LayoutDirection.Ltr
                val expected = WorkspacePaneInsets(
                    top = if (top) raw.top else 0.dp,
                    bottom = if (bottom) raw.bottom else 0.dp,
                    left = if (if (startIsLeft) start else end) raw.left else 0.dp,
                    right = if (if (startIsLeft) end else start) raw.right else 0.dp,
                )
                withClue("$edges / $direction") {
                    edges.resolve(raw, direction) shouldBe expected
                }
            }
        }
    }

    @Test
    fun `floating bar stacks follow the matching vertical edge`() {
        val topOnly = PaneEdges(touchesTop = true, touchesBottom = false, touchesStart = true, touchesEnd = true)

        topOnly.includesSystemBarInset(BarPosition.TOP) shouldBe true
        topOnly.includesSystemBarInset(BarPosition.BOTTOM) shouldBe false
        PaneEdges.All.includesSystemBarInset(BarPosition.BOTTOM) shouldBe true
        PaneEdges.None.includesSystemBarInset(BarPosition.TOP) shouldBe false
    }
}
