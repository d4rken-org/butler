package eu.darken.butler.common.files.sftp

import eu.darken.butler.common.ca.caString
import eu.darken.butler.common.ca.toCaString
import eu.darken.butler.common.error.HasLocalizedError
import eu.darken.butler.common.error.LocalizedError
import eu.darken.butler.common.error.LocalizedErrorContext
import eu.darken.butler.common.files.errors.ReadException
import eu.darken.butler.common.files.sftp.credentials.SftpCredentialUnavailableException
import eu.darken.butler.common.files.sftp.location.TrustedHostKey
import eu.darken.butler.common.io.R
import eu.darken.ssh.HostKey
import kotlin.uuid.Uuid

/** The server could not be reached at all: unknown name, refused connection, timeout or a dropped handshake. */
class SftpUnreachableException(
    val endpoint: String,
    cause: Throwable? = null,
) : ReadException(message = "Cannot reach $endpoint", cause = cause), HasLocalizedError {

    override fun getLocalizedError(context: LocalizedErrorContext) = LocalizedError(
        throwable = this,
        label = R.string.sftp_error_unreachable_title.toCaString(),
        description = caString { it.getString(R.string.sftp_error_unreachable_description, endpoint) },
    )
}

/** The server rejected the username, password or key. */
class SftpAuthException(
    val endpoint: String,
    cause: Throwable? = null,
) : ReadException(message = "Authentication rejected by $endpoint", cause = cause), HasLocalizedError {

    override fun getLocalizedError(context: LocalizedErrorContext) = LocalizedError(
        throwable = this,
        label = R.string.sftp_error_auth_title.toCaString(),
        description = caString { it.getString(R.string.sftp_error_auth_description, endpoint) },
    )
}

/** No key is trusted for this server yet; [presentedKey] is what the user is asked to confirm. */
class SftpHostKeyUnknownException(
    val endpoint: String,
    val presentedKey: HostKey,
    cause: Throwable? = null,
) : ReadException(message = "Unknown host key for $endpoint: $presentedKey", cause = cause), HasLocalizedError {

    override fun getLocalizedError(context: LocalizedErrorContext) = LocalizedError(
        throwable = this,
        label = R.string.sftp_error_host_key_unknown_title.toCaString(),
        description = caString {
            it.getString(R.string.sftp_error_host_key_unknown_description, endpoint, presentedKey.sha256Fingerprint)
        },
    )
}

/**
 * The server presented a different key than the one pinned for it. Nothing was sent to it: host keys
 * are checked before authentication.
 *
 * [locationId], [host], [port] and [trustRevision] identify the location as it was when the
 * connection was attempted, the values a re-trust has to be confirmed against.
 */
class SftpHostKeyChangedException(
    val endpoint: String,
    val locationId: Uuid,
    val host: String,
    val port: Int,
    val trustRevision: Int,
    val storedKey: TrustedHostKey,
    val presentedKey: TrustedHostKey,
    cause: Throwable? = null,
) : ReadException(
    message = "Host key of $endpoint changed from ${storedKey.fingerprint} to ${presentedKey.fingerprint}",
    cause = cause,
), HasLocalizedError {

    val storedFingerprint: String
        get() = storedKey.fingerprint

    val presentedFingerprint: String
        get() = presentedKey.fingerprint

    override fun getLocalizedError(context: LocalizedErrorContext) = LocalizedError(
        throwable = this,
        label = R.string.sftp_error_host_key_changed_title.toCaString(),
        description = caString {
            it.getString(
                R.string.sftp_error_host_key_changed_description,
                endpoint,
                storedFingerprint,
                presentedFingerprint,
            )
        },
    )
}

/** The stored private key is not a key file Butler can read. */
class SftpKeyFormatException(
    val endpoint: String,
    cause: Throwable? = null,
) : ReadException(message = "Unreadable private key for $endpoint", cause = cause), HasLocalizedError {

    override fun getLocalizedError(context: LocalizedErrorContext) = LocalizedError(
        throwable = this,
        label = R.string.sftp_error_key_format_title.toCaString(),
        description = R.string.sftp_error_key_format_description.toCaString(),
    )
}

/** The private key is encrypted and the passphrase is wrong or missing. */
class SftpKeyPassphraseException(
    val endpoint: String,
    cause: Throwable? = null,
) : ReadException(message = "Wrong key passphrase for $endpoint", cause = cause), HasLocalizedError {

    override fun getLocalizedError(context: LocalizedErrorContext) = LocalizedError(
        throwable = this,
        label = R.string.sftp_error_key_passphrase_title.toCaString(),
        description = R.string.sftp_error_key_passphrase_description.toCaString(),
    )
}

/**
 * Signed in, but the account may not open the location's folder. Not a sign-in failure: other
 * credentials for the same account cannot change a permission on the server.
 */
class SftpAccessDeniedException(
    val endpoint: String,
    val basePath: String,
    cause: Throwable? = null,
) : ReadException(message = "No access to '$basePath' on $endpoint", cause = cause), HasLocalizedError {

    override fun getLocalizedError(context: LocalizedErrorContext) = LocalizedError(
        throwable = this,
        label = R.string.sftp_error_access_denied_title.toCaString(),
        description = caString { it.getString(R.string.sftp_error_access_denied_description, endpoint) },
    )
}

/** Network storage is a Pro feature, and every SFTP session is opened through the connection pool. */
class SftpProRequiredException : ReadException(message = "SFTP servers require Butler Pro"), HasLocalizedError {

    override fun getLocalizedError(context: LocalizedErrorContext) = LocalizedError(
        throwable = this,
        label = R.string.sftp_error_pro_required_title.toCaString(),
        description = R.string.sftp_error_pro_required_description.toCaString(),
    )
}

/**
 * Whether entering the credential again could fix this failure. Walks the cause chain: the generic
 * operations wrap failures in [ReadException]/[eu.darken.butler.common.files.errors.WriteException]
 * before they reach the UI. Host key failures are not included, they need a trust decision instead.
 */
fun Throwable.isSftpSignInFailure(): Boolean {
    var current: Throwable? = this
    val seen = mutableSetOf<Throwable>()
    while (current != null && seen.add(current)) {
        when (current) {
            is SftpAuthException,
            is SftpKeyFormatException,
            is SftpKeyPassphraseException,
            is SftpCredentialUnavailableException -> return true
        }
        current = current.cause
    }
    return false
}
