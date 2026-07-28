package eu.darken.butler.explorer.ui.explorer

/**
 * Scroll slot keys for the Explorer.
 *
 * These are persisted in the workspace session's UI state blob: renaming one orphans the saved
 * positions of everyone who already has state stored, and their next launch silently starts at the
 * top. Only the prefix is contract - the location id after it is data.
 */
internal object ExplorerScrollSlots {

    private const val LIST_PREFIX = "list#"
    private const val GRID_PREFIX = "grid#"

    /** One slot per directory and view kind: navigating gives a clean slate, going back restores. */
    fun list(locationId: String?): String = "$LIST_PREFIX$locationId"

    /** List and grid never share a slot, their indices are not interchangeable. */
    fun grid(locationId: String?): String = "$GRID_PREFIX$locationId"
}

/**
 * Floating bar keys for the Explorer.
 *
 * These are persisted in the workspace session's UI state blob as part of the bar collapse
 * fractions: renaming one orphans the stored fraction for that bar, so it comes back expanded while
 * the rest of its stack stays as it was.
 */
internal object ExplorerBarKeys {
    const val TOOLBAR = "toolbar"
    const val INFOBAR = "infobar"
    const val OPERATIONS = "operations"
    const val CLIPBOARD = "clipboard"
    // Value predates the bar covering additions too; keep it so stored fractions still resolve.
    const val FAVORITES_FEEDBACK = "favorites-undo"
    const val ACTIONS = "actions"
}
