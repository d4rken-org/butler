package eu.darken.butler.viewer.core

/**
 * What a metadata probe found about the file that is currently on display.
 *
 * Only ever set, never cleared: reloading the file is what resets it, because that is the only
 * moment a new baseline exists to compare against.
 */
sealed interface ViewerExternalChange {
    /** Size or mtime differs from what was loaded. */
    data object Modified : ViewerExternalChange

    /** The file is definitively no longer there. */
    data object Gone : ViewerExternalChange
}
