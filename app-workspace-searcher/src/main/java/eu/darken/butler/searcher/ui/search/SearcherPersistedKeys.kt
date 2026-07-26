package eu.darken.butler.searcher.ui.search

/**
 * Scroll slot keys for the Searcher.
 *
 * These are persisted in the workspace session's UI state blob: renaming one orphans the saved
 * positions of everyone who already has state stored, and their next launch silently starts at the
 * top.
 */
internal object SearcherScrollSlots {
    /** List and grid keep separate slots, their indices are not interchangeable. */
    const val RESULTS_LIST = "results#list"
    const val RESULTS_GRID = "results#grid"

    /**
     * The idle screen (templates + history) is a different list with different content, so it gets
     * its own slot - a restored Searcher comes back idle and must not apply a results index here.
     */
    const val IDLE_LIST = "idle#list"
}

/**
 * Floating bar keys for the Searcher.
 *
 * These are persisted in the workspace session's UI state blob as part of the bar collapse
 * fractions: renaming one orphans the stored fraction for that bar, so it comes back expanded while
 * the rest of its stack stays as it was.
 */
internal object SearcherBarKeys {
    const val TOOLBAR = "toolbar"
    const val PROGRESS = "progress"
    const val INFOBAR = "infobar"
    const val OPERATIONS = "operations"
    const val CLIPBOARD = "clipboard"
    const val ACTIONS = "actions"
}
