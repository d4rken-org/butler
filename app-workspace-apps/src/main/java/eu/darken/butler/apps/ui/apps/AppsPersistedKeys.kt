package eu.darken.butler.apps.ui.apps

/**
 * Scroll slot keys for the apps list.
 *
 * These are persisted in the workspace session's UI state blob: renaming one orphans the saved
 * positions of everyone who already has state stored, and their next launch silently starts at the
 * top.
 */
internal object AppsScrollSlots {
    /** List and grid keep separate slots, their indices are not interchangeable. */
    const val LIST = "apps#list"
    const val GRID = "apps#grid"
}

/**
 * Floating bar keys for the apps list.
 *
 * These are persisted in the workspace session's UI state blob as part of the bar collapse
 * fractions: renaming one orphans the stored fraction for that bar, so it comes back expanded while
 * the rest of its stack stays as it was.
 */
internal object AppsBarKeys {
    const val TOOLBAR = "toolbar"
    const val INFOBAR = "infobar"
    const val ACTIONS = "actions"
}
