package eu.darken.webdav

import java.io.InputStream
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import javax.xml.parsers.SAXParserFactory
import kotlin.time.Instant
import okhttp3.HttpUrl
import org.xml.sax.Attributes
import org.xml.sax.InputSource
import org.xml.sax.SAXException
import org.xml.sax.ext.DefaultHandler2

internal class DavProps {
    var isCollection: Boolean = false
    var contentLength: Long? = null
    var lastModified: Instant? = null
    var etag: String? = null
    var contentType: String? = null
    var quotaAvailable: Long? = null
    var quotaUsed: Long? = null
}

/** One `DAV:response`: [status] is its own status element; [props] merges every 2xx `propstat`. */
internal class DavResponse(val href: String, val status: Int?, val props: DavProps)

/**
 * Parses a `DAV:multistatus` body. Only portable SAX hardening is used, because Android's Expat-backed
 * parser rejects implementation-specific feature names: external entities are off and any DOCTYPE
 * aborts the parse.
 */
internal object Multistatus {
    private const val DAV = "DAV:"

    fun parse(input: InputStream): List<DavResponse> {
        val handler = Handler()
        val reader = SAXParserFactory.newInstance().apply { isNamespaceAware = true }.newSAXParser().xmlReader
        reader.setFeature("http://xml.org/sax/features/external-general-entities", false)
        reader.setFeature("http://xml.org/sax/features/external-parameter-entities", false)
        reader.setProperty("http://xml.org/sax/properties/lexical-handler", handler)
        reader.contentHandler = handler
        reader.errorHandler = handler
        reader.parse(InputSource(input))
        if (!handler.sawRoot) throw SAXException("Not a DAV:multistatus document")
        return handler.responses
    }

    /** `HTTP/1.1 404 Not Found` gives 404. */
    fun parseStatus(value: String): Int? = value.trim().split(Regex("\\s+")).getOrNull(1)?.toIntOrNull()

    private fun parseDate(value: String): Instant? =
        try {
            Instant.fromEpochSeconds(ZonedDateTime.parse(value.trim(), DateTimeFormatter.RFC_1123_DATE_TIME).toEpochSecond())
        } catch (_: Exception) {
            null
        }

    private class Handler : DefaultHandler2() {
        val responses = mutableListOf<DavResponse>()
        var sawRoot = false

        private val path = ArrayDeque<String>()
        private val text = StringBuilder()
        private var href: String? = null
        private var responseStatus: Int? = null
        private var props = DavProps()
        private var propstatProps: DavProps? = null
        private var propstatStatus: Int? = null

        override fun startDTD(name: String?, publicId: String?, systemId: String?) {
            throw SAXException("DOCTYPE is not allowed")
        }

        override fun startElement(uri: String?, localName: String, qName: String?, attributes: Attributes?) {
            val name = if (uri == DAV) localName else "{$uri}$localName"
            if (path.isEmpty()) {
                if (name != "multistatus") throw SAXException("Not a DAV:multistatus document")
                sawRoot = true
            }
            when (name) {
                "response" ->
                    if (path.lastOrNull() == "multistatus") {
                        href = null
                        responseStatus = null
                        props = DavProps()
                    }
                "propstat" ->
                    if (path.lastOrNull() == "response") {
                        propstatProps = DavProps()
                        propstatStatus = null
                    }
                "collection" -> if (path.lastOrNull() == "resourcetype") propstatProps?.isCollection = true
            }
            path.addLast(name)
            text.setLength(0)
        }

        override fun characters(ch: CharArray, start: Int, length: Int) {
            text.appendRange(ch, start, start + length)
        }

        override fun endElement(uri: String?, localName: String, qName: String?) {
            val name = path.removeLast()
            val parent = path.lastOrNull()
            val value = text.toString()
            text.setLength(0)
            val target = propstatProps
            when {
                name == "href" && parent == "response" -> href = value.trim()
                name == "status" && parent == "response" -> responseStatus = parseStatus(value)
                name == "status" && parent == "propstat" -> propstatStatus = parseStatus(value)
                parent == "prop" && target != null ->
                    when (name) {
                        "getcontentlength" -> target.contentLength = value.trim().toLongOrNull()
                        "getlastmodified" -> target.lastModified = parseDate(value)
                        "getetag" -> target.etag = value.trim().ifEmpty { null }
                        "getcontenttype" -> target.contentType = value.trim().ifEmpty { null }
                        "quota-available-bytes" -> target.quotaAvailable = value.trim().toLongOrNull()
                        "quota-used-bytes" -> target.quotaUsed = value.trim().toLongOrNull()
                    }
                name == "propstat" && parent == "response" -> {
                    val status = propstatStatus
                    if (target != null && (status == null || status in 200..299)) props.merge(target)
                    propstatProps = null
                }
                name == "response" && parent == "multistatus" -> {
                    val responseHref = href ?: throw SAXException("DAV:response without DAV:href")
                    responses += DavResponse(responseHref, responseStatus, props)
                }
            }
        }

        private fun DavProps.merge(other: DavProps) {
            isCollection = isCollection || other.isCollection
            contentLength = other.contentLength ?: contentLength
            lastModified = other.lastModified ?: lastModified
            etag = other.etag ?: etag
            contentType = other.contentType ?: contentType
            quotaAvailable = other.quotaAvailable ?: quotaAvailable
            quotaUsed = other.quotaUsed ?: quotaUsed
        }
    }
}

/**
 * Maps a multistatus href to the decoded, non-empty path segments it names. Absolute paths, full URLs
 * (whose host is ignored, as reverse proxies may rewrite it), percent-encoded and raw forms and trailing
 * slashes all resolve against [requestUrl]: `/dav/a%20b/`, `/dav/a b` and `https://other/dav/a%20b`
 * give `[dav, a b]`.
 */
internal fun hrefSegments(requestUrl: HttpUrl, href: String): List<String>? =
    requestUrl.resolve(href.trim())?.pathSegments?.filter { it.isNotEmpty() }
