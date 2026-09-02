package eu.darken.butler.history.ui

/**
 * Scroll slot keys for the operation history.
 *
 * These are persisted in the workspace session's UI state blob: renaming one orphans the saved
 * positions of everyone who already has state stored, and their next launch silently starts at the
 * top.
 */
internal object HistoryScrollSlots {
    const val LIST = "history"
}

/**
 * Floating bar keys for the operation history.
 *
 * These are persisted in the workspace session's UI state blob as part of the bar collapse
 * fractions: renaming one orphans the stored fraction for that bar, so it comes back expanded while
 * the rest of its stack stays as it was.
 */
internal object HistoryBarKeys {
    const val TOOLBAR = "toolbar"
    const val ACTIONS = "actions"
}
