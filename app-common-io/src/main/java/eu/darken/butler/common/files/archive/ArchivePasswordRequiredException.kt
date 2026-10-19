package eu.darken.butler.common.files.archive

import eu.darken.butler.common.files.APath
import eu.darken.butler.common.files.errors.ReadException

/**
 * Content of an encrypted archive entry was requested without a cached password,
 * or the cached password turned out to be wrong ([attemptFailed]).
 *
 * Listing works without a password, except for 7z archives with an encrypted entry list: there
 * indexing throws this too. [mayBeDamaged] marks a failed attempt that could just as well be damaged
 * data, because the format cannot tell a wrong key from corruption.
 */
class ArchivePasswordRequiredException(
    val container: APath<*>,
    val attemptFailed: Boolean = false,
    val mayBeDamaged: Boolean = false,
    cause: Throwable? = null,
) : ReadException(
    message = when {
        mayBeDamaged -> "Wrong archive password, or the archive is damaged."
        attemptFailed -> "Wrong archive password."
        else -> "Archive password required."
    },
    path = container,
    cause = cause,
)
