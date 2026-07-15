package eu.darken.butler.common.files.archive

import eu.darken.butler.common.files.APath
import eu.darken.butler.common.files.errors.ReadException

/**
 * Content of an encrypted archive entry was requested without a cached password,
 * or the cached password turned out to be wrong ([attemptFailed]).
 *
 * Listing encrypted archives works without a password; only entry content reads throw this.
 */
class ArchivePasswordRequiredException(
    val container: APath<*>,
    val attemptFailed: Boolean = false,
    cause: Throwable? = null,
) : ReadException(
    message = if (attemptFailed) "Wrong archive password." else "Archive password required.",
    path = container,
    cause = cause,
)
