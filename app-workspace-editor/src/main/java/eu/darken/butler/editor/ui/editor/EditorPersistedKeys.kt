package eu.darken.butler.editor.ui.editor

/**
 * Floating bar keys for the editor.
 *
 * These are persisted in the workspace session's UI state blob as part of the bar collapse
 * fractions: renaming one orphans the stored fraction for that bar, so it comes back expanded while
 * the rest of its stack stays as it was.
 *
 * [BANNERS] is listed with them although a Static bar's fraction is never recorded - the key still
 * has to stay unique within its stack, and a later behaviour change would silently start persisting
 * it under whatever name it has by then.
 */
internal object EditorBarKeys {
    const val TOOLBAR = "toolbar"
    const val INFOBAR = "infobar"
    const val BANNERS = "banners"
    const val SEARCH = "search"
    const val CLIPBOARD = "clipboard"
    const val ACTIONS = "actions"
}
