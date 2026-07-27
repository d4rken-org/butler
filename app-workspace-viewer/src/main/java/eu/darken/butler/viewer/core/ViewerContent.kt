package eu.darken.butler.viewer.core

import eu.darken.butler.common.files.MimeInfo

/**
 * What the viewer resolved the target file to. [Loading] is the seed state, everything else is
 * terminal for one load attempt.
 */
sealed interface ViewerContent {
    data object Loading : ViewerContent

    data class Image(val mime: MimeInfo) : ViewerContent

    data class Unsupported(val mime: MimeInfo) : ViewerContent

    data class Failed(val error: Throwable) : ViewerContent
}
