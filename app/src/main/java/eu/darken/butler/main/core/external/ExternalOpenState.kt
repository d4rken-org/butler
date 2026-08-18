package eu.darken.butler.main.core.external

import android.net.Uri
import eu.darken.butler.common.files.MimeInfo
import eu.darken.butler.editor.core.PasteFileReader
import eu.darken.butler.viewer.core.ViewerSupport

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
    SHOW_IN_EXPLORER,
    SAVE_AS,
    ;
}

/**
 * Whether the Viewer can show this content. Delegates to [ViewerSupport] so the offer always tracks
 * what the viewer actually renders; the import and extension rules in [ExternalOpenRouter] and
 * [ExternalContentImporter] key off the same source, so an option we offer is one we can open.
 */
internal val MimeInfo.isViewable: Boolean
    get() = ViewerSupport.canDisplay(this)

/**
 * Whether [fileName] already announces the same kind of content as this type, i.e. whether the
 * viewer would route it to the right renderer without us materializing a better-named copy.
 */
internal fun MimeInfo.hasMatchingViewableExtension(fileName: String): Boolean =
    ViewerSupport.hasMatchingName(this, fileName)

/**
 * Viewing is offered for whatever the Viewer workspace renders, editing only for text that fits the
 * editor's paste cap, and revealing only for content that exists as a real file on the device -
 * anything Butler had to copy into its own cache has no folder worth opening. Saving is always
 * possible, so the dialog is never empty.
 */
fun computeExternalOpenOptions(
    mime: MimeInfo,
    sizeBytes: Long?,
    hasContainingFolder: Boolean = false,
): List<ExternalOpenOption> = buildList {
    if (mime.isViewable) add(ExternalOpenOption.VIEW)
    if (mime.isText && (sizeBytes == null || sizeBytes <= PasteFileReader.MAX_PASTE_FILE_SIZE)) {
        add(ExternalOpenOption.EDIT_AS_TEXT)
    }
    if (hasContainingFolder) add(ExternalOpenOption.SHOW_IN_EXPLORER)
    add(ExternalOpenOption.SAVE_AS)
}
