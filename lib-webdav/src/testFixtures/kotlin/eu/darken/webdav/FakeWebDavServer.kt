package eu.darken.webdav

import java.io.Closeable
import java.net.InetAddress
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.Credentials
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import okio.Buffer

/**
 * An in-memory WebDAV server on MockWebServer, shared with Butler's gateway tests (JVM and ART).
 * Paths in the setup API are relative to [basePath] and use `/` separators, e.g. `"docs/a b.txt"`.
 *
 * It implements PROPFIND (Depth 0 and 1; all supported props regardless of the request body), GET with
 * `Range` and `If-Match`, PUT with `If-None-Match: *` and `If-Match`, MKCOL, DELETE, MOVE and COPY with
 * `Overwrite`, Basic auth when [username] is set, and TLS when [certificate] is set.
 */
public class FakeWebDavServer(
    public val username: String? = "butler",
    public val password: String? = "butlerpass",
    public val basePath: String = "/remote.php/dav/files/butler/",
    certificate: HeldCertificate? = null,
) : Closeable {
    public enum class HrefStyle {
        /** `/remote.php/dav/files/butler/a%20b/` */
        ENCODED_PATH,

        /** `http://localhost:1234/remote.php/dav/files/butler/a%20b/` */
        FULL_URL,

        /** `/remote.php/dav/files/butler/a b/` */
        RAW_PATH,
    }

    public val server: MockWebServer = MockWebServer()

    private val lock = Any()
    private val nodes = HashMap<List<String>, Node>()
    private var etagCounter = 0
    private val baseSegments = basePath.split('/').filter { it.isNotEmpty() }

    /** `null` omits the quota props; negative values are sent as-is, like Nextcloud's `-3` for unlimited. */
    @Volatile public var quotaAvailableBytes: Long? = null

    @Volatile public var quotaUsedBytes: Long? = null

    /** Answers PUTs without `Content-Length` with 411 Length Required. */
    @Volatile public var rejectUnknownLengthPut: Boolean = false

    /** When false, ranged GETs get the whole file with 200. */
    @Volatile public var honorRange: Boolean = true

    /** Whether a 416 carries `Content-Range: bytes * /<size>` (Nextcloud sends none). */
    @Volatile public var contentRangeOn416: Boolean = true

    @Volatile public var hrefStyle: HrefStyle = HrefStyle.ENCODED_PATH

    /** Reports ETags as weak (`W/"..."`), as Apache does for files modified within the last second. */
    @Volatile public var weakEtags: Boolean = false

    /** Redirects any request on a collection URL without trailing `/` to the slashed URL, like Apache's `DirectorySlash`. */
    @Volatile public var redirectSlashlessCollections: Boolean = false

    /** Applied to every response after the request took effect, e.g. to drop the connection afterwards. */
    @Volatile public var responseTransformer: ((RecordedRequest, MockResponse) -> MockResponse)? = null

    init {
        nodes[emptyList()] = Node.Collection(Instant.now())
        if (certificate != null) {
            server.useHttps(HandshakeCertificates.Builder().heldCertificate(certificate).build().sslSocketFactory())
        }
        server.dispatcher =
            object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val response = synchronized(lock) { handle(request) }
                    return responseTransformer?.invoke(request, response) ?: response
                }
            }
    }

    public fun start(): FakeWebDavServer = apply { server.start(InetAddress.getByName("localhost"), 0) }

    /** The endpoint URL, e.g. `http://localhost:1234/remote.php/dav/files/butler/`. */
    public val baseUrl: String
        get() = server.url(basePath).toString()

    public val requestCount: Int
        get() = server.requestCount

    public fun takeRequest(): RecordedRequest = server.takeRequest()

    override fun close() {
        server.close()
    }

    /** Creates missing parent collections; returns the new ETag. */
    public fun putFile(path: String, content: ByteArray, modifiedAt: Instant = Instant.now()): String =
        synchronized(lock) {
            val segments = split(path)
            mkdirsLocked(segments.dropLast(1))
            Node.File(content, nextEtag(), modifiedAt).also { nodes[segments] = it }.etag
        }

    public fun mkdirs(path: String): Unit = synchronized(lock) { mkdirsLocked(split(path)) }

    public fun content(path: String): ByteArray? = synchronized(lock) { (nodes[split(path)] as? Node.File)?.content }

    public fun etag(path: String): String? = synchronized(lock) { (nodes[split(path)] as? Node.File)?.etag }

    public fun exists(path: String): Boolean = synchronized(lock) { split(path) in nodes }

    public fun isCollection(path: String): Boolean = synchronized(lock) { nodes[split(path)] is Node.Collection }

    private fun split(path: String): List<String> = path.split('/').filter { it.isNotEmpty() }

    private fun mkdirsLocked(segments: List<String>) {
        for (index in 1..segments.size) {
            val prefix = segments.subList(0, index)
            when (nodes[prefix]) {
                null -> nodes[prefix] = Node.Collection(Instant.now())
                is Node.File -> error("${prefix.joinToString("/")} is a file")
                is Node.Collection -> Unit
            }
        }
    }

    private fun nextEtag(): String = "\"fake-${++etagCounter}\""

    private sealed interface Node {
        val modifiedAt: Instant

        class Collection(override val modifiedAt: Instant) : Node

        class File(val content: ByteArray, val etag: String, override val modifiedAt: Instant) : Node
    }

    private fun handle(request: RecordedRequest): MockResponse {
        if (username != null) {
            val expected = Credentials.basic(username, password.orEmpty())
            if (request.headers["Authorization"] != expected) {
                return MockResponse.Builder().code(401).addHeader("WWW-Authenticate", "Basic realm=\"fake\"").build()
            }
        }
        val segments = relative(request.url) ?: return status(404)
        val node = nodes[segments]
        if (redirectSlashlessCollections && node is Node.Collection && segments.isNotEmpty() && !request.url.encodedPath.endsWith('/')) {
            val slashed = request.url.newBuilder().addPathSegment("").build()
            return MockResponse.Builder().code(301).addHeader("Location", slashed.toString()).build()
        }
        return when (request.method) {
            "PROPFIND" -> propfind(request, segments, node)
            "GET" -> get(request, node)
            "PUT" -> put(request, segments, node)
            "MKCOL" -> mkcol(request, segments, node)
            "DELETE" -> delete(segments, node)
            "MOVE", "COPY" -> transfer(request, segments, node)
            else -> status(405)
        }
    }

    private fun relative(url: HttpUrl): List<String>? {
        val segments = url.pathSegments.filter { it.isNotEmpty() }
        if (segments.size < baseSegments.size || segments.subList(0, baseSegments.size) != baseSegments) return null
        return segments.drop(baseSegments.size)
    }

    private fun status(code: Int): MockResponse = MockResponse.Builder().code(code).build()

    private fun propfind(request: RecordedRequest, segments: List<String>, node: Node?): MockResponse {
        node ?: return status(404)
        val depth = request.headers["Depth"]
        if (depth != "0" && depth != "1") return status(403)
        val members = mutableListOf(segments to node)
        if (depth == "1" && node is Node.Collection) {
            members += nodes.filterKeys { it.size == segments.size + 1 && it.subList(0, segments.size) == segments }.toList()
        }
        val xml = StringBuilder("""<?xml version="1.0" encoding="utf-8"?><d:multistatus xmlns:d="DAV:">""")
        for ((path, member) in members) {
            xml.append("<d:response><d:href>").append(escape(href(request.url, path, member is Node.Collection)))
            xml.append("</d:href><d:propstat><d:prop>")
            xml.append("<d:getlastmodified>").append(HTTP_DATE.format(member.modifiedAt)).append("</d:getlastmodified>")
            when (member) {
                is Node.Collection -> {
                    xml.append("<d:resourcetype><d:collection/></d:resourcetype>")
                    quotaAvailableBytes?.let { xml.append("<d:quota-available-bytes>$it</d:quota-available-bytes>") }
                    quotaUsedBytes?.let { xml.append("<d:quota-used-bytes>$it</d:quota-used-bytes>") }
                }
                is Node.File -> {
                    xml.append("<d:resourcetype/>")
                    xml.append("<d:getcontentlength>${member.content.size}</d:getcontentlength>")
                    xml.append("<d:getetag>").append(escape(presented(member))).append("</d:getetag>")
                    xml.append("<d:getcontenttype>application/octet-stream</d:getcontenttype>")
                }
            }
            xml.append("</d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response>")
        }
        xml.append("</d:multistatus>")
        return MockResponse.Builder()
            .code(207)
            .addHeader("Content-Type", "application/xml; charset=utf-8")
            .body(xml.toString())
            .build()
    }

    private fun href(requestUrl: HttpUrl, path: List<String>, collection: Boolean): String {
        val segments = baseSegments + path
        return when (hrefStyle) {
            HrefStyle.RAW_PATH -> "/" + segments.joinToString("/") + if (collection) "/" else ""
            else -> {
                val url =
                    requestUrl.newBuilder()
                        .encodedPath("/")
                        .apply {
                            segments.forEach(::addPathSegment)
                            if (collection) addPathSegment("")
                        }
                        .build()
                if (hrefStyle == HrefStyle.FULL_URL) url.toString() else url.encodedPath
            }
        }
    }

    private fun get(request: RecordedRequest, node: Node?): MockResponse {
        node ?: return status(404)
        if (node !is Node.File) return status(405)
        if (!matches(request.headers["If-Match"], node)) return status(412)
        val size = node.content.size.toLong()
        val offset = request.headers["Range"]?.let { Regex("""bytes=(\d+)-""").matchEntire(it.trim()) }?.groupValues?.get(1)?.toLong()
        val builder = MockResponse.Builder().addHeader("ETag", presented(node))
        if (offset == null || !honorRange) return builder.code(200).body(Buffer().write(node.content)).build()
        if (offset >= size) {
            builder.code(416)
            if (contentRangeOn416) builder.addHeader("Content-Range", "bytes */$size")
            return builder.build()
        }
        return builder
            .code(206)
            .addHeader("Content-Range", "bytes $offset-${size - 1}/$size")
            .body(Buffer().write(node.content, offset.toInt(), (size - offset).toInt()))
            .build()
    }

    private fun put(request: RecordedRequest, segments: List<String>, node: Node?): MockResponse {
        if (segments.isEmpty() || node is Node.Collection) return status(405)
        if (nodes[segments.dropLast(1)] !is Node.Collection) return status(409)
        if (request.headers["If-None-Match"] == "*" && node != null) return status(412)
        if (request.headers["If-Match"] != null && (node !is Node.File || !matches(request.headers["If-Match"], node))) {
            return status(412)
        }
        if (rejectUnknownLengthPut && request.headers["Content-Length"] == null) return status(411)
        val file = Node.File(request.body?.toByteArray() ?: ByteArray(0), nextEtag(), Instant.now())
        nodes[segments] = file
        return MockResponse.Builder().code(if (node == null) 201 else 204).addHeader("ETag", presented(file)).build()
    }

    private fun mkcol(request: RecordedRequest, segments: List<String>, node: Node?): MockResponse {
        if (node != null) return status(405)
        if ((request.body?.size ?: 0) > 0) return status(415)
        if (nodes[segments.dropLast(1)] !is Node.Collection) return status(409)
        nodes[segments] = Node.Collection(Instant.now())
        return status(201)
    }

    private fun delete(segments: List<String>, node: Node?): MockResponse {
        node ?: return status(404)
        if (segments.isEmpty()) return status(403)
        nodes.keys.removeAll { it.size >= segments.size && it.subList(0, segments.size) == segments }
        return status(204)
    }

    private fun transfer(request: RecordedRequest, source: List<String>, node: Node?): MockResponse {
        node ?: return status(404)
        val destination = request.headers["Destination"]?.toHttpUrlOrNull()?.let(::relative) ?: return status(400)
        if (source.isEmpty() || destination.isEmpty()) return status(403)
        if (destination.size >= source.size && destination.subList(0, source.size) == source) return status(403)
        if (nodes[destination.dropLast(1)] !is Node.Collection) return status(409)
        val existed = destination in nodes
        if (existed) {
            if (request.headers["Overwrite"].equals("F", ignoreCase = true)) return status(412)
            nodes.keys.removeAll { it.size >= destination.size && it.subList(0, destination.size) == destination }
        }
        val moved = nodes.filterKeys { it.size >= source.size && it.subList(0, source.size) == source }
        for ((path, member) in moved) {
            val target = destination + path.drop(source.size)
            nodes[target] =
                when {
                    request.method == "MOVE" -> member
                    member is Node.File -> Node.File(member.content.copyOf(), nextEtag(), member.modifiedAt)
                    else -> Node.Collection(member.modifiedAt)
                }
        }
        if (request.method == "MOVE") nodes.keys.removeAll(moved.keys)
        return status(if (existed) 204 else 201)
    }

    private fun presented(file: Node.File): String = if (weakEtags) "W/${file.etag}" else file.etag

    /** If-Match compares strongly: a weak ETag never matches. */
    private fun matches(ifMatch: String?, file: Node.File): Boolean =
        ifMatch == null || ifMatch == "*" || (!weakEtags && ifMatch == file.etag)

    private fun escape(value: String): String = value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    private companion object {
        val HTTP_DATE: DateTimeFormatter = DateTimeFormatter.RFC_1123_DATE_TIME.withZone(ZoneOffset.UTC)
    }
}
