package eu.darken.webdav

import java.io.IOException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.xml.sax.SAXException

internal class OkHttpWebDavSession(
    private val endpoint: WebDavEndpoint,
    /** Retries connection failures; only for PROPFIND and GET. */
    private val readClient: OkHttpClient,
    private val mutationClient: OkHttpClient,
) : WebDavSession {
    internal suspend fun verifyEndpoint() {
        if (!stat(WebDavPath.Root).isCollection) {
            throw WebDavException(WebDavException.Kind.NOT_DIRECTORY, message = "The endpoint is not a WebDAV collection")
        }
    }

    internal fun url(path: WebDavPath): HttpUrl =
        endpoint.httpUrl.newBuilder().apply { path.segments.forEach(::addPathSegment) }.build()

    override suspend fun stat(path: WebDavPath): WebDavEntry {
        val (requestUrl, responses) = propfind(path, depth = 0, PROPS_BODY)
        val requested = requestUrl.pathSegments.filter { it.isNotEmpty() }
        val match =
            responses.firstOrNull { hrefSegments(requestUrl, it.href) == requested }
                ?: responses.singleOrNull()
                ?: throw protocolFailure("PROPFIND response does not describe the requested resource")
        return match.toEntry(path)
    }

    override fun list(path: WebDavPath): Flow<WebDavEntry> = flow {
        val (requestUrl, responses) = propfind(path, depth = 1, PROPS_BODY)
        // After a redirect, members are named relative to the collection that answered.
        val requested = requestUrl.pathSegments.filter { it.isNotEmpty() }
        val entries = mutableListOf<WebDavEntry>()
        var self: DavResponse? = null
        for (response in responses) {
            val segments = hrefSegments(requestUrl, response.href) ?: continue
            when {
                segments == requested -> self = response
                segments.size == requested.size + 1 && segments.subList(0, requested.size) == requested -> {
                    val name = segments.last()
                    if (WebDavPath.isValidSegment(name)) entries += response.toEntry(path.child(name))
                }
            }
        }
        if (self != null && !self.props.isCollection) {
            throw WebDavException(WebDavException.Kind.NOT_DIRECTORY, message = "PROPFIND target is not a collection")
        }
        entries.forEach { emit(it) }
    }

    override suspend fun mkdir(path: WebDavPath) {
        send("MKCOL", url(path)).use { (_, response) ->
            if (response.code != 200 && response.code != 201) throw statusFailure(response.code, "MKCOL")
        }
    }

    override suspend fun delete(path: WebDavPath) {
        send("DELETE", url(path)).use { checkMutation(it, "DELETE", setOf(200, 202, 204)) }
    }

    override suspend fun move(source: WebDavPath, destination: WebDavPath) = transfer("MOVE", source, destination)

    override suspend fun copy(source: WebDavPath, destination: WebDavPath) = transfer("COPY", source, destination)

    private suspend fun transfer(method: String, source: WebDavPath, destination: WebDavPath) {
        val headers =
            Headers.Builder()
                .add("Destination", url(destination).toString())
                .add("Overwrite", "F")
                .apply { if (method == "COPY") add("Depth", "infinity") }
                .build()
        send(method, url(source), headers).use { checkMutation(it, method, setOf(201, 204), Precondition.ABSENT) }
    }

    /** A 207 reports per-resource failures; the first one fails the whole operation. */
    private suspend fun checkMutation(
        exchange: Exchange,
        method: String,
        success: Set<Int>,
        precondition: Precondition = Precondition.NONE,
    ) {
        val code = exchange.response.code
        if (code in success) return
        if (code != 207) throw statusFailure(code, method, precondition)
        val failures = parse(exchange).mapNotNull { it.status }.filter { it !in 200..299 }
        val first = failures.firstOrNull { it != 424 } ?: failures.firstOrNull() ?: return
        throw statusFailure(first, method, precondition)
    }

    override suspend fun openRead(path: WebDavPath, offset: Long, expectedEtag: String?): WebDavDownload {
        require(offset >= 0) { "Negative offset" }
        // If-Match compares strongly, so a weak ETag would never match; it is compared here instead.
        val weakExpectation = expectedEtag?.takeIf { it.startsWith("W/") }
        val headers =
            Headers.Builder()
                .add("Accept-Encoding", "identity")
                .apply {
                    if (offset > 0) add("Range", "bytes=$offset-")
                    if (expectedEtag != null && weakExpectation == null) add("If-Match", expectedEtag)
                }
                .build()
        val exchange = send("GET", url(path), headers)
        val response = exchange.response
        try {
            val etag = response.header("ETag")
            if (weakExpectation != null && response.code in 200..299 && !weaklyEqual(weakExpectation, etag)) {
                throw WebDavException(WebDavException.Kind.CHANGED, response.code, message = "GET failed: the ETag changed")
            }
            val declared = response.body.contentLength().takeIf { it >= 0 }
            return when (response.code) {
                200 ->
                    OkHttpDownload(
                        exchange,
                        contentLength = declared,
                        totalLength = declared,
                        etag = etag,
                        rangeHonored = offset == 0L,
                    )
                206 -> {
                    val range = response.header("Content-Range")?.let(::parseContentRange)
                    if (range == null || range.first != offset) {
                        throw protocolFailure("Content-Range does not start at the requested offset", 206)
                    }
                    OkHttpDownload(exchange, declared, range.second, etag, rangeHonored = true)
                }
                416 -> {
                    if (offset == 0L) throw protocolFailure("Unrequested range refusal", 416)
                    val total = response.header("Content-Range")?.let(::parseUnsatisfiedRange)
                    if (total != null && total > offset) {
                        throw protocolFailure("Range refused before the end of the file", 416)
                    }
                    response.close()
                    EmptyDownload(totalLength = total, etag = etag)
                }
                else -> throw statusFailure(response.code, "GET", if (expectedEtag != null) Precondition.MATCH else Precondition.NONE)
            }
        } catch (error: Throwable) {
            response.close()
            throw error
        }
    }

    override suspend fun openWrite(path: WebDavPath, contentLength: Long?, createOnly: Boolean): WebDavUpload {
        require(contentLength == null || contentLength >= 0) { "Negative content length" }
        val request = Request.Builder().url(url(path)).apply { if (createOnly) header("If-None-Match", "*") }
        return OkHttpUpload(mutationClient, request, contentLength, if (createOnly) Precondition.ABSENT else Precondition.NONE)
    }

    override suspend fun capacity(path: WebDavPath): WebDavCapacity? {
        val (_, responses) = propfind(path, depth = 0, QUOTA_BODY)
        val props = responses.singleOrNull()?.props ?: return null
        val available = props.quotaAvailable?.takeIf { it >= 0 } ?: return null
        return WebDavCapacity(available, props.quotaUsed?.takeIf { it >= 0 })
    }

    override fun disconnect() {
        for (client in listOf(readClient, mutationClient)) {
            client.dispatcher.cancelAll()
            client.connectionPool.evictAll()
        }
    }

    override suspend fun close() = disconnect()

    private suspend fun propfind(path: WebDavPath, depth: Int, body: ByteArray): Pair<HttpUrl, List<DavResponse>> {
        send("PROPFIND", url(path), Headers.headersOf("Depth", depth.toString()), body).use { exchange ->
            val response = exchange.response
            if (response.code != 207) throw statusFailure(response.code, "PROPFIND")
            return response.request.url to parse(exchange)
        }
    }

    private suspend fun parse(exchange: Exchange): List<DavResponse> =
        try {
            exchange.call.blockingIo { exchange.response.body.byteStream().use(Multistatus::parse) }
        } catch (error: SAXException) {
            throw protocolFailure("Malformed multistatus response", exchange.response.code, error)
        } catch (error: IOException) {
            throw error.toWebDavException()
        }

    /**
     * Sends a request and applies the redirect policy: PROPFIND and GET follow up to [MAX_REDIRECTS]
     * same-origin redirects; a body-less mutation redirected to its own URL plus `/` (Apache's
     * `DirectorySlash` for collections) is re-sent once; every other redirect fails.
     */
    private suspend fun send(method: String, url: HttpUrl, headers: Headers = Headers.headersOf(), body: ByteArray? = null): Exchange {
        val readOnly = method == "PROPFIND" || method == "GET"
        val client = if (readOnly) readClient else mutationClient
        var target = url
        var hops = 0
        while (true) {
            val request =
                Request.Builder()
                    .url(target)
                    .headers(headers)
                    .method(method, body?.toRequestBody(XML))
                    .build()
            val call = client.newCall(request)
            val response =
                try {
                    call.await()
                } catch (error: Throwable) {
                    throw error.toWebDavException()
                }
            if (response.code !in REDIRECTS) return Exchange(call, response)
            val location = response.header("Location")?.let(target::resolve)
            val code = response.code
            response.close()
            target =
                when {
                    location == null -> throw protocolFailure("Redirect without a usable Location", code)
                    readOnly && hops < MAX_REDIRECTS && location.sameOrigin(endpoint.httpUrl) -> location
                    !readOnly && hops == 0 && location.isSlashedFormOf(target) -> location
                    else -> throw protocolFailure("Refused $method redirect", code)
                }
            hops++
        }
    }

    private fun HttpUrl.sameOrigin(other: HttpUrl): Boolean = scheme == other.scheme && host == other.host && port == other.port

    private fun HttpUrl.isSlashedFormOf(url: HttpUrl): Boolean =
        sameOrigin(url) &&
            query == null &&
            !url.encodedPath.endsWith('/') &&
            pathSegments == url.pathSegments + ""

    private fun DavResponse.toEntry(path: WebDavPath) =
        WebDavEntry(
            path = path,
            isCollection = props.isCollection,
            size = props.contentLength.takeUnless { props.isCollection },
            modifiedAt = props.lastModified,
            etag = props.etag,
            contentType = props.contentType.takeUnless { props.isCollection },
        )

    companion object {
        private const val MAX_REDIRECTS = 5
        private val REDIRECTS = setOf(300, 301, 302, 303, 307, 308)
        private val XML = "application/xml; charset=utf-8".toMediaType()

        private val PROPS_BODY =
            """
            <?xml version="1.0" encoding="utf-8"?>
            <d:propfind xmlns:d="DAV:"><d:prop>
            <d:resourcetype/><d:getcontentlength/><d:getlastmodified/><d:getetag/><d:getcontenttype/>
            </d:prop></d:propfind>
            """.trimIndent().encodeToByteArray()

        private val QUOTA_BODY =
            """
            <?xml version="1.0" encoding="utf-8"?>
            <d:propfind xmlns:d="DAV:"><d:prop>
            <d:quota-available-bytes/><d:quota-used-bytes/>
            </d:prop></d:propfind>
            """.trimIndent().encodeToByteArray()

        // "bytes 100-199/1000" gives 100 to 1000; an unknown total ("/*") gives null.
        internal fun parseContentRange(value: String): Pair<Long, Long?>? {
            val match = Regex("""^\s*bytes\s+(\d+)-(\d+)/(\d+|\*)\s*$""", RegexOption.IGNORE_CASE).find(value) ?: return null
            val (first, last, total) = match.destructured
            val start = first.toLongOrNull() ?: return null
            val end = last.toLongOrNull() ?: return null
            val size = if (total == "*") null else total.toLongOrNull() ?: return null
            if (end < start || (size != null && end >= size)) return null
            return start to size
        }

        /** RFC 9110 weak comparison: `W/"a"` equals `"a"`; a missing ETag equals nothing. */
        internal fun weaklyEqual(expected: String, actual: String?): Boolean =
            actual != null && expected.removePrefix("W/") == actual.trim().removePrefix("W/")

        // "bytes */1000" gives 1000.
        internal fun parseUnsatisfiedRange(value: String): Long? =
            Regex("""^\s*bytes\s+\*/(\d+)\s*$""", RegexOption.IGNORE_CASE).find(value)?.groupValues?.get(1)?.toLongOrNull()
    }
}
