package eu.darken.butler.workspace.ui.manager

import androidx.compose.ui.unit.LayoutDirection

/**
 * Where a pane sits inside the window, as fractions of it. [startX] is layout-relative like
 * [WorkspaceDesign.PaneEdges], so it means "left" under LTR and "right" under RTL - call
 * [toLeftRelative] before handing it to anything that draws in absolute coordinates.
 */
data class PaneCell(
    val startX: Float,
    val y: Float,
    val width: Float,
    val height: Float,
) {

    fun toLeftRelative(direction: LayoutDirection): PaneCell = when (direction) {
        LayoutDirection.Ltr -> this
        LayoutDirection.Rtl -> copy(startX = 1f - startX - width)
    }
}

/**
 * The pane rectangles of a layout, in the same order as the pane indices used everywhere else -
 * i.e. matching [WorkspaceDesign.forPane], except **0-based** like the rail's `paneIndex` while
 * `forPane` is 1-based.
 *
 * This repeats the arrangement that [WorkspaceDesign.forPane] already describes, deliberately.
 * `PaneEdges` is lossy: it cannot tell "second column" apart from "first column, but the navigation
 * rail covers the start edge" (see the `withoutEdges` call in `AdaptiveWorkspaceLayout`), so
 * deriving rectangles from it would silently misplace panes wherever chrome masks an edge. `PaneCellTest`
 * asserts the two agree for every layout instead, so they cannot drift.
 *
 * The `when` is exhaustive without an `else` so a new [WorkspaceDesign.Layout] fails to compile
 * rather than rendering as an empty diagram.
 */
fun paneCells(layout: WorkspaceDesign.Layout): List<PaneCell> = when (layout) {
    WorkspaceDesign.Layout.SINGLE -> listOf(
        PaneCell(0f, 0f, 1f, 1f),
    )
    WorkspaceDesign.Layout.DUAL_VERTICAL -> listOf(
        PaneCell(0f, 0f, 0.5f, 1f),
        PaneCell(0.5f, 0f, 0.5f, 1f),
    )
    WorkspaceDesign.Layout.DUAL_HORIZONTAL -> listOf(
        PaneCell(0f, 0f, 1f, 0.5f),
        PaneCell(0f, 0.5f, 1f, 0.5f),
    )
    WorkspaceDesign.Layout.TRIPLE_MAIN_LEFT -> listOf(
        PaneCell(0f, 0f, 0.5f, 1f),
        PaneCell(0.5f, 0f, 0.5f, 0.5f),
        PaneCell(0.5f, 0.5f, 0.5f, 0.5f),
    )
    WorkspaceDesign.Layout.TRIPLE_MAIN_RIGHT -> listOf(
        PaneCell(0f, 0f, 0.5f, 0.5f),
        PaneCell(0f, 0.5f, 0.5f, 0.5f),
        PaneCell(0.5f, 0f, 0.5f, 1f),
    )
    WorkspaceDesign.Layout.QUAD_GRID -> listOf(
        PaneCell(0f, 0f, 0.5f, 0.5f),
        PaneCell(0f, 0.5f, 0.5f, 0.5f),
        PaneCell(0.5f, 0f, 0.5f, 0.5f),
        PaneCell(0.5f, 0.5f, 0.5f, 0.5f),
    )
}

/**
 * Null for an index the layout doesn't have, rather than throwing like [WorkspaceDesign.forPane]:
 * a pane assignment can outlive the layout it was made in (rotation, panel mode change, `AUTO`
 * reacting to a window size change), and that must not take down composition.
 */
fun paneCell(layout: WorkspaceDesign.Layout, paneIndex: Int): PaneCell? =
    paneCells(layout).getOrNull(paneIndex)
