package eu.darken.butler.common.files.webdav

import eu.darken.butler.common.ca.caString
import eu.darken.butler.common.ca.toCaString
import eu.darken.butler.common.error.HasLocalizedError
import eu.darken.butler.common.error.LocalizedError
import eu.darken.butler.common.error.LocalizedErrorContext
import eu.darken.butler.common.files.errors.ReadException
import eu.darken.butler.common.files.webdav.credentials.WebDavCredentialUnavailableException
import eu.darken.butler.common.io.R
import kotlin.uuid.Uuid

/** The server could not be reached at all: unknown name, refused connection, timeout or a failed handshake. */
class WebDavUnreachableException(
    val endpoint: String,
    cause: Throwable? = null,
) : ReadException(message = "Cannot reach $endpoint", cause = cause), HasLocalizedError {

    override fun getLocalizedError(context: LocalizedErrorContext) = LocalizedError(
        throwable = this,
        label = R.string.webdav_error_unreachable_title.toCaString(),
        description = caString { it.getString(R.string.webdav_error_unreachable_description, endpoint) },
    )
}

/** The server rejected the username or password, or demands credentials from an anonymous location. */
class WebDavAuthException(
    val endpoint: String,
    cause: Throwable? = null,
) : ReadException(message = "Authentication rejected by $endpoint", cause = cause), HasLocalizedError {

    override fun getLocalizedError(context: LocalizedErrorContext) = LocalizedError(
        throwable = this,
        label = R.string.webdav_error_auth_title.toCaString(),
        description = caString { it.getString(R.string.webdav_error_auth_description, endpoint) },
    )
}

/**
 * The server presented a certificate that is neither trusted by the system nor pinned for the
 * location. Nothing was sent to it: the certificate is checked during the TLS handshake.
 *
 * [stored] is null when nothing is pinned, i.e. the user is asked to trust [presented] for the first
 * time; a location saved while the system trusted its server ends up here too once the server
 * presents an untrusted certificate. Otherwise the pinned certificate changed.
 *
 * [locationId], [url] and [trustRevision] identify the location as it was when the connection was
 * attempted, the values a re-trust has to be confirmed against.
 */
class WebDavCertificateException(
    val locationId: Uuid,
    val endpoint: String,
    val presented: WebDavCertificateInfo,
    val stored: WebDavCertificateInfo?,
    val url: String,
    val trustRevision: Int,
    cause: Throwable? = null,
) : ReadException(
    message = when (stored) {
        null -> "Untrusted certificate from $endpoint: ${presented.sha256Fingerprint}"
        else -> "Certificate of $endpoint changed from ${stored.sha256Fingerprint} to ${presented.sha256Fingerprint}"
    },
    cause = cause,
), HasLocalizedError {

    override fun getLocalizedError(context: LocalizedErrorContext) = when (stored) {
        null -> LocalizedError(
            throwable = this,
            label = R.string.webdav_error_certificate_untrusted_title.toCaString(),
            description = caString {
                it.getString(R.string.webdav_error_certificate_untrusted_description, endpoint, presented.sha256Fingerprint)
            },
        )

        else -> LocalizedError(
            throwable = this,
            label = R.string.webdav_error_certificate_changed_title.toCaString(),
            description = caString {
                it.getString(
                    R.string.webdav_error_certificate_changed_description,
                    endpoint,
                    stored.sha256Fingerprint,
                    presented.sha256Fingerprint,
                )
            },
        )
    }
}

/**
 * Signed in, but the account may not open the location's collection. Not a sign-in failure: other
 * credentials for the same account cannot change a permission on the server.
 */
class WebDavAccessDeniedException(
    val endpoint: String,
    cause: Throwable? = null,
) : ReadException(message = "No access to $endpoint", cause = cause), HasLocalizedError {

    override fun getLocalizedError(context: LocalizedErrorContext) = LocalizedError(
        throwable = this,
        label = R.string.webdav_error_access_denied_title.toCaString(),
        description = caString { it.getString(R.string.webdav_error_access_denied_description, endpoint) },
    )
}

/** The location's URL names a file, or something that does not speak WebDAV, instead of a collection. */
class WebDavNotCollectionException(
    val endpoint: String,
    cause: Throwable? = null,
) : ReadException(message = "$endpoint is not a WebDAV collection", cause = cause), HasLocalizedError {

    override fun getLocalizedError(context: LocalizedErrorContext) = LocalizedError(
        throwable = this,
        label = R.string.webdav_error_not_collection_title.toCaString(),
        description = caString { it.getString(R.string.webdav_error_not_collection_description, endpoint) },
    )
}

/** Network storage is a Pro feature, and every WebDAV session is opened through the client pool. */
class WebDavProRequiredException : ReadException(message = "WebDAV servers require Butler Pro"), HasLocalizedError {

    override fun getLocalizedError(context: LocalizedErrorContext) = LocalizedError(
        throwable = this,
        label = R.string.webdav_error_pro_required_title.toCaString(),
        description = R.string.webdav_error_pro_required_description.toCaString(),
    )
}

/**
 * Whether entering the credential again could fix this failure. Walks the cause chain: the generic
 * operations wrap failures in [ReadException]/[eu.darken.butler.common.files.errors.WriteException]
 * before they reach the UI. Certificate failures are not included, they need a trust decision instead.
 */
fun Throwable.isWebDavSignInFailure(): Boolean {
    var current: Throwable? = this
    val seen = mutableSetOf<Throwable>()
    while (current != null && seen.add(current)) {
        when (current) {
            is WebDavAuthException,
            is WebDavCredentialUnavailableException -> return true
        }
        current = current.cause
    }
    return false
}
