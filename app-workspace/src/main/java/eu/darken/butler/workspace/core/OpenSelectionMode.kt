package eu.darken.butler.workspace.core

/** How a multi-item selection is opened. */
enum class OpenSelectionMode {
    /** One viewer over the selection, shown as an overlay in the calling pane. */
    VIEW_HERE,

    /** One viewer over the selection, in its own tab. */
    VIEW_IN_TAB,

    /** Every item in its own tab: directories in the Explorer, files in the Viewer. */
    EACH_IN_TAB,
}
