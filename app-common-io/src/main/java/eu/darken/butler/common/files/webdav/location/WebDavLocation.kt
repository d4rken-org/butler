package eu.darken.butler.common.files.webdav.location

import eu.darken.butler.common.ca.CaString
import eu.darken.butler.common.ca.toCaString
import eu.darken.webdav.WebDavEndpoint
import java.net.URI
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * A WebDAV server the user added manually: its collection URL, how to authenticate, and which
 * certificate it may present beyond the ones the system trusts.
 *
 * The secret itself is never part of this type, it lives in the credential vault keyed by [id].
 * [credentialVersion] is the generation token shared with the credential row, see
 * [WebDavLocationManager] for the write ordering that keeps the two databases consistent.
 */
data class WebDavLocation(
    val id: Uuid,
    val label: String?,
    /** Normalized as [WebDavEndpoint.url] produces it, e.g. `https://cloud.example.com/remote.php/dav/files/alice/`. */
    val url: String,
    val authType: AuthType,
    /** Set for [AuthType.BASIC] only. */
    val username: String?,
    /** Always false for [AuthType.ANONYMOUS], which has nothing to remember. */
    val rememberCredential: Boolean,
    val credentialVersion: Int,
    /**
     * A certificate the user accepted although the system does not trust it. Null when nothing is
     * pinned: a system-trusted HTTPS server, or plain HTTP, which has no certificate at all.
     */
    val pinnedCertificate: TrustedCertificate?,
    /** Increments whenever [pinnedCertificate] changes or the URL moves to another origin. */
    val trustRevision: Int,
    val createdAt: Instant,
    val updatedAt: Instant,
    /** When this host's port was last found answering: the server was seen, not signed in to. */
    val lastSeenAt: Instant? = null,
) {

    val host: String
    val port: Int
    val isSecure: Boolean

    init {
        val endpoint = WebDavEndpoint(url)
        require(endpoint.url == url) { "URL is not normalized: $url" }
        host = endpoint.host
        port = endpoint.port
        isSecure = endpoint.isSecure

        when (authType) {
            AuthType.ANONYMOUS -> {
                require(username == null) { "An anonymous location has no username" }
                require(!rememberCredential) { "An anonymous location has no credential to remember" }
            }

            AuthType.BASIC -> {
                require(!username.isNullOrBlank()) { "A Basic location needs a username" }
                require(':' !in username) { "HTTP Basic usernames cannot contain ':'" }
            }
        }
        require(pinnedCertificate == null || isSecure) { "A plain HTTP location cannot pin a certificate" }
    }

    enum class AuthType {
        ANONYMOUS,
        BASIC,
    }

    fun toEndpoint(): WebDavEndpoint = WebDavEndpoint(url)

    /**
     * `alice@cloud.example.com/remote.php/dav/files/alice` or `nas.local:8080/dav`, the subtitle shown
     * under the name: no scheme, the port only when it is not the scheme's default.
     */
    val endpointLabel: String
        get() = buildString {
            username?.let { append(it).append("@") }
            if (':' in host) append("[$host]") else append(host)
            if (port != defaultPort(isSecure)) append(":$port")
            append(URI(url).path.removeSuffix("/"))
        }

    val displayName: CaString
        get() = (label?.takeIf { it.isNotBlank() } ?: host).toCaString()

    companion object {
        const val DEFAULT_HTTP_PORT = 80
        const val DEFAULT_HTTPS_PORT = 443

        fun defaultPort(isSecure: Boolean): Int = if (isSecure) DEFAULT_HTTPS_PORT else DEFAULT_HTTP_PORT
    }
}
