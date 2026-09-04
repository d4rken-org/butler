package eu.darken.butler.templates.ui

/**
 * Scroll slot keys for the templates picker.
 *
 * These are persisted in the workspace session's UI state blob: renaming one orphans the saved
 * positions of everyone who already has state stored, and their next launch silently starts at the
 * top.
 */
internal object TemplatesScrollSlots {
    const val LIST = "templates.v2"
}
