package eu.darken.butler.workspace.ui.manager

import eu.darken.butler.workspace.ui.manager.WorkspaceDesign.Layout
import eu.darken.butler.workspace.ui.manager.WorkspaceDesign.PaneEdges
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import testhelpers.BaseTest

/**
 * Locks the pane geometry table down.
 *
 * The values here mirror where each layout composable actually places its panes - see
 * `PaneEdgesGeometryTest` in `:app`, which measures the rendered layouts and cross-checks them
 * against this table.
 */
class WorkspaceDesignTest : BaseTest() {

    private fun edges(top: Boolean, bottom: Boolean, start: Boolean, end: Boolean) = PaneEdges(
        touchesTop = top,
        touchesBottom = bottom,
        touchesStart = start,
        touchesEnd = end,
    )

    private fun paneEdges(layout: Layout, paneIndex: Int) =
        WorkspaceDesign(layout = layout).forPane(paneIndex).paneEdges

    @Test
    fun `single pane touches every edge`() {
        paneEdges(Layout.SINGLE, 1) shouldBe edges(top = true, bottom = true, start = true, end = true)
    }

    @Test
    fun `dual vertical splits along start-end`() {
        paneEdges(Layout.DUAL_VERTICAL, 1) shouldBe edges(top = true, bottom = true, start = true, end = false)
        paneEdges(Layout.DUAL_VERTICAL, 2) shouldBe edges(top = true, bottom = true, start = false, end = true)
    }

    @Test
    fun `dual horizontal splits along top-bottom`() {
        paneEdges(Layout.DUAL_HORIZONTAL, 1) shouldBe edges(top = true, bottom = false, start = true, end = true)
        paneEdges(Layout.DUAL_HORIZONTAL, 2) shouldBe edges(top = false, bottom = true, start = true, end = true)
    }

    @Test
    fun `triple main left has a full-height start pane`() {
        paneEdges(Layout.TRIPLE_MAIN_LEFT, 1) shouldBe edges(top = true, bottom = true, start = true, end = false)
        paneEdges(Layout.TRIPLE_MAIN_LEFT, 2) shouldBe edges(top = true, bottom = false, start = false, end = true)
        paneEdges(Layout.TRIPLE_MAIN_LEFT, 3) shouldBe edges(top = false, bottom = true, start = false, end = true)
    }

    @Test
    fun `triple main right has a full-height end pane`() {
        paneEdges(Layout.TRIPLE_MAIN_RIGHT, 1) shouldBe edges(top = true, bottom = false, start = true, end = false)
        paneEdges(Layout.TRIPLE_MAIN_RIGHT, 2) shouldBe edges(top = false, bottom = true, start = true, end = false)
        paneEdges(Layout.TRIPLE_MAIN_RIGHT, 3) shouldBe edges(top = true, bottom = true, start = false, end = true)
    }

    /**
     * Regression: the quad grid fills column by column (1 = start top, 2 = start bottom,
     * 3 = end top, 4 = end bottom). Treating it as row-major made pane 2 pad for the status bar
     * and pane 3 draw under it.
     */
    @Test
    fun `quad grid is column-major`() {
        paneEdges(Layout.QUAD_GRID, 1) shouldBe edges(top = true, bottom = false, start = true, end = false)
        paneEdges(Layout.QUAD_GRID, 2) shouldBe edges(top = false, bottom = true, start = true, end = false)
        paneEdges(Layout.QUAD_GRID, 3) shouldBe edges(top = true, bottom = false, start = false, end = true)
        paneEdges(Layout.QUAD_GRID, 4) shouldBe edges(top = false, bottom = true, start = false, end = true)
    }

    @Test
    fun `every layout covers exactly its pane range`() {
        Layout.entries.forEach { layout ->
            val design = WorkspaceDesign(layout = layout)
            (1..design.maxPanes).forEach { design.forPane(it) }
            shouldThrow<IllegalStateException> { design.forPane(0) }
            shouldThrow<IllegalStateException> { design.forPane(design.maxPanes + 1) }
        }
    }

    @Test
    fun `each layout reaches every window edge across its panes`() {
        Layout.entries.forEach { layout ->
            val design = WorkspaceDesign(layout = layout)
            val all = (1..design.maxPanes).map { design.forPane(it).paneEdges }
            withClue(layout) {
                all.any { it.touchesTop } shouldBe true
                all.any { it.touchesBottom } shouldBe true
                all.any { it.touchesStart } shouldBe true
                all.any { it.touchesEnd } shouldBe true
            }
        }
    }

    @Test
    fun `withoutEdges only clears, never adds`() {
        val topOnly = edges(top = true, bottom = false, start = true, end = false)

        topOnly.withoutEdges(start = true) shouldBe edges(top = true, bottom = false, start = false, end = false)
        topOnly.withoutEdges(bottom = true, end = true) shouldBe topOnly
        topOnly.withoutEdges() shouldBe topOnly
        PaneEdges.All.withoutEdges(top = true, bottom = true, start = true, end = true) shouldBe PaneEdges.None
    }

    @Test
    fun `withoutEdges on the design masks the pane edges`() {
        val design = WorkspaceDesign(layout = Layout.QUAD_GRID).forPane(1)

        design.withoutEdges(start = true).paneEdges shouldBe
            edges(top = true, bottom = false, start = false, end = false)
        design.withoutEdges(start = true).layout shouldBe Layout.QUAD_GRID
    }
}
