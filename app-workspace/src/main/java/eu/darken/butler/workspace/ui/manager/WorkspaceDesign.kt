package eu.darken.butler.workspace.ui.manager

data class WorkspaceDesign(
    val layout: Layout = Layout.SINGLE,
) {

    val isSingle: Boolean
        get() = layout == Layout.SINGLE

    val maxPanes = when (layout) {
        Layout.SINGLE -> 1
        Layout.DUAL_VERTICAL -> 2
        Layout.DUAL_HORIZONTAL -> 2
        Layout.TRIPLE_MAIN_LEFT -> 3
    }

    enum class Layout {
        SINGLE,
        DUAL_VERTICAL,
        DUAL_HORIZONTAL,
        TRIPLE_MAIN_LEFT,
        ;
    }
}
