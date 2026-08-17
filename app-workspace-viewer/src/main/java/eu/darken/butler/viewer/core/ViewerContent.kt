package eu.darken.butler.viewer.core

import eu.darken.butler.common.files.MimeInfo

/**
 * What the viewer resolved the target file to. [Loading] is the seed state, everything else is
 * terminal for one load attempt.
 */
sealed interface ViewerContent {
    data object Loading : ViewerContent

    data class Image(val mime: MimeInfo) : ViewerContent

    /** A PDF whose first page can be rendered. The bitmap is not part of the state, only its existence. */
    data class PdfPreview(val mime: MimeInfo, val pageCount: Int) : ViewerContent

    data class Unsupported(val mime: MimeInfo) : ViewerContent

    data class Failed(val error: Throwable) : ViewerContent
}
