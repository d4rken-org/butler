package eu.darken.butler.viewer.ui.viewer

/**
 * Floating bar keys for the viewer.
 *
 * These are persisted in the workspace session's UI state blob as part of the bar collapse
 * fractions: renaming one orphans the stored fraction for that bar, so it comes back expanded while
 * the rest of its stack stays as it was.
 *
 * All of them are Static bars whose fraction is never recorded, but the key still has to stay unique
 * within its stack, and a later behaviour change would silently start persisting it under whatever
 * name it has by then.
 */
internal object ViewerBarKeys {
    const val TOOLBAR = "toolbar"
    const val ACTIONS = "actions"
    const val FILEINFO = "fileinfo"
    const val PDF_HINT = "pdfhint"
}
