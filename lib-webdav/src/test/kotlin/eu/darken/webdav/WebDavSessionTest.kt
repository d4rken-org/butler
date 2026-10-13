package eu.darken.webdav

import eu.darken.webdav.WebDavException.Kind
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlin.time.TimeSource
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import mockwebserver3.SocketEffect
import okhttp3.Call
import okhttp3.EventListener
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource

internal fun basic(user: String = "butler", password: String = "butlerpass") =
    WebDavCredentials.Basic(user, password.toCharArray())

internal inline fun <reified T : Throwable> assertThrowsSuspending(noinline block: suspend () -> Unit): T =
    assertThrows(T::class.java) { runBlocking { block() } }

internal fun assertKind(kind: Kind, block: suspend () -> Unit): WebDavException =
    assertThrowsSuspending<WebDavException>(block).also { assertEquals(kind, it.kind, it.message) }

internal suspend fun WebDavSession.readAll(path: WebDavPath, offset: Long = 0): ByteArray =
    openRead(path, offset).use { download ->
        val out = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val count = download.read(buffer)
            if (count < 0) break
            out.write(buffer, 0, count)
        }
        out.toByteArray()
    }

internal suspend fun WebDavSession.writeAll(path: WebDavPath, content: ByteArray, knownLength: Boolean = true, createOnly: Boolean = false) {
    openWrite(path, if (knownLength) content.size.toLong() else null, createOnly).use { upload ->
        content.asList().chunked(1000).forEach { upload.write(it.toByteArray()) }
    }
}

internal fun path(vararg segments: String) = WebDavPath(segments.toList())

class WebDavSessionTest {
    private val server = FakeWebDavServer().start()

    @AfterEach
    fun tearDown() = server.close()

    private suspend fun session(credentials: WebDavCredentials = basic()): WebDavSession =
        OkHttpWebDavConnector().connect(WebDavEndpoint(server.baseUrl), credentials, CertificatePolicy.System)

    @Test
    fun `connect sends Basic credentials preemptively and verifies the collection`(): Unit = runBlocking {
        session().use { assertTrue(it.stat(WebDavPath.Root).isCollection) }
        val connect = server.takeRequest()
        assertEquals("PROPFIND", connect.method)
        assertEquals("0", connect.headers["Depth"])
        assertEquals(okhttp3.Credentials.basic("butler", "butlerpass"), connect.headers["Authorization"])
        assertEquals(2, server.requestCount)
    }

    @Test
    fun `wrong password fails with AUTHENTICATION at connect`() {
        assertKind(Kind.AUTHENTICATION) { session(basic(password = "wrong")) }
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `anonymous sessions send no Authorization header`(): Unit = runBlocking {
        FakeWebDavServer(username = null, password = null).start().use { anonymous ->
            OkHttpWebDavConnector()
                .connect(WebDavEndpoint(anonymous.baseUrl), WebDavCredentials.Anonymous, CertificatePolicy.System)
                .use { it.stat(WebDavPath.Root) }
            assertNull(anonymous.takeRequest().headers["Authorization"])
        }
    }

    @Test
    fun `connect to a file fails with NOT_DIRECTORY`() {
        server.putFile("file.txt", byteArrayOf(1))
        assertKind(Kind.NOT_DIRECTORY) {
            OkHttpWebDavConnector().connect(WebDavEndpoint(server.baseUrl + "file.txt"), basic(), CertificatePolicy.System)
        }
    }

    @Test
    fun `stat reports file metadata`(): Unit = runBlocking {
        val modified = java.time.Instant.parse("2024-05-06T07:08:09Z")
        val etag = server.putFile("dir/a b.txt", "hello".encodeToByteArray(), modified)
        session().use {
            val entry = it.stat(path("dir", "a b.txt"))
            assertEquals(path("dir", "a b.txt"), entry.path)
            assertFalse(entry.isCollection)
            assertEquals(5L, entry.size)
            assertEquals(Instant.parse("2024-05-06T07:08:09Z"), entry.modifiedAt)
            assertEquals(etag, entry.etag)
            assertEquals("application/octet-stream", entry.contentType)
            val dir = it.stat(path("dir"))
            assertTrue(dir.isCollection)
            assertNull(dir.size)
            assertKind(Kind.MISSING) { it.stat(path("nope")) }
        }
    }

    @ParameterizedTest
    @EnumSource(FakeWebDavServer.HrefStyle::class)
    fun `list maps every href style back to segments and excludes the collection itself`(style: FakeWebDavServer.HrefStyle): Unit =
        runBlocking {
            server.hrefStyle = style
            val names = listOf("a b", "ü.txt", "100%.txt", "x+y=z", "sub")
            names.dropLast(1).forEach { server.putFile("dir/$it", byteArrayOf(1)) }
            server.mkdirs("dir/sub/deeper")
            session().use {
                val entries = it.list(path("dir")).toList()
                assertEquals(names.toSet(), entries.map { entry -> entry.path.name }.toSet())
                assertTrue(entries.single { entry -> entry.path.name == "sub" }.isCollection)
                entries.forEach { entry -> assertEquals(path("dir"), entry.path.parent) }
                assertEquals(listOf("dir"), it.list(WebDavPath.Root).toList().map { entry -> entry.path.name })
            }
        }

    @Test
    fun `list of a Nextcloud-style user collection with an encoded folder name`(): Unit = runBlocking {
        FakeWebDavServer(basePath = "/remote.php/dav/files/u/").start().use { nextcloud ->
            nextcloud.putFile("a b/c d.txt", byteArrayOf(1))
            OkHttpWebDavConnector().connect(WebDavEndpoint(nextcloud.baseUrl), basic(), CertificatePolicy.System).use {
                assertEquals(listOf(path("a b", "c d.txt")), it.list(path("a b")).toList().map { entry -> entry.path })
                assertTrue(nextcloud.takeRequest().url.encodedPath.startsWith("/remote.php/dav/files/u/"))
            }
        }
    }

    @Test
    fun `list fails for files and missing paths`(): Unit = runBlocking {
        server.putFile("file", byteArrayOf(1))
        session().use {
            assertKind(Kind.NOT_DIRECTORY) { it.list(path("file")).toList() }
            assertKind(Kind.MISSING) { it.list(path("missing")).toList() }
        }
    }

    @Test
    fun `mkdir creates collections and reports existing paths and missing parents`(): Unit = runBlocking {
        session().use {
            it.mkdir(path("new"))
            assertTrue(server.isCollection("new"))
            assertKind(Kind.ALREADY_EXISTS) { it.mkdir(path("new")) }
            assertKind(Kind.CONFLICT) { it.mkdir(path("missing", "child")) }
        }
    }

    @Test
    fun `delete removes files and whole collections`(): Unit = runBlocking {
        server.putFile("dir/sub/file", byteArrayOf(1))
        server.putFile("top", byteArrayOf(1))
        session().use {
            it.delete(path("top"))
            it.delete(path("dir"))
            assertFalse(server.exists("top"))
            assertFalse(server.exists("dir/sub/file"))
            assertKind(Kind.MISSING) { it.delete(path("dir")) }
        }
    }

    @Test
    fun `move and copy never replace an existing destination`(): Unit = runBlocking {
        server.putFile("dir/sub/file", "x".encodeToByteArray())
        server.putFile("other", "keep".encodeToByteArray())
        session().use {
            it.copy(path("dir"), path("copy"))
            assertArrayEquals("x".encodeToByteArray(), server.content("copy/sub/file"))
            assertTrue(server.exists("dir/sub/file"))
            it.move(path("copy"), path("moved"))
            assertFalse(server.exists("copy"))
            assertTrue(server.exists("moved/sub/file"))

            assertKind(Kind.ALREADY_EXISTS) { it.move(path("dir", "sub", "file"), path("other")) }
            assertKind(Kind.ALREADY_EXISTS) { it.copy(path("dir", "sub", "file"), path("other")) }
            assertArrayEquals("keep".encodeToByteArray(), server.content("other"))
            assertKind(Kind.CONFLICT) { it.copy(path("other"), path("missing", "other")) }
            assertKind(Kind.MISSING) { it.move(path("missing"), path("x")) }
        }
        val requests = (1..server.requestCount).map { server.takeRequest() }
        val copy = requests.first { it.method == "COPY" }
        assertEquals("F", copy.headers["Overwrite"])
        assertEquals("infinity", copy.headers["Depth"])
        assertEquals(server.baseUrl + "copy", copy.headers["Destination"])
        assertEquals("F", requests.first { it.method == "MOVE" }.headers["Overwrite"])
    }

    @Test
    fun `Apache-style slash redirects are re-sent for collection mutations and followed for PROPFIND`(): Unit = runBlocking {
        server.redirectSlashlessCollections = true
        server.putFile("dir/file", byteArrayOf(1))
        session().use {
            assertTrue(it.stat(path("dir")).isCollection)
            assertEquals(listOf(path("dir", "file")), it.list(path("dir")).toList().map { entry -> entry.path })
            assertKind(Kind.ALREADY_EXISTS) { it.mkdir(path("dir")) }
            it.copy(path("dir"), path("copy"))
            it.move(path("copy"), path("moved"))
            it.delete(path("moved"))
            assertTrue(server.exists("dir/file"))
            assertFalse(server.exists("copy"))
            assertFalse(server.exists("moved"))
        }
    }

    @Test
    fun `openRead reads whole files and honored ranges`(): Unit = runBlocking {
        val content = ByteArray(100_000) { it.toByte() }
        val etag = server.putFile("file", content)
        session().use {
            assertArrayEquals(content, it.readAll(path("file")))
            it.openRead(path("file"), offset = 1000).use { download ->
                assertTrue(download.rangeHonored)
                assertEquals(99_000L, download.contentLength)
                assertEquals(100_000L, download.totalLength)
                assertEquals(etag, download.etag)
            }
            assertArrayEquals(content.copyOfRange(1000, content.size), it.readAll(path("file"), 1000))
        }
        server.takeRequest()
        val get = server.takeRequest()
        assertEquals("identity", get.headers["Accept-Encoding"])
        assertNull(get.headers["Range"])
    }

    @Test
    fun `openRead reports an ignored range instead of treating the body as positioned`(): Unit = runBlocking {
        server.putFile("file", "0123456789".encodeToByteArray())
        server.honorRange = false
        session().use {
            it.openRead(path("file"), offset = 4).use { download ->
                assertFalse(download.rangeHonored)
                assertEquals(10L, download.contentLength)
                assertEquals('0'.code, download.blocking().read())
            }
        }
    }

    @Test
    fun `openRead at or beyond EOF is empty with or without Content-Range`(): Unit = runBlocking {
        server.putFile("file", "0123456789".encodeToByteArray())
        session().use {
            it.openRead(path("file"), offset = 10).use { download ->
                assertEquals(-1, download.read(ByteArray(4)))
                assertEquals(0L, download.contentLength)
                assertEquals(10L, download.totalLength)
            }
            server.contentRangeOn416 = false
            it.openRead(path("file"), offset = 20).use { download ->
                assertEquals(-1, download.read(ByteArray(4)))
                assertNull(download.totalLength)
            }
        }
    }

    @Test
    fun `openRead with an outdated ETag fails with CHANGED`(): Unit = runBlocking {
        val etag = server.putFile("file", "old".encodeToByteArray())
        session().use {
            it.openRead(path("file"), expectedEtag = etag).use { download -> assertEquals(etag, download.etag) }
            server.putFile("file", "new".encodeToByteArray())
            assertKind(Kind.CHANGED) { it.openRead(path("file"), offset = 1, expectedEtag = etag) }
            assertKind(Kind.MISSING) { it.openRead(path("missing")) }
        }
    }

    @Test
    fun `a weak expected ETag is compared locally because If-Match would never match it`(): Unit = runBlocking {
        server.weakEtags = true
        server.putFile("file", "old".encodeToByteArray())
        session().use {
            val etag = it.stat(path("file")).etag
            assertTrue(etag!!.startsWith("W/"))
            it.openRead(path("file"), offset = 1, expectedEtag = etag).use { download -> assertEquals(etag, download.etag) }
            server.weakEtags = false
            it.openRead(path("file"), offset = 1, expectedEtag = etag).close()
            server.putFile("file", "new".encodeToByteArray())
            assertKind(Kind.CHANGED) { it.openRead(path("file"), offset = 1, expectedEtag = etag) }
        }
        val gets = (1..server.requestCount).map { server.takeRequest() }.filter { it.method == "GET" }
        assertEquals(3, gets.size)
        gets.forEach { assertNull(it.headers["If-Match"]) }
    }

    @Test
    fun `uploads send fixed-length or chunked bodies`(): Unit = runBlocking {
        val content = ByteArray(300_000) { (it * 7).toByte() }
        session().use {
            it.writeAll(path("fixed"), content, knownLength = true)
            it.writeAll(path("chunked"), content, knownLength = false)
            it.writeAll(path("empty"), ByteArray(0), knownLength = true)
        }
        assertArrayEquals(content, server.content("fixed"))
        assertArrayEquals(content, server.content("chunked"))
        assertArrayEquals(ByteArray(0), server.content("empty"))
        val puts = (1..server.requestCount).map { server.takeRequest() }.filter { it.method == "PUT" }
        assertEquals("300000", puts[0].headers["Content-Length"])
        assertNull(puts[1].headers["Content-Length"])
        assertNotNull(puts[1].chunkSizes)
        assertEquals("0", puts[2].headers["Content-Length"])
    }

    @Test
    fun `a fixed-length upload writing a different byte count fails without creating the file`(): Unit = runBlocking {
        session().use {
            it.openWrite(path("long"), contentLength = 4).use { upload ->
                upload.write(byteArrayOf(1, 2, 3))
                assertKind(Kind.OTHER) { upload.write(byteArrayOf(4, 5)) }
            }
            val short = it.openWrite(path("short"), contentLength = 4)
            short.write(byteArrayOf(1, 2, 3))
            assertKind(Kind.OTHER) { short.close() }
        }
        assertFalse(server.exists("long"))
        assertFalse(server.exists("short"))
    }

    @Test
    fun `createOnly never replaces another writer's file`(): Unit = runBlocking {
        server.putFile("taken", "theirs".encodeToByteArray())
        session().use {
            assertKind(Kind.ALREADY_EXISTS) { it.writeAll(path("taken"), "mine".encodeToByteArray(), createOnly = true) }
            assertKind(Kind.ALREADY_EXISTS) { it.writeAll(path("taken"), ByteArray(0), createOnly = true) }
            it.writeAll(path("free"), ByteArray(0), createOnly = true)
        }
        assertArrayEquals("theirs".encodeToByteArray(), server.content("taken"))
        assertTrue(server.exists("free"))
        val put = (1..server.requestCount).map { server.takeRequest() }.first { it.method == "PUT" }
        assertEquals("*", put.headers["If-None-Match"])
    }

    @Test
    fun `upload failures surface on close`(): Unit = runBlocking {
        session().use {
            val upload = it.openWrite(path("missing", "file"), contentLength = 3)
            upload.write(byteArrayOf(1, 2, 3))
            assertKind(Kind.CONFLICT) { upload.close() }
        }
    }

    @Test
    fun `a server requiring Content-Length refuses chunked uploads`(): Unit = runBlocking {
        server.rejectUnknownLengthPut = true
        session().use {
            val error = assertKind(Kind.OTHER) { it.writeAll(path("chunked"), byteArrayOf(1), knownLength = false) }
            assertEquals(411, error.httpStatus)
            it.writeAll(path("fixed"), byteArrayOf(1), knownLength = true)
        }
        assertFalse(server.exists("chunked"))
        assertTrue(server.exists("fixed"))
    }

    @Test
    fun `abort and a failing producer never commit a partial body`(): Unit = runBlocking {
        session().use {
            val upload = it.openWrite(path("aborted"))
            upload.write(ByteArray(10_000))
            upload.abort()
            upload.close()

            assertThrowsSuspending<IllegalStateException> {
                it.openWrite(path("failed")).use { failing ->
                    failing.write(ByteArray(10_000))
                    error("producer failed")
                }
            }
            it.writeAll(path("after"), byteArrayOf(1))
        }
        assertFalse(server.exists("aborted"))
        assertFalse(server.exists("failed"))
        assertTrue(server.exists("after"))
    }

    @Test
    fun `blocking views read and commit`(): Unit = runBlocking {
        session().use {
            val upload = it.openWrite(path("file"), contentLength = 5)
            upload.blocking().use { stream -> stream.write("hello".encodeToByteArray()) }
            assertArrayEquals("hello".encodeToByteArray(), server.content("file"))
            it.openRead(path("file")).use { download ->
                assertArrayEquals("hello".encodeToByteArray(), download.blocking().readBytes())
            }
        }
    }

    @Test
    fun `capacity follows RFC 4331 and ignores negative sentinels`(): Unit = runBlocking {
        session().use {
            assertNull(it.capacity(WebDavPath.Root))
            server.quotaAvailableBytes = 1000
            server.quotaUsedBytes = 24
            assertEquals(WebDavCapacity(1000, 24), it.capacity(WebDavPath.Root))
            assertEquals(1024L, it.capacity(WebDavPath.Root)?.totalBytes)
            server.quotaAvailableBytes = -3
            assertNull(it.capacity(WebDavPath.Root))
            server.quotaAvailableBytes = 1000
            server.quotaUsedBytes = -1
            val unknownUsed = it.capacity(WebDavPath.Root)
            assertEquals(WebDavCapacity(1000, null), unknownUsed)
            assertNull(unknownUsed?.totalBytes)
        }
    }

    @Test
    fun `a DELETE whose response is lost is not retried`(): Unit = runBlocking {
        server.putFile("file", byteArrayOf(1))
        server.responseTransformer = { request, response ->
            if (request.method == "DELETE") response.newBuilder().onResponseStart(SocketEffect.CloseSocket()).build() else response
        }
        session().use {
            it.stat(path("file"))
            assertKind(Kind.TRANSPORT) { it.delete(path("file")) }
        }
        assertFalse(server.exists("file"))
        assertEquals(1, (1..server.requestCount).map { server.takeRequest() }.count { it.method == "DELETE" })
    }

    @Test
    fun `cancellation releases a call blocked on the server`(): Unit = runBlocking {
        session().use {
            server.responseTransformer = { _, response -> response.newBuilder().headersDelay(10, TimeUnit.SECONDS).build() }
            val mark = TimeSource.Monotonic.markNow()
            assertThrows(TimeoutCancellationException::class.java) {
                runBlocking { withTimeout(300.milliseconds) { it.stat(WebDavPath.Root) } }
            }
            assertTrue(mark.elapsedNow() < 5.seconds)
        }
    }

    @Test
    fun `cancellation cancels the HTTP call while the session stays open`(): Unit = runBlocking {
        val reached = CountDownLatch(1)
        val canceled = CountDownLatch(1)
        var canceledCall: Call? = null
        var reachedBeforeCancel = false
        val listener =
            object : EventListener() {
                override fun canceled(call: Call) {
                    reachedBeforeCancel = reached.count == 0L
                    canceledCall = call
                    canceled.countDown()
                }
            }
        val factory =
            object : EventListener.Factory {
                override fun create(call: Call): EventListener = listener
            }
        OkHttpWebDavConnector(WebDavConfig(), eventListenerFactory = factory) { platformTrustManager() }
            .connect(WebDavEndpoint(server.baseUrl), basic(), CertificatePolicy.System)
            .use {
                server.responseTransformer = { _, response ->
                    reached.countDown()
                    response.newBuilder().headersDelay(10, TimeUnit.SECONDS).build()
                }
                assertThrows(TimeoutCancellationException::class.java) {
                    runBlocking { withTimeout(1.seconds) { it.stat(WebDavPath.Root) } }
                }
                assertTrue(canceled.await(2, TimeUnit.SECONDS), "The blocked call was not cancelled")
                assertTrue(reachedBeforeCancel, "The request had not reached the server when it was cancelled")
                assertEquals("PROPFIND", canceledCall?.request()?.method)
            }
    }
}
