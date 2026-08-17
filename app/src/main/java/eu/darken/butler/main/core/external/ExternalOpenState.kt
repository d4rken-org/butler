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
 * Viewing is offered for what the Viewer workspace can actually show, editing only for text that
 * fits the editor's paste cap. Saving is always possible, so the dialog is never empty.
 */
fun computeExternalOpenOptions(
    mime: MimeInfo,
    sizeBytes: Long?,
): List<ExternalOpenOption> = buildList {
    if (mime.isImage) add(ExternalOpenOption.VIEW)
    if (mime.isText && (sizeBytes == null || sizeBytes <= PasteFileReader.MAX_PASTE_FILE_SIZE)) {
        add(ExternalOpenOption.EDIT_AS_TEXT)
    }
    add(ExternalOpenOption.SAVE_AS)
}
