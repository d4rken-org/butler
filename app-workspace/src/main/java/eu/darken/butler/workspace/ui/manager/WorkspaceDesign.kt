package eu.darken.butler.workspace.ui.manager

data class WorkspaceDesign(
    val layout: Layout = Layout.SINGLE,
    val paneEdges: PaneEdges = PaneEdges.All,
    val railPlacement: RailPlacement = RailPlacement.START,
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
     *
     * The table below is pure window geometry: it describes where each layout composable actually
     * places its panes, and nothing about surrounding chrome. Indices follow the placement order of
     * the matching layout composable - note that the column-based layouts (TRIPLE_*, QUAD_GRID)
     * fill column by column, not row by row.
     *
     * Chrome that occupies a window edge (e.g. the navigation rail) is applied afterwards by the
     * host via [PaneEdges.withoutEdges].
     */
    fun forPane(paneIndex: Int): WorkspaceDesign = copy(
        paneEdges = when (layout) {
            Layout.SINGLE -> when (paneIndex) {
                1 -> PaneEdges.All
                else -> error("Invalid pane index $paneIndex for $layout (valid: 1)")
            }
            // Row: 1 = start, 2 = end. Both full height.
            Layout.DUAL_VERTICAL -> when (paneIndex) {
                1 -> PaneEdges(touchesTop = true, touchesBottom = true, touchesStart = true, touchesEnd = false)
                2 -> PaneEdges(touchesTop = true, touchesBottom = true, touchesStart = false, touchesEnd = true)
                else -> error("Invalid pane index $paneIndex for $layout (valid: 1-2)")
            }
            // Column: 1 = top, 2 = bottom. Both full width.
            Layout.DUAL_HORIZONTAL -> when (paneIndex) {
                1 -> PaneEdges(touchesTop = true, touchesBottom = false, touchesStart = true, touchesEnd = true)
                2 -> PaneEdges(touchesTop = false, touchesBottom = true, touchesStart = true, touchesEnd = true)
                else -> error("Invalid pane index $paneIndex for $layout (valid: 1-2)")
            }
            // 1 = start main (full height), 2 = end top, 3 = end bottom.
            Layout.TRIPLE_MAIN_LEFT -> when (paneIndex) {
                1 -> PaneEdges(touchesTop = true, touchesBottom = true, touchesStart = true, touchesEnd = false)
                2 -> PaneEdges(touchesTop = true, touchesBottom = false, touchesStart = false, touchesEnd = true)
                3 -> PaneEdges(touchesTop = false, touchesBottom = true, touchesStart = false, touchesEnd = true)
                else -> error("Invalid pane index $paneIndex for $layout (valid: 1-3)")
            }
            // 1 = start top, 2 = start bottom, 3 = end main (full height).
            Layout.TRIPLE_MAIN_RIGHT -> when (paneIndex) {
                1 -> PaneEdges(touchesTop = true, touchesBottom = false, touchesStart = true, touchesEnd = false)
                2 -> PaneEdges(touchesTop = false, touchesBottom = true, touchesStart = true, touchesEnd = false)
                3 -> PaneEdges(touchesTop = true, touchesBottom = true, touchesStart = false, touchesEnd = true)
                else -> error("Invalid pane index $paneIndex for $layout (valid: 1-3)")
            }
            // Column-major: 1 = start top, 2 = start bottom, 3 = end top, 4 = end bottom.
            Layout.QUAD_GRID -> when (paneIndex) {
                1 -> PaneEdges(touchesTop = true, touchesBottom = false, touchesStart = true, touchesEnd = false)
                2 -> PaneEdges(touchesTop = false, touchesBottom = true, touchesStart = true, touchesEnd = false)
                3 -> PaneEdges(touchesTop = true, touchesBottom = false, touchesStart = false, touchesEnd = true)
                4 -> PaneEdges(touchesTop = false, touchesBottom = true, touchesStart = false, touchesEnd = true)
                else -> error("Invalid pane index $paneIndex for $layout (valid: 1-4)")
            }
        }
    )

    /**
     * Clears window edges that are occupied by surrounding chrome. See [PaneEdges.withoutEdges].
     */
    fun withoutEdges(
        top: Boolean = false,
        bottom: Boolean = false,
        start: Boolean = false,
        end: Boolean = false,
    ): WorkspaceDesign = copy(paneEdges = paneEdges.withoutEdges(top, bottom, start, end))

    /**
     * Describes which window edges a workspace pane touches.
     * Used to determine which system bar insets to apply.
     *
     * Start/end are layout-relative and only resolved to physical left/right when insets are
     * computed, so the model stays correct under RTL.
     */
    data class PaneEdges(
        val touchesTop: Boolean = true,
        val touchesBottom: Boolean = true,
        val touchesStart: Boolean = true,
        val touchesEnd: Boolean = true,
    ) {

        /**
         * Clears edges that are occupied by surrounding chrome (e.g. the navigation rail), i.e. the
         * pane doesn't actually reach that window edge even though its layout position suggests it.
         */
        fun withoutEdges(
            top: Boolean = false,
            bottom: Boolean = false,
            start: Boolean = false,
            end: Boolean = false,
        ): PaneEdges = PaneEdges(
            touchesTop = touchesTop && !top,
            touchesBottom = touchesBottom && !bottom,
            touchesStart = touchesStart && !start,
            touchesEnd = touchesEnd && !end,
        )

        companion object {
            val All = PaneEdges(touchesTop = true, touchesBottom = true, touchesStart = true, touchesEnd = true)
            val None = PaneEdges(touchesTop = false, touchesBottom = false, touchesStart = false, touchesEnd = false)
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

    /**
     * Which window edge the navigation rail occupies. A property of the window rather than of the
     * layout: a single-pane window carries one too, even though it composes no rail.
     */
    enum class RailPlacement {
        START,
        BOTTOM,
        ;
    }
}
