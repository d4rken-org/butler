package eu.darken.butler.workspace.ui.manager

import androidx.compose.ui.unit.LayoutDirection
import eu.darken.butler.workspace.ui.manager.WorkspaceDesign.Layout
import eu.darken.butler.workspace.ui.manager.WorkspaceDesign.PaneEdges
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import testhelpers.BaseTest

/**
 * The glyph's rectangles repeat an arrangement that [WorkspaceDesign.forPane] already describes as
 * edge flags, so the interesting test is not the table itself but that the two never disagree -
 * `paneEdgesMatchForPane` below. The table is pinned as well because a wrong rectangle draws a
 * plausible diagram of the wrong layout, which no other test would notice.
 */
class PaneCellTest : BaseTest() {

    private fun cell(layout: Layout, paneIndex: Int) = paneCell(layout, paneIndex)!!

    @Test
    fun `single fills the window`() {
        cell(Layout.SINGLE, 0) shouldBe PaneCell(0f, 0f, 1f, 1f)
    }

    @Test
    fun `dual vertical is two full-height columns`() {
        cell(Layout.DUAL_VERTICAL, 0) shouldBe PaneCell(0f, 0f, 0.5f, 1f)
        cell(Layout.DUAL_VERTICAL, 1) shouldBe PaneCell(0.5f, 0f, 0.5f, 1f)
    }

    @Test
    fun `dual horizontal is two full-width rows`() {
        cell(Layout.DUAL_HORIZONTAL, 0) shouldBe PaneCell(0f, 0f, 1f, 0.5f)
        cell(Layout.DUAL_HORIZONTAL, 1) shouldBe PaneCell(0f, 0.5f, 1f, 0.5f)
    }

    @Test
    fun `triple main left keeps its main pane full height at the start`() {
        cell(Layout.TRIPLE_MAIN_LEFT, 0) shouldBe PaneCell(0f, 0f, 0.5f, 1f)
        cell(Layout.TRIPLE_MAIN_LEFT, 1) shouldBe PaneCell(0.5f, 0f, 0.5f, 0.5f)
        cell(Layout.TRIPLE_MAIN_LEFT, 2) shouldBe PaneCell(0.5f, 0.5f, 0.5f, 0.5f)
    }

    @Test
    fun `triple main right keeps its main pane full height at the end`() {
        cell(Layout.TRIPLE_MAIN_RIGHT, 0) shouldBe PaneCell(0f, 0f, 0.5f, 0.5f)
        cell(Layout.TRIPLE_MAIN_RIGHT, 1) shouldBe PaneCell(0f, 0.5f, 0.5f, 0.5f)
        cell(Layout.TRIPLE_MAIN_RIGHT, 2) shouldBe PaneCell(0.5f, 0f, 0.5f, 1f)
    }

    @Test
    fun `quad grid fills column by column`() {
        cell(Layout.QUAD_GRID, 0) shouldBe PaneCell(0f, 0f, 0.5f, 0.5f)
        cell(Layout.QUAD_GRID, 1) shouldBe PaneCell(0f, 0.5f, 0.5f, 0.5f)
        cell(Layout.QUAD_GRID, 2) shouldBe PaneCell(0.5f, 0f, 0.5f, 0.5f)
        cell(Layout.QUAD_GRID, 3) shouldBe PaneCell(0.5f, 0.5f, 0.5f, 0.5f)
    }

    @Test
    fun `every layout offers exactly its pane count`() {
        Layout.entries.forEach { layout ->
            withClue(layout) {
                paneCells(layout).size shouldBe WorkspaceDesign(layout = layout).maxPanes
            }
        }
    }

    /**
     * The one that stops the two descriptions of pane geometry from drifting apart. Note the
     * off-by-one: [paneCell] is 0-based like the rail's `paneIndex`, [WorkspaceDesign.forPane] is
     * 1-based.
     */
    @Test
    fun `pane edges match forPane`() {
        Layout.entries.forEach { layout ->
            paneCells(layout).forEachIndexed { paneIndex, cell ->
                withClue("$layout pane $paneIndex") {
                    val derived = PaneEdges(
                        touchesTop = cell.y == 0f,
                        touchesBottom = cell.y + cell.height == 1f,
                        touchesStart = cell.startX == 0f,
                        touchesEnd = cell.startX + cell.width == 1f,
                    )
                    derived shouldBe WorkspaceDesign(layout = layout).forPane(paneIndex + 1).paneEdges
                }
            }
        }
    }

    /**
     * A pane assignment can outlive the layout it was made in, and unlike [WorkspaceDesign.forPane]
     * that must not throw - it would take down composition.
     */
    @Test
    fun `an index the layout does not have is null`() {
        paneCell(Layout.SINGLE, 1) shouldBe null
        paneCell(Layout.DUAL_VERTICAL, 2) shouldBe null
        paneCell(Layout.QUAD_GRID, 4) shouldBe null
        paneCell(Layout.QUAD_GRID, -1) shouldBe null
    }

    @Test
    fun `rtl mirrors the cell onto the other side`() {
        val startColumn = cell(Layout.DUAL_VERTICAL, 0)

        startColumn.toLeftRelative(LayoutDirection.Ltr) shouldBe PaneCell(0f, 0f, 0.5f, 1f)
        startColumn.toLeftRelative(LayoutDirection.Rtl) shouldBe PaneCell(0.5f, 0f, 0.5f, 1f)
    }

    @Test
    fun `rtl leaves a full-width cell alone`() {
        val fullWidth = cell(Layout.DUAL_HORIZONTAL, 1)

        fullWidth.toLeftRelative(LayoutDirection.Rtl) shouldBe fullWidth
    }
}
