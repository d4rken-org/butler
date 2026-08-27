package eu.darken.butler.common.files.smb

import eu.darken.butler.common.ca.caString
import eu.darken.butler.common.ca.toCaString
import eu.darken.butler.common.error.HasLocalizedError
import eu.darken.butler.common.error.LocalizedError
import eu.darken.butler.common.error.LocalizedErrorContext
import eu.darken.butler.common.files.APath
import eu.darken.butler.common.files.errors.ReadException
import eu.darken.butler.common.files.smb.credentials.SmbCredentialUnavailableException
import eu.darken.butler.common.io.R

/** The server could not be reached at all: unknown name, refused connection or a timeout. */
class SmbUnreachableException(
    val endpoint: String,
    cause: Throwable? = null,
) : ReadException(message = "Cannot reach $endpoint", cause = cause), HasLocalizedError {

    override fun getLocalizedError(context: LocalizedErrorContext) = LocalizedError(
        throwable = this,
        label = R.string.smb_error_unreachable_title.toCaString(),
        description = caString { it.getString(R.string.smb_error_unreachable_description, endpoint) },
    )
}

/** The server rejected the credentials. The only failure that asks the user to sign in again. */
class SmbAuthException(
    val endpoint: String,
    cause: Throwable? = null,
) : ReadException(message = "Authentication rejected by $endpoint", cause = cause), HasLocalizedError {

    override fun getLocalizedError(context: LocalizedErrorContext) = LocalizedError(
        throwable = this,
        label = R.string.smb_error_auth_title.toCaString(),
        description = caString { it.getString(R.string.smb_error_auth_description, endpoint) },
    )
}

/**
 * The credentials were accepted, the share was not: this account may not use it. Deliberately not a
 * sign-in failure, re-entering the password cannot change a permission on the server.
 */
class SmbShareAccessDeniedException(
    val endpoint: String,
    val share: String,
    cause: Throwable? = null,
) : ReadException(message = "No access to '$share' on $endpoint", cause = cause), HasLocalizedError {

    override fun getLocalizedError(context: LocalizedErrorContext) = LocalizedError(
        throwable = this,
        label = R.string.smb_error_share_access_denied_title.toCaString(),
        description = caString { it.getString(R.string.smb_error_share_access_denied_description, share, endpoint) },
    )
}

/** The server is reachable but does not publish this share. */
class SmbShareNotFoundException(
    val endpoint: String,
    val share: String,
    cause: Throwable? = null,
) : ReadException(message = "No share '$share' on $endpoint", cause = cause), HasLocalizedError {

    override fun getLocalizedError(context: LocalizedErrorContext) = LocalizedError(
        throwable = this,
        label = R.string.smb_error_share_not_found_title.toCaString(),
        description = caString { it.getString(R.string.smb_error_share_not_found_description, share, endpoint) },
    )
}

/**
 * The server only speaks SMB1. Butler does not: SMB1 has no session signing worth the name and is
 * disabled by default on every current OS, so this is reported rather than silently downgraded.
 */
class SmbDialectNotSupportedException(
    val endpoint: String,
    cause: Throwable? = null,
) : ReadException(message = "$endpoint only offers SMB1", cause = cause), HasLocalizedError {

    override fun getLocalizedError(context: LocalizedErrorContext) = LocalizedError(
        throwable = this,
        label = R.string.smb_error_dialect_title.toCaString(),
        description = caString { it.getString(R.string.smb_error_dialect_description, endpoint) },
    )
}

/** A path operation failed for a reason the caller has no dedicated handling for. */
class SmbOperationException(
    message: String,
    path: APath<*>? = null,
    cause: Throwable? = null,
) : ReadException(message = message, path = path, cause = cause)

/**
 * Whether re-entering the password could fix this failure. Walks the cause chain: the generic
 * operations wrap failures in [ReadException]/[eu.darken.butler.common.files.errors.WriteException]
 * before they reach the UI.
 */
fun Throwable.isSmbSignInFailure(): Boolean {
    var current: Throwable? = this
    val seen = mutableSetOf<Throwable>()
    while (current != null && seen.add(current)) {
        if (current is SmbAuthException || current is SmbCredentialUnavailableException) return true
        current = current.cause
    }
    return false
}
