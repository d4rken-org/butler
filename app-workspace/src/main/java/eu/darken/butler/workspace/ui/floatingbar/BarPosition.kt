package eu.darken.butler.workspace.ui.floatingbar

/**
 * Defines the position of a [FloatingBarStack] relative to the screen edges.
 */
enum class BarPosition {
    /**
     * Bars stack from the top edge downward.
     * First declared bar is closest to the top edge.
     */
    TOP,

    /**
     * Bars stack from the bottom edge upward.
     * First declared bar is closest to the bottom edge.
     */
    BOTTOM,
}
