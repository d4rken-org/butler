package eu.darken.butler.viewer.ui.viewer

/**
 * Floating bar keys for the viewer.
 *
 * These are persisted in the workspace session's UI state blob as part of the bar collapse
 * fractions: renaming one orphans the stored fraction for that bar, so it comes back expanded while
 * the rest of its stack stays as it was.
 *
 * Every bar here now has a scroll behaviour (the toolbar collapses, the bottom bars hide), so these
 * fractions are recorded for real - this is no longer a precaution. Keys also have to stay unique
 * within their stack; TOP and BOTTOM are separate stacks.
 */
internal object ViewerBarKeys {
    const val TOOLBAR = "toolbar"
    const val EXTERNAL_CHANGE = "externalchange"
    const val ACTIONS = "actions"
    const val FILEINFO = "fileinfo"
    const val PDF_HINT = "pdfhint"
}
