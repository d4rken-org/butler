package eu.darken.butler.viewer.core

import eu.darken.butler.common.ca.caString
import eu.darken.butler.common.ca.toCaString
import eu.darken.butler.common.error.HasLocalizedError
import eu.darken.butler.common.error.LocalizedError
import eu.darken.butler.common.error.LocalizedErrorContext
import eu.darken.butler.common.files.APath
import eu.darken.butler.viewer.R

class ViewerNotAFileException(
    val path: APath<*>,
) : IllegalArgumentException("Not a regular file: ${path.path}"), HasLocalizedError {

    override fun getLocalizedError(context: LocalizedErrorContext) = LocalizedError(
        throwable = this,
        label = R.string.viewer_error_not_a_file_label.toCaString(),
        description = caString { it.getString(R.string.viewer_error_not_a_file_description, path.path) },
    )
}

class ViewerBrokenSymlinkException(
    val path: APath<*>,
) : IllegalArgumentException("Broken symlink: ${path.path}"), HasLocalizedError {

    override fun getLocalizedError(context: LocalizedErrorContext) = LocalizedError(
        throwable = this,
        label = R.string.viewer_error_broken_symlink_label.toCaString(),
        description = caString { it.getString(R.string.viewer_error_broken_symlink_description, path.path) },
    )
}

/**
 * The file claims an image format but the decoder could not read its header - truncated download,
 * corrupt bytes, or a file whose extension lies about its contents.
 */
class ViewerUndecodableImageException(
    val path: APath<*>,
) : IllegalArgumentException("Not a decodable image: ${path.path}"), HasLocalizedError {

    override fun getLocalizedError(context: LocalizedErrorContext) = LocalizedError(
        throwable = this,
        label = R.string.viewer_error_undecodable_label.toCaString(),
        description = caString { it.getString(R.string.viewer_error_undecodable_description, path.name) },
    )
}

/**
 * The file is gone, not merely unreadable. Without this the gateway's permission-denied wording
 * would send the user hunting for an access problem that does not exist.
 */
class ViewerFileGoneException(
    val path: APath<*>,
    cause: Throwable? = null,
) : IllegalArgumentException("File no longer exists: ${path.path}", cause), HasLocalizedError {

    override fun getLocalizedError(context: LocalizedErrorContext) = LocalizedError(
        throwable = this,
        label = R.string.viewer_error_file_gone_label.toCaString(),
        description = caString { it.getString(R.string.viewer_error_file_gone_description, path.name) },
    )
}

/**
 * The file claims to be an Android package but the platform parser could not read a manifest from
 * it - truncated download, corrupt archive, or an extension that lies about the contents.
 */
class ViewerApkParseException(
    val path: APath<*>,
) : IllegalArgumentException("Not a readable package archive: ${path.path}"), HasLocalizedError {

    override fun getLocalizedError(context: LocalizedErrorContext) = LocalizedError(
        throwable = this,
        label = R.string.viewer_error_apk_unreadable_label.toCaString(),
        description = caString { it.getString(R.string.viewer_error_apk_unreadable_description, path.name) },
    )
}

/**
 * The document opened far enough to report a page count, but rendering its first page failed - the
 * file changed underneath the viewer, or the page itself is damaged.
 */
class ViewerPdfPreviewFailedException(
    val path: APath<*>,
) : IllegalStateException("PDF preview failed: ${path.path}"), HasLocalizedError {

    override fun getLocalizedError(context: LocalizedErrorContext) = LocalizedError(
        throwable = this,
        label = R.string.viewer_error_pdf_preview_label.toCaString(),
        description = caString { it.getString(R.string.viewer_error_pdf_preview_description, path.name) },
    )
}

class ViewerEmptyFileException(
    val path: APath<*>,
) : IllegalArgumentException("File is empty: ${path.path}"), HasLocalizedError {

    override fun getLocalizedError(context: LocalizedErrorContext) = LocalizedError(
        throwable = this,
        label = R.string.viewer_error_empty_file_label.toCaString(),
        description = caString { it.getString(R.string.viewer_error_empty_file_description, path.path) },
    )
}
