package eu.darken.butler.main.core.external

import android.net.Uri
import eu.darken.butler.common.files.MimeInfo
import eu.darken.butler.editor.core.PasteFileReader
import eu.darken.butler.viewer.core.ViewerSupport

/**
 * A file another app handed to Butler - via ACTION_VIEW or a single-file share - together with the
 * choices we can offer for it.
 *
 * @param caption Text the sender attached to the file. Only a share carries one; an ACTION_VIEW
 * arrival has no text to go with the file.
 */
data class ExternalOpenState(
    val ref: SourceRef,
    val originalUri: Uri,
    val displayName: String,
    val sizeBytes: Long?,
    val mime: MimeInfo,
    val callerPackage: String?,
    val options: List<ExternalOpenOption>,
    val caption: String? = null,
)

enum class ExternalOpenOption {
    VIEW,
    EDIT_AS_TEXT,
    SHOW_IN_EXPLORER,
    SAVE_AS,
    ;
}

/**
 * Whether the Viewer has a renderer for this content. Delegates to [ViewerSupport] so the import and
 * extension rules in [ExternalOpenRouter] and [ExternalContentImporter] track what the viewer
 * actually renders. Deliberately NOT what gates the view offer - see [computeExternalOpenOptions] -
 * because widening it would start importing archives into the cache.
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
 * Viewing is offered for every arrival, whatever it is: the Viewer classifies archives as their own
 * state and explains in place what it cannot render, which is a better answer than a dialog that
 * silently drops the action the user came for. Editing is offered only for text that fits the
 * editor's paste cap, and revealing only for content that exists as a real file on the device -
 * anything Butler had to copy into its own cache has no folder worth opening. Saving is always
 * possible, so the dialog is never empty.
 */
fun computeExternalOpenOptions(
    mime: MimeInfo,
    sizeBytes: Long?,
    hasContainingFolder: Boolean = false,
): List<ExternalOpenOption> = buildList {
    add(ExternalOpenOption.VIEW)
    if (mime.isText && (sizeBytes == null || sizeBytes <= PasteFileReader.MAX_PASTE_FILE_SIZE)) {
        add(ExternalOpenOption.EDIT_AS_TEXT)
    }
    if (hasContainingFolder) add(ExternalOpenOption.SHOW_IN_EXPLORER)
    add(ExternalOpenOption.SAVE_AS)
}
