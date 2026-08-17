package eu.darken.butler.main.core.external

import android.net.Uri
import eu.darken.butler.common.files.MimeInfo
import eu.darken.butler.editor.core.PasteFileReader

/**
 * A file another app handed to Butler via ACTION_VIEW, together with the choices we can offer for it.
 */
data class ExternalOpenState(
    val ref: SourceRef,
    val originalUri: Uri,
    val displayName: String,
    val sizeBytes: Long?,
    val mime: MimeInfo,
    val callerPackage: String?,
    val options: List<ExternalOpenOption>,
)

enum class ExternalOpenOption {
    VIEW,
    EDIT_AS_TEXT,
    SAVE_AS,
    ;
}

/**
 * What the Viewer workspace can actually show: images and PDFs. The import and extension rules in
 * [ExternalOpenRouter] and [ExternalContentImporter] key off the same predicate, so an option we
 * offer is always one we can also open.
 */
internal val MimeInfo.isViewable: Boolean
    get() = isImage || isPdf

/**
 * Whether [fileName] already announces the same viewable category as this type. The Viewer picks its
 * decoder from the file name, so a merely viewable name isn't enough: a PDF called `invoice.jpg`
 * would be handed to the image decoder and fail to render.
 */
internal fun MimeInfo.hasMatchingViewableExtension(fileName: String): Boolean {
    val named = MimeInfo.fromFileName(fileName)
    return when {
        isPdf -> named.isPdf
        isImage -> named.isImage
        else -> false
    }
}

/**
 * Viewing is offered for images and PDFs, the two things the Viewer workspace can show, editing only
 * for text that fits the editor's paste cap. Saving is always possible, so the dialog is never empty.
 */
fun computeExternalOpenOptions(
    mime: MimeInfo,
    sizeBytes: Long?,
): List<ExternalOpenOption> = buildList {
    if (mime.isViewable) add(ExternalOpenOption.VIEW)
    if (mime.isText && (sizeBytes == null || sizeBytes <= PasteFileReader.MAX_PASTE_FILE_SIZE)) {
        add(ExternalOpenOption.EDIT_AS_TEXT)
    }
    add(ExternalOpenOption.SAVE_AS)
}
