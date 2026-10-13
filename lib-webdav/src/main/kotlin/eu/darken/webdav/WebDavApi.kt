package eu.darken.webdav

import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * A WebDAV collection URL such as `https://cloud.example/remote.php/dav/files/alice/`, normalized to
 * end with `/`. Only absolute `http`/`https` URLs without userinfo, query or fragment are accepted.
 */
public class WebDavEndpoint(baseUrl: String) {
    internal val httpUrl: HttpUrl

    init {
        val trimmed = baseUrl.trim()
        require(trimmed.startsWith("http://", ignoreCase = true) || trimmed.startsWith("https://", ignoreCase = true)) {
            "WebDAV endpoints must be http:// or https:// URLs"
        }
        val parsed = requireNotNull(trimmed.toHttpUrlOrNull()) { "Malformed WebDAV endpoint URL" }
        require(parsed.username.isEmpty() && parsed.password.isEmpty()) { "WebDAV endpoints must not carry userinfo" }
        require(parsed.query == null && parsed.fragment == null) { "WebDAV endpoints must not carry a query or fragment" }
        httpUrl = if (parsed.encodedPath.endsWith('/')) parsed else parsed.newBuilder().addPathSegment("").build()
    }

    public val url: String
        get() = httpUrl.toString()

    public val host: String
        get() = httpUrl.host

    public val port: Int
        get() = httpUrl.port

    public val isSecure: Boolean
        get() = httpUrl.isHttps

    override fun equals(other: Any?): Boolean = other is WebDavEndpoint && httpUrl == other.httpUrl

    override fun hashCode(): Int = httpUrl.hashCode()

    override fun toString(): String = url
}

/** A path relative to the endpoint, e.g. `WebDavPath(listOf("a b", "c.txt"))` is `<endpoint>a%20b/c.txt`. */
public class WebDavPath(segments: List<String> = emptyList()) {
    public val segments: List<String> = java.util.Collections.unmodifiableList(segments.toList())

    init {
        require(this.segments.all(::isValidSegment)) { "Invalid WebDAV path segment" }
    }

    public val name: String?
        get() = segments.lastOrNull()

    public fun child(name: String): WebDavPath = WebDavPath(segments + name)

    public val parent: WebDavPath?
        get() = segments.takeIf { it.isNotEmpty() }?.let { WebDavPath(it.dropLast(1)) }

    override fun equals(other: Any?): Boolean = other is WebDavPath && segments == other.segments

    override fun hashCode(): Int = segments.hashCode()

    override fun toString(): String = "/" + segments.joinToString("/")

    public companion object {
        public val Root: WebDavPath = WebDavPath()

        public fun isValidSegment(value: String): Boolean =
            value.isNotEmpty() &&
                value != "." &&
                value != ".." &&
                value.none { it == '/' || it == '\u0000' }
    }
}

/** The caller owns the password array and may erase it once connect returns. */
public sealed interface WebDavCredentials {
    public data object Anonymous : WebDavCredentials

    public class Basic(public val username: String, public val password: CharArray) : WebDavCredentials {
        init {
            require(':' !in username) { "HTTP Basic usernames cannot contain ':'" }
        }

        override fun toString(): String = "Basic(<redacted>)"
    }
}

/** An X.509 certificate in DER encoding, as presented by a server. */
public class ServerCertificate(der: ByteArray) {
    private val bytes: ByteArray = der.copyOf()
    private val x509: X509Certificate =
        try {
            CertificateFactory.getInstance("X.509").generateCertificate(ByteArrayInputStream(bytes)) as X509Certificate
        } catch (error: Exception) {
            throw IllegalArgumentException("Malformed X.509 certificate", error)
        }

    public val encoded: ByteArray
        get() = bytes.copyOf()

    /** Uppercase hex pairs separated by colons, e.g. `3A:0F:...`. */
    public val sha256Fingerprint: String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString(":") { "%02X".format(it) }

    /** RFC 2253 distinguished name, e.g. `CN=cloud.example,O=Example`. */
    public val subject: String = x509.subjectX500Principal.name

    public val issuer: String = x509.issuerX500Principal.name

    public val notBefore: Instant = Instant.fromEpochMilliseconds(x509.notBefore.time)

    public val notAfter: Instant = Instant.fromEpochMilliseconds(x509.notAfter.time)

    internal fun matches(der: ByteArray): Boolean = bytes.contentEquals(der)

    override fun equals(other: Any?): Boolean = other is ServerCertificate && bytes.contentEquals(other.bytes)

    override fun hashCode(): Int = bytes.contentHashCode()

    override fun toString(): String = "ServerCertificate($subject $sha256Fingerprint)"
}

/** Decides the server certificate during the TLS handshake, before any request is sent. Ignored for `http://`. */
public sealed interface CertificatePolicy {
    /**
     * Accepts chains the system trusts for the endpoint's host name. Anything else fails with
     * [WebDavException.Kind.CERTIFICATE_UNTRUSTED], carrying the presented leaf for the user to confirm.
     */
    public data object System : CertificatePolicy

    /**
     * Accepts a system-trusted chain for the endpoint's host name or a leaf equal to [certificate], in
     * which case the host name is not checked. Any other leaf fails with
     * [WebDavException.Kind.CERTIFICATE_MISMATCH].
     */
    public data class Pinned(val certificate: ServerCertificate) : CertificatePolicy
}

public data class WebDavConfig(
    val connectTimeout: Duration = 10.seconds,
    val readTimeout: Duration = 30.seconds,
) {
    init {
        require(connectTimeout.isFinite() && connectTimeout.inWholeMilliseconds in 1..Int.MAX_VALUE)
        require(readTimeout.isFinite() && readTimeout.inWholeMilliseconds in 1..Int.MAX_VALUE)
    }
}

public fun interface WebDavConnector {
    /** Sends `PROPFIND Depth: 0` to the endpoint, which must be a collection. */
    public suspend fun connect(
        endpoint: WebDavEndpoint,
        credentials: WebDavCredentials,
        certificatePolicy: CertificatePolicy,
    ): WebDavSession
}

public interface WebDavResource {
    public suspend fun close()
}

/** Closes the resource; a [WebDavUpload] whose block fails is aborted instead of committed. */
public suspend inline fun <T : WebDavResource, R> T.use(block: (T) -> R): R {
    var failure: Throwable? = null
    try {
        return block(this)
    } catch (error: Throwable) {
        failure = error
        throw error
    } finally {
        withContext(NonCancellable) {
            try {
                if (failure != null && this@use is WebDavUpload) abort()
                close()
            } catch (error: Throwable) {
                if (failure == null) throw error
                if (failure !== error) failure.addSuppressed(error)
            }
        }
    }
}

/** [size] and [contentType] are typically `null` for collections. */
public data class WebDavEntry(
    val path: WebDavPath,
    val isCollection: Boolean,
    val size: Long?,
    val modifiedAt: Instant?,
    val etag: String?,
    val contentType: String?,
)

/** RFC 4331 quota of a collection. [usedBytes] is `null` when the server does not report it. */
public data class WebDavCapacity(val availableBytes: Long, val usedBytes: Long?) {
    public val totalBytes: Long?
        get() = usedBytes?.takeIf { Long.MAX_VALUE - it >= availableBytes }?.let { it + availableBytes }
}

public interface WebDavSession : WebDavResource {
    public suspend fun stat(path: WebDavPath): WebDavEntry

    /** Cold flow over one `PROPFIND Depth: 1`; excludes [path] itself. */
    public fun list(path: WebDavPath): Flow<WebDavEntry>

    public suspend fun mkdir(path: WebDavPath)

    /** Collections are deleted with their contents. */
    public suspend fun delete(path: WebDavPath)

    /** Never replaces an existing destination. */
    public suspend fun move(source: WebDavPath, destination: WebDavPath)

    /** Copies collections recursively and never replaces an existing destination. */
    public suspend fun copy(source: WebDavPath, destination: WebDavPath)

    /**
     * Reads from [offset]. When [WebDavDownload.rangeHonored] is false, the server ignored the range
     * and the body starts at offset 0. With [expectedEtag], a changed file fails with
     * [WebDavException.Kind.CHANGED].
     */
    public suspend fun openRead(path: WebDavPath, offset: Long = 0, expectedEtag: String? = null): WebDavDownload

    /**
     * Streams a PUT. A known [contentLength] must be written exactly; otherwise the body is chunked.
     * With [createOnly], an existing file fails with [WebDavException.Kind.ALREADY_EXISTS] and is left untouched.
     */
    public suspend fun openWrite(path: WebDavPath, contentLength: Long? = null, createOnly: Boolean = false): WebDavUpload

    /** `null` when the server reports no quota or an unlimited/unknown one. */
    public suspend fun capacity(path: WebDavPath): WebDavCapacity?

    /** Cancels in-flight calls and releases pooled connections; safe from any thread and idempotent. */
    public fun disconnect()
}

public interface WebDavDownload : WebDavResource {
    /** Bytes in this response body, if the server declared them. */
    public val contentLength: Long?

    /** Size of the whole file, if the server declared it. */
    public val totalLength: Long?

    public val etag: String?

    public val rangeHonored: Boolean

    /** Returns -1 at EOF; a zero-length read returns 0. */
    public suspend fun read(buffer: ByteArray, start: Int = 0, length: Int = buffer.size - start): Int

    /** Shares this download's lifetime. For adapters driven on an I/O thread. */
    public fun blocking(): InputStream
}

public interface WebDavUpload : WebDavResource {
    public suspend fun write(buffer: ByteArray, start: Int = 0, length: Int = buffer.size - start)

    /** Shares this upload's lifetime; closing the stream commits like [close]. For adapters driven on an I/O thread. */
    public fun blocking(): OutputStream

    /**
     * Cancels the request before the body is complete; idempotent, and [close] afterwards does nothing.
     * An incomplete known-length body is discarded by the server. A chunked body lacks its final chunk,
     * yet some servers (Nextcloud) store what arrived.
     */
    public fun abort()

    /** Ends the body and waits for the server's response; throws if the upload failed. */
    override suspend fun close()
}

public class WebDavException(
    public val kind: Kind,
    public val httpStatus: Int? = null,
    public val presentedCertificate: ServerCertificate? = null,
    message: String = "WebDAV operation failed: $kind" + (httpStatus?.let { " (HTTP $it)" } ?: ""),
    cause: Throwable? = null,
) : IOException(message, cause) {
    public enum class Kind {
        MISSING,
        ALREADY_EXISTS,
        ACCESS_DENIED,
        AUTHENTICATION,
        CERTIFICATE_UNTRUSTED,
        CERTIFICATE_MISMATCH,
        CONFLICT,
        LOCKED,
        DISK_FULL,
        NOT_DIRECTORY,
        CHANGED,
        TRANSPORT,
        PROTOCOL,
        UNSUPPORTED,
        OTHER,
    }
}
