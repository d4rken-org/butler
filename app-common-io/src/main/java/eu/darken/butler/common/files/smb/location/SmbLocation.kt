package eu.darken.butler.common.files.smb.location

import eu.darken.butler.common.ca.CaString
import eu.darken.butler.common.ca.toCaString
import eu.darken.butler.common.files.SmbPath
import eu.darken.butler.common.files.extensions.Segments
import eu.darken.butler.common.files.smb.SmbLocationInput
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * A network location the user added manually: where the share is and how to authenticate to it.
 *
 * The password itself is never part of this type, it lives in the credential vault keyed by [id].
 * [credentialVersion] is the generation token shared with the credential row, see
 * [SmbLocationManager] for the write ordering that keeps the two databases consistent.
 */
data class SmbLocation(
    val id: Uuid,
    val label: String?,
    val host: String,
    val port: Int = SmbLocationInput.DEFAULT_PORT,
    /** Null for a server entry, whose root lists the shares the server offers. */
    val share: String?,
    /** Directory below [share] the location starts in, always empty for a server entry. */
    val basePath: Segments = emptyList(),
    val domain: String? = null,
    /** Display copy of the username, the authoritative one is stored with the credential. */
    val username: String? = null,
    val authType: AuthType,
    val rememberCredential: Boolean,
    val credentialVersion: Int,
    val createdAt: Instant,
    val updatedAt: Instant,
    /**
     * When a probe last found this host's port answering. A probe performs no SMB negotiation and
     * no login, so this says the server was seen, not that it was signed in to.
     */
    val lastSeenAt: Instant? = null,
) {

    enum class AuthType {
        GUEST,
        PASSWORD,
    }

    val isServer: Boolean
        get() = share == null

    val rootPath: SmbPath
        get() = SmbPath.root(id)

    /** The share [path] lives on. Null only for a server entry's root, which lists the shares. */
    fun shareOf(path: SmbPath): String? = share ?: path.segments.firstOrNull()

    /**
     * Where [path] points inside [shareOf]: `[a, b]` is `base/path/a/b` on a share location, and
     * `b` on share `a` of a server entry.
     */
    fun segmentsInShare(path: SmbPath): Segments = when {
        isServer -> path.segments.drop(1)
        else -> basePath + path.segments
    }

    /** A server entry's root or one of its shares: the server decides neither's name or existence. */
    fun isProtected(path: SmbPath): Boolean = isServer && path.segments.size <= 1

    /**
     * `host/share` or `host:port/share/base/path`, the subtitle shown under the location name. A
     * server entry is just `host` or `host:port`.
     */
    val endpointLabel: String
        get() = buildString {
            append(host)
            if (port != SmbLocationInput.DEFAULT_PORT) append(":$port")
            if (share != null) {
                append("/")
                append(share)
                basePath.forEach { append("/$it") }
            }
        }

    val displayName: CaString
        get() = (label?.takeIf { it.isNotBlank() } ?: share ?: host).toCaString()
}
