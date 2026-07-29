package eu.darken.butler.apps.ui.details

/**
 * Scroll slot keys for the app details pages.
 *
 * These are persisted in the workspace session's UI state blob: renaming one orphans the saved
 * positions of everyone who already has state stored, and their next launch silently starts at the
 * top.
 */
internal object AppDetailsScrollSlots {
    /** Separate slots per route so Overview's position survives the round-trip to Components. */
    const val OVERVIEW = "overview"
    const val COMPONENTS = "components"
}

/**
 * Floating bar keys for the app details pages.
 *
 * These are persisted in the workspace session's UI state blob as part of the bar collapse
 * fractions: renaming one orphans the stored fraction for that bar, so it comes back expanded while
 * the rest of its stack stays as it was.
 */
internal object AppDetailsBarKeys {
    const val TOOLBAR = "toolbar"
    const val INFOBAR = "infobar"
    const val ACTIONS = "actions"
}
