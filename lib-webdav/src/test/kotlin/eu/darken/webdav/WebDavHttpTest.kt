package eu.darken.webdav

import eu.darken.webdav.WebDavException.Kind
import java.net.InetAddress
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.QueueDispatcher
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource

/** Protocol edge cases against scripted responses. */
class WebDavHttpTest {
    private val server = MockWebServer().apply { start(InetAddress.getByName("localhost"), 0) }
    private val other = MockWebServer().apply { start(InetAddress.getByName("localhost"), 0) }

    @AfterEach
    fun tearDown() {
        server.close()
        other.close()
    }

    private val base = "/dav/"

    private fun multistatus(vararg responses: String) =
        MockResponse.Builder()
            .code(207)
            .addHeader("Content-Type", "application/xml")
            .body("""<?xml version="1.0"?><d:multistatus xmlns:d="DAV:">${responses.joinToString("")}</d:multistatus>""")
            .build()

    private fun collection(href: String) =
        "<d:response><d:href>$href</d:href><d:propstat><d:prop><d:resourcetype><d:collection/></d:resourcetype></d:prop>" +
            "<d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response>"

    private fun file(href: String, size: Long = 1) =
        "<d:response><d:href>$href</d:href><d:propstat><d:prop><d:resourcetype/><d:getcontentlength>$size</d:getcontentlength>" +
            "</d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response>"

    private fun status(code: Int, vararg headers: Pair<String, String>) =
        MockResponse.Builder().code(code).apply { headers.forEach { (name, value) -> addHeader(name, value) } }.build()

    private suspend fun connect(): WebDavSession {
        server.enqueue(multistatus(collection(base)))
        return OkHttpWebDavConnector()
            .connect(WebDavEndpoint(server.url(base).toString()), basic(), CertificatePolicy.System)
            .also { server.takeRequest() }
    }

    @ParameterizedTest(name = "{0} {1} -> {2}")
    @CsvSource(
        "PROPFIND, 404, MISSING",
        "PROPFIND, 410, MISSING",
        "PROPFIND, 403, ACCESS_DENIED",
        "PROPFIND, 401, AUTHENTICATION",
        "PROPFIND, 405, UNSUPPORTED",
        "PROPFIND, 501, UNSUPPORTED",
        "PROPFIND, 500, OTHER",
        "PROPFIND, 200, PROTOCOL",
        "MKCOL, 405, ALREADY_EXISTS",
        "MKCOL, 409, CONFLICT",
        "MKCOL, 507, DISK_FULL",
        "MKCOL, 423, LOCKED",
        "DELETE, 423, LOCKED",
        "DELETE, 403, ACCESS_DENIED",
        "MOVE, 412, ALREADY_EXISTS",
        "COPY, 412, ALREADY_EXISTS",
        "COPY, 507, DISK_FULL",
        "PUT, 413, DISK_FULL",
        "PUT, 507, DISK_FULL",
        "PUT, 409, CONFLICT",
        "PUT, 412, OTHER",
        "GET, 412, CHANGED",
        "GET, 404, MISSING",
    )
    fun `status codes map to kinds`(method: String, code: Int, kind: Kind): Unit = runBlocking {
        connect().use {
            server.enqueue(status(code))
            val error =
                assertKind(kind) {
                    when (method) {
                        "PROPFIND" -> it.stat(path("x"))
                        "MKCOL" -> it.mkdir(path("x"))
                        "DELETE" -> it.delete(path("x"))
                        "MOVE" -> it.move(path("x"), path("y"))
                        "COPY" -> it.copy(path("x"), path("y"))
                        "PUT" -> it.writeAll(path("x"), byteArrayOf(1))
                        "GET" -> it.openRead(path("x"), expectedEtag = "\"e\"")
                    }
                }
            assertEquals(code, error.httpStatus)
            assertEquals(method, server.takeRequest().method)
        }
    }

    @Test
    fun `PROPFIND 405 at connect means the URL is not a WebDAV endpoint`() {
        server.enqueue(status(405))
        assertKind(Kind.UNSUPPORTED) {
            OkHttpWebDavConnector().connect(WebDavEndpoint(server.url(base).toString()), basic(), CertificatePolicy.System)
        }
    }

    @Test
    fun `per-resource failures in a 207 are surfaced`(): Unit = runBlocking {
        connect().use {
            val failed =
                "<d:response><d:href>/dav/x/locked</d:href><d:status>HTTP/1.1 423 Locked</d:status></d:response>" +
                    "<d:response><d:href>/dav/x/</d:href><d:status>HTTP/1.1 424 Failed Dependency</d:status></d:response>"
            server.enqueue(multistatus(failed))
            assertEquals(423, assertKind(Kind.LOCKED) { it.delete(path("x")) }.httpStatus)
            server.enqueue(multistatus("<d:response><d:href>/dav/y/a</d:href><d:status>HTTP/1.1 403 Forbidden</d:status></d:response>"))
            assertKind(Kind.ACCESS_DENIED) { it.copy(path("x"), path("y")) }
            server.enqueue(multistatus("<d:response><d:href>/dav/x/a</d:href><d:status>HTTP/1.1 204 No Content</d:status></d:response>"))
            it.move(path("x"), path("y"))
        }
    }

    @Test
    fun `malformed or DOCTYPE-carrying multistatus bodies fail with PROTOCOL`(): Unit = runBlocking {
        connect().use {
            server.enqueue(MockResponse.Builder().code(207).body("<d:multistatus xmlns:d=\"DAV:\"><d:response>").build())
            assertKind(Kind.PROTOCOL) { it.stat(path("x")) }
            server.enqueue(MockResponse.Builder().code(207).body("<html/>").build())
            assertKind(Kind.PROTOCOL) { it.stat(path("x")) }
            val xxe =
                """<?xml version="1.0"?><!DOCTYPE m [<!ENTITY x SYSTEM "file:///etc/hostname">]>""" +
                    """<d:multistatus xmlns:d="DAV:"><d:response><d:href>/dav/x&x;</d:href></d:response></d:multistatus>"""
            server.enqueue(MockResponse.Builder().code(207).body(xxe).build())
            assertKind(Kind.PROTOCOL) { it.stat(path("x")) }
            val bomb =
                """<?xml version="1.0"?><!DOCTYPE m [<!ENTITY a "aaaaaaaaaa"><!ENTITY b "&a;&a;&a;&a;&a;&a;&a;&a;">]>""" +
                    """<d:multistatus xmlns:d="DAV:"><d:response><d:href>/dav/&b;</d:href></d:response></d:multistatus>"""
            server.enqueue(MockResponse.Builder().code(207).body(bomb).build())
            assertKind(Kind.PROTOCOL) { it.list(WebDavPath.Root).toList() }
        }
    }

    @Test
    fun `hrefs outside the requested collection are dropped`(): Unit = runBlocking {
        connect().use {
            server.enqueue(
                multistatus(
                    collection("/dav/dir/"),
                    file("/dav/dir/a"),
                    file("http://proxy.internal:8080/dav/dir/b%20c"),
                    file("/dav/elsewhere/c"),
                    file("/dav/dir/sub/deep"),
                    file("/dav/dir/x%2Fy"),
                    file("/other/dir/d"),
                ),
            )
            assertEquals(
                listOf(path("dir", "a"), path("dir", "b c")),
                it.list(path("dir")).toList().map { entry -> entry.path },
            )
        }
    }

    @Test
    fun `only 2xx propstats contribute properties`(): Unit = runBlocking {
        connect().use {
            server.enqueue(
                multistatus(
                    "<d:response><d:href>/dav/f</d:href>" +
                        "<d:propstat><d:prop><d:getcontentlength>5</d:getcontentlength></d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat>" +
                        "<d:propstat><d:prop><d:getetag>bogus</d:getetag></d:prop><d:status>HTTP/1.1 404 Not Found</d:status></d:propstat>" +
                        "</d:response>",
                ),
            )
            val entry = it.stat(path("f"))
            assertEquals(5L, entry.size)
            assertNull(entry.etag)
        }
    }

    @Test
    fun `a 206 must start at the requested offset`(): Unit = runBlocking {
        connect().use {
            server.enqueue(MockResponse.Builder().code(206).addHeader("Content-Range", "bytes 0-9/10").body("0123456789").build())
            assertKind(Kind.PROTOCOL) { it.openRead(path("f"), offset = 5) }
            server.enqueue(MockResponse.Builder().code(206).body("56789").build())
            assertKind(Kind.PROTOCOL) { it.openRead(path("f"), offset = 5) }
            server.enqueue(MockResponse.Builder().code(206).addHeader("Content-Range", "bytes 5-9/*").body("56789").build())
            it.openRead(path("f"), offset = 5).use { download ->
                assertTrue(download.rangeHonored)
                assertNull(download.totalLength)
                assertEquals(5L, download.contentLength)
            }
            assertEquals("bytes=5-", (1..3).map { server.takeRequest() }.last().headers["Range"])
        }
    }

    @Test
    fun `a 416 before the end of the file fails with PROTOCOL`(): Unit = runBlocking {
        connect().use {
            server.enqueue(status(416, "Content-Range" to "bytes */100"))
            assertKind(Kind.PROTOCOL) { it.openRead(path("f"), offset = 5) }
        }
    }

    @Test
    fun `same-origin PROPFIND redirects are followed with credentials`(): Unit = runBlocking {
        connect().use {
            server.enqueue(status(301, "Location" to "/dav/moved/"))
            server.enqueue(multistatus(collection("/dav/moved/")))
            assertTrue(it.stat(path("dir")).isCollection)
            server.takeRequest()
            val followed = server.takeRequest()
            assertEquals("/dav/moved/", followed.url.encodedPath)
            assertEquals("PROPFIND", followed.method)
            assertNotNull(followed.headers["Authorization"])

            server.enqueue(status(308, "Location" to "/dav/moved/"))
            server.enqueue(multistatus(collection("/dav/moved/"), file("/dav/moved/a")))
            assertEquals(listOf(path("dir", "a")), it.list(path("dir")).toList().map { entry -> entry.path })
        }
    }

    @Test
    fun `redirect chains stop after five hops`(): Unit = runBlocking {
        connect().use {
            repeat(6) { server.enqueue(status(302, "Location" to "/dav/loop$it")) }
            assertKind(Kind.PROTOCOL) { it.stat(path("x")) }
            assertEquals(7, server.requestCount)
        }
    }

    @Test
    fun `redirects to another host, port or scheme are refused and never get credentials`(): Unit = runBlocking {
        // Following any of these would reach a fresh 401 and fail with AUTHENTICATION instead.
        listOf(server, other).forEach { (it.dispatcher as QueueDispatcher).setFailFast(status(401)) }
        connect().use {
            val targets =
                listOf(
                    other.url("/dav/x").toString(),
                    server.url("/dav/x").newBuilder().host("127.0.0.1").build().toString(),
                    server.url("/dav/x").newBuilder().scheme("https").build().toString(),
                )
            for (target in targets) {
                server.enqueue(status(301, "Location" to target))
                assertKind(Kind.PROTOCOL) { it.stat(path("x")) }
                server.enqueue(status(302, "Location" to target))
                assertKind(Kind.PROTOCOL) { it.openRead(path("x")) }
            }
            assertEquals(0, other.requestCount)
            assertEquals(1 + targets.size * 2, server.requestCount)
        }
    }

    @Test
    fun `an HTTPS endpoint refuses downgrade redirects`(): Unit = runBlocking {
        (other.dispatcher as QueueDispatcher).setFailFast(status(401))
        val held = TestCertificates.leaf("localhost")
        FakeWebDavServer(certificate = held).start().use { secure ->
            val targets =
                listOf(
                    secure.server.url("/dav/x").newBuilder().scheme("http").build().toString(),
                    other.url("/dav/x").toString(),
                )
            var next = 0
            secure.responseTransformer = { request, response ->
                if (request.url.encodedPath.endsWith("/x")) status(301, "Location" to targets[next++]) else response
            }
            OkHttpWebDavConnector()
                .connect(WebDavEndpoint(secure.baseUrl), basic(), CertificatePolicy.Pinned(ServerCertificate(held.certificate.encoded)))
                .use { session -> repeat(targets.size) { assertKind(Kind.PROTOCOL) { session.stat(path("x")) } } }
        }
        assertEquals(0, other.requestCount)
    }

    @Test
    fun `mutations follow only the slash redirect, once`(): Unit = runBlocking {
        connect().use {
            server.enqueue(status(301, "Location" to server.url("/dav/x/").toString()))
            server.enqueue(status(204))
            it.delete(path("x"))
            server.enqueue(status(301, "Location" to server.url("/dav/elsewhere/").toString()))
            assertKind(Kind.PROTOCOL) { it.delete(path("x")) }
            server.enqueue(status(301, "Location" to server.url("/dav/x/").toString()))
            server.enqueue(status(301, "Location" to server.url("/dav/x//").toString()))
            assertKind(Kind.PROTOCOL) { it.mkdir(path("x")) }
            server.enqueue(status(301, "Location" to server.url("/dav/x/").toString()))
            assertKind(Kind.PROTOCOL) { it.writeAll(path("x"), byteArrayOf(1)) }
            val methods = (1 until server.requestCount).map { server.takeRequest().let { r -> r.method + " " + r.url.encodedPath } }
            assertEquals(
                listOf("DELETE /dav/x", "DELETE /dav/x/", "DELETE /dav/x", "MKCOL /dav/x", "MKCOL /dav/x/", "PUT /dav/x"),
                methods,
            )
        }
    }
}
