package eu.darken.butler.common.files.archive

import eu.darken.butler.common.R
import eu.darken.butler.common.ca.caString
import eu.darken.butler.common.ca.toCaString
import eu.darken.butler.common.error.LocalizedError
import eu.darken.butler.common.error.LocalizedErrorContext
import eu.darken.butler.common.files.APath
import eu.darken.butler.common.files.errors.ReadException

/**
 * The archive's storage backend only provides forward-only streaming (e.g. a cloud
 * DocumentsProvider returning a pipe descriptor), but the format requires random access.
 *
 * Thrown ONLY when a handle was successfully opened and the positioned probe read failed —
 * open failures (permissions, offline, vanished files) propagate as their original errors.
 */
class ArchiveNotSeekableException(
    val container: APath<*>,
    cause: Throwable? = null,
) : ReadException("Archive requires random access, storage is stream-only", container, cause) {

    override fun getLocalizedError(context: LocalizedErrorContext) = LocalizedError(
        throwable = this,
        label = "ArchiveNotSeekableException".toCaString(),
        description = caString { cx ->
            cx.getString(R.string.general_error_archive_not_seekable_msg, container.userReadablePath.get(cx))
        },
    )
}
