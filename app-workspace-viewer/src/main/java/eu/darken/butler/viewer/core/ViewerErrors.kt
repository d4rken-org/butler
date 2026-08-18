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
 * The archive parsed, but its launcher icon could not be rendered - a missing, malformed or
 * unreadable icon resource. Only reachable from an explicit save/preview, never from a plain view.
 */
class ViewerIconUnavailableException(
    val path: APath<*>,
) : IllegalStateException("No readable launcher icon: ${path.path}"), HasLocalizedError {

    override fun getLocalizedError(context: LocalizedErrorContext) = LocalizedError(
        throwable = this,
        label = R.string.viewer_error_icon_unavailable_label.toCaString(),
        description = caString { it.getString(R.string.viewer_error_icon_unavailable_description, path.name) },
    )
}

/**
 * The chosen destination exists but is not a regular file: a folder, a symlink, or something the
 * gateway could not classify. Replacing it is refused rather than offered - writing through a
 * symlink truncates whatever it points at, which nobody agreeing to replace an icon is asking for.
 */
class ViewerIconTargetNotAFileException(
    val path: APath<*>,
) : IllegalArgumentException("Save target is not a regular file: ${path.path}"), HasLocalizedError {

    override fun getLocalizedError(context: LocalizedErrorContext) = LocalizedError(
        throwable = this,
        label = R.string.viewer_error_icon_target_notfile_label.toCaString(),
        description = caString { it.getString(R.string.viewer_error_icon_target_notfile_description, path.name) },
    )
}

/**
 * Something occupied the destination between the moment it was checked and the moment we went to
 * write. Permission to overwrite was never granted for *this* file, so the save stops instead.
 */
class ViewerIconTargetAppearedException(
    val path: APath<*>,
) : IllegalStateException("Save target appeared after the check: ${path.path}"), HasLocalizedError {

    override fun getLocalizedError(context: LocalizedErrorContext) = LocalizedError(
        throwable = this,
        label = R.string.viewer_error_icon_target_appeared_label.toCaString(),
        description = caString { it.getString(R.string.viewer_error_icon_target_appeared_description, path.name) },
    )
}

/**
 * Sharing needs a FileProvider URI, which only exists for a local file the app itself can read.
 * SAF paths and files reachable only through root or ADB have none, so the hand-off never starts.
 */
class ViewerShareUnavailableException(
    val path: APath<*>,
) : IllegalStateException("Cannot build a shareable URI for: ${path.path}"), HasLocalizedError {

    override fun getLocalizedError(context: LocalizedErrorContext) = LocalizedError(
        throwable = this,
        label = R.string.viewer_share_failed_label.toCaString(),
        description = caString { it.getString(R.string.viewer_share_failed_description, path.name) },
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
