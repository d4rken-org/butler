package eu.darken.butler.workspace.ui.common

import androidx.compose.ui.unit.dp

object WorkspacePaddings {
    /** Inset from the pane edge to a floating bar. Applied automatically by FloatingBarStack. */
    val BarHorizontal = 16.dp

    /** Inset from the pane edge to full-width page content (lists, card columns). */
    val ContentHorizontal = 12.dp

    /** Inset from the pane edge to a tile grid. Matches [GridGutter] so the margins read as wide
     * as the gaps between tiles. */
    val GridHorizontal = 4.dp

    /** Gap between grid tiles, horizontally and vertically. */
    val GridGutter = 4.dp

    /** Gap between standalone cards in a list. */
    val ListGap = 8.dp

    /** Gap between full-bleed rows, which carry their own interior padding. */
    val ListGapDense = 4.dp

    /** Inset from the window edge on full-window surfaces that are not a workspace pane. */
    val ScreenHorizontal = 24.dp
}
