package eu.darken.butler.workspace.ui.manager

data class WorkspaceDesign(
    val layout: Layout = Layout.SINGLE,
    val paneEdges: PaneEdges = PaneEdges.Both,
) {

    val isSingle: Boolean
        get() = layout == Layout.SINGLE

    val maxPanes = when (layout) {
        Layout.SINGLE -> 1
        Layout.DUAL_VERTICAL -> 2
        Layout.DUAL_HORIZONTAL -> 2
        Layout.TRIPLE_MAIN_LEFT -> 3
        Layout.TRIPLE_MAIN_RIGHT -> 3
        Layout.QUAD_GRID -> 4
    }

    /**
     * Returns a new WorkspaceDesign with correct paneEdges for the given pane.
     * Pane indices are 1-based (matching UI display).
     */
    fun forPane(paneIndex: Int): WorkspaceDesign = copy(
        paneEdges = when (layout) {
            Layout.SINGLE -> when (paneIndex) {
                1 -> PaneEdges.Both
                else -> error("Invalid pane index $paneIndex for $layout (valid: 1)")
            }
            Layout.DUAL_VERTICAL -> when (paneIndex) {
                1, 2 -> PaneEdges.Both // Both panes full height
                else -> error("Invalid pane index $paneIndex for $layout (valid: 1-2)")
            }
            Layout.DUAL_HORIZONTAL -> when (paneIndex) {
                1 -> PaneEdges.TopOnly
                2 -> PaneEdges.BottomOnly
                else -> error("Invalid pane index $paneIndex for $layout (valid: 1-2)")
            }
            Layout.TRIPLE_MAIN_LEFT -> when (paneIndex) {
                1 -> PaneEdges.Both // Left main (full height)
                2 -> PaneEdges.TopOnly // Top-right
                3 -> PaneEdges.BottomOnly // Bottom-right
                else -> error("Invalid pane index $paneIndex for $layout (valid: 1-3)")
            }
            Layout.TRIPLE_MAIN_RIGHT -> when (paneIndex) {
                1 -> PaneEdges.TopOnly // Top-left
                2 -> PaneEdges.BottomOnly // Bottom-left
                3 -> PaneEdges.Both // Right main (full height)
                else -> error("Invalid pane index $paneIndex for $layout (valid: 1-3)")
            }
            Layout.QUAD_GRID -> when (paneIndex) {
                1, 2 -> PaneEdges.TopOnly // Top row
                3, 4 -> PaneEdges.BottomOnly // Bottom row
                else -> error("Invalid pane index $paneIndex for $layout (valid: 1-4)")
            }
        }
    )

    /**
     * Describes which screen edges a workspace pane touches.
     * Used to determine which system bar insets to apply.
     */
    data class PaneEdges(
        val touchesTop: Boolean = true,
        val touchesBottom: Boolean = true,
    ) {
        companion object {
            val Both = PaneEdges(touchesTop = true, touchesBottom = true)
            val TopOnly = PaneEdges(touchesTop = true, touchesBottom = false)
            val BottomOnly = PaneEdges(touchesTop = false, touchesBottom = true)
            val None = PaneEdges(touchesTop = false, touchesBottom = false)
        }
    }

    enum class Layout {
        SINGLE,
        DUAL_VERTICAL,
        DUAL_HORIZONTAL,
        TRIPLE_MAIN_LEFT,
        TRIPLE_MAIN_RIGHT,
        QUAD_GRID,
        ;
    }
}
