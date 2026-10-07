package eu.darken.butler.common.files.sftp.location

import eu.darken.butler.common.ca.CaString
import eu.darken.butler.common.ca.toCaString
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * An SSH server the user added manually: where it is, how to authenticate, and which host key it
 * must present.
 *
 * The secret itself is never part of this type, it lives in the credential vault keyed by [id].
 * [credentialVersion] is the generation token shared with the credential row, see
 * [SftpLocationManager] for the write ordering that keeps the two databases consistent.
 */
data class SftpLocation(
    val id: Uuid,
    val label: String?,
    val host: String,
    val port: Int = DEFAULT_PORT,
    val username: String,
    /**
     * Raw user input, stored verbatim: `""` is the server's initial directory, `/srv/media` is
     * absolute, `media` is relative to the initial directory.
     */
    val basePath: String = "",
    val authType: AuthType,
    val rememberCredential: Boolean,
    val credentialVersion: Int,
    /** Only a key the user accepted for [host]:[port]; a location never exists without one. */
    val hostKey: TrustedHostKey,
    /** Increments whenever [hostKey] is replaced or bound to a new endpoint. */
    val trustRevision: Int,
    val createdAt: Instant,
    val updatedAt: Instant,
    /** When this host's port was last found answering: the server was seen, not signed in to. */
    val lastSeenAt: Instant? = null,
) {

    init {
        require(isValidBasePath(basePath)) { "Base path must not contain NUL" }
    }

    enum class AuthType {
        PASSWORD,
        PRIVATE_KEY,
    }

    /** `darken@nas.local` or `darken@nas.local:2222/srv/media`, the subtitle shown under the name. */
    val endpointLabel: String
        get() = buildString {
            append(username)
            append("@")
            append(host)
            if (port != DEFAULT_PORT) append(":$port")
            if (basePath.isNotEmpty()) {
                if (!basePath.startsWith("/")) append("/")
                append(basePath)
            }
        }

    val displayName: CaString
        get() = (label?.takeIf { it.isNotBlank() } ?: host).toCaString()

    companion object {
        const val DEFAULT_PORT = 22

        fun isValidBasePath(basePath: String): Boolean = '\u0000' !in basePath
    }
}
