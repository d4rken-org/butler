package eu.darken.webdav

import eu.darken.webdav.WebDavException.Kind
import kotlin.uuid.Uuid
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

/** The operation contract the gateway relies on, run against real servers by the subclasses. */
@Tag("webdav-server")
internal abstract class WebDavContractTest {
    protected abstract val endpoint: WebDavEndpoint

    protected abstract fun credentials(): WebDavCredentials.Basic

    /** Whether the server reports RFC 4331 quota for the endpoint. */
    protected abstract val reportsQuota: Boolean

    /** How a MOVE or COPY onto an existing collection fails. */
    protected open val collectionDestinationKind: Kind = Kind.ALREADY_EXISTS

    /** PUT into a missing parent: RFC 4918 says 409, Nextcloud answers 404. */
    protected open val missingParentPutKind: Kind = Kind.CONFLICT

    /** Whether a chunked PUT whose connection drops before the final chunk is stored anyway. */
    protected open val keepsTruncatedChunkedUploads: Boolean = false

    protected suspend fun session(credentials: WebDavCredentials = credentials()): WebDavSession =
        OkHttpWebDavConnector().connect(endpoint, credentials, CertificatePolicy.System)

    private suspend fun WebDavSession.scratch(): WebDavPath = WebDavPath.Root.child("t-${Uuid.random()}").also { mkdir(it) }

    private fun bytes(size: Int) = ByteArray(size) { (it * 31 + 7).toByte() }

    @Test
    fun `connect verifies the endpoint and rejects a wrong password`(): Unit = runBlocking {
        session().use { assertTrue(it.stat(WebDavPath.Root).isCollection) }
        assertKind(Kind.AUTHENTICATION) { session(WebDavCredentials.Basic(credentials().username, "wrong".toCharArray())) }
        assertKind(Kind.AUTHENTICATION) { session(WebDavCredentials.Anonymous) }
    }

    @Test
    fun `files round-trip with fixed-length and chunked uploads`(): Unit = runBlocking {
        session().use {
            val dir = it.scratch()
            val content = bytes(3 * 1024 * 1024 + 17)
            it.writeAll(dir.child("fixed.bin"), content, knownLength = true)
            it.writeAll(dir.child("chunked.bin"), content, knownLength = false)
            it.writeAll(dir.child("empty.bin"), ByteArray(0))
            assertArrayEquals(content, it.readAll(dir.child("fixed.bin")))
            assertArrayEquals(content, it.readAll(dir.child("chunked.bin")))
            assertArrayEquals(ByteArray(0), it.readAll(dir.child("empty.bin")))

            val entry = it.stat(dir.child("fixed.bin"))
            assertFalse(entry.isCollection)
            assertEquals(content.size.toLong(), entry.size)
            assertNotNull(entry.modifiedAt)
            assertNotNull(entry.etag)
            it.openRead(dir.child("fixed.bin")).use { download ->
                assertEquals(entry.etag, download.etag)
                assertEquals(content.size.toLong(), download.contentLength)
            }
            it.delete(dir)
        }
    }

    @Test
    fun `listing maps special names back to segments`(): Unit = runBlocking {
        session().use {
            val dir = it.scratch()
            val names = listOf("a b", "ü ñ.txt", "100%.txt", "x+y=z", "hash#q?mark", "semi;colon", "it's (1)")
            names.forEach { name -> it.writeAll(dir.child(name), name.encodeToByteArray()) }
            it.mkdir(dir.child("sub dir"))
            val entries = it.list(dir).toList()
            assertEquals((names + "sub dir").toSet(), entries.map { entry -> entry.path.name }.toSet())
            entries.forEach { entry -> assertEquals(dir, entry.path.parent) }
            assertTrue(entries.single { entry -> entry.path.name == "sub dir" }.isCollection)
            names.forEach { name -> assertArrayEquals(name.encodeToByteArray(), it.readAll(dir.child(name))) }
            assertTrue(it.list(WebDavPath.Root).toList().any { entry -> entry.path == dir && entry.isCollection })

            assertKind(Kind.NOT_DIRECTORY) { it.list(dir.child("a b")).toList() }
            assertKind(Kind.MISSING) { it.list(dir.child("missing")).toList() }
            assertKind(Kind.MISSING) { it.stat(dir.child("missing")) }
            it.delete(dir)
        }
    }

    @Test
    fun `mkdir reports existing collections and missing parents`(): Unit = runBlocking {
        session().use {
            val dir = it.scratch()
            assertKind(Kind.ALREADY_EXISTS) { it.mkdir(dir) }
            assertKind(Kind.CONFLICT) { it.mkdir(dir.child("missing").child("child")) }
            it.delete(dir)
        }
    }

    @Test
    fun `delete removes files and collections recursively`(): Unit = runBlocking {
        session().use {
            val dir = it.scratch()
            it.mkdir(dir.child("sub"))
            it.writeAll(dir.child("sub").child("file"), bytes(10))
            it.writeAll(dir.child("top"), bytes(10))
            it.delete(dir.child("top"))
            assertKind(Kind.MISSING) { it.stat(dir.child("top")) }
            it.delete(dir.child("sub"))
            assertKind(Kind.MISSING) { it.stat(dir.child("sub")) }
            assertKind(Kind.MISSING) { it.delete(dir.child("sub")) }
            it.delete(dir)
        }
    }

    @Test
    fun `move and copy work on files and collections and never overwrite`(): Unit = runBlocking {
        session().use {
            val dir = it.scratch()
            it.mkdir(dir.child("src"))
            it.writeAll(dir.child("src").child("file"), bytes(100))
            it.writeAll(dir.child("other"), "keep".encodeToByteArray())

            it.copy(dir.child("src"), dir.child("copy"))
            assertArrayEquals(bytes(100), it.readAll(dir.child("copy").child("file")))
            it.move(dir.child("copy"), dir.child("moved"))
            assertKind(Kind.MISSING) { it.stat(dir.child("copy")) }
            assertArrayEquals(bytes(100), it.readAll(dir.child("moved").child("file")))
            it.copy(dir.child("src").child("file"), dir.child("file copy"))
            it.move(dir.child("file copy"), dir.child("file moved"))
            assertArrayEquals(bytes(100), it.readAll(dir.child("file moved")))

            assertKind(Kind.ALREADY_EXISTS) { it.copy(dir.child("src").child("file"), dir.child("other")) }
            assertKind(Kind.ALREADY_EXISTS) { it.move(dir.child("src").child("file"), dir.child("other")) }
            assertKind(collectionDestinationKind) { it.move(dir.child("src"), dir.child("moved")) }
            assertKind(collectionDestinationKind) { it.copy(dir.child("other"), dir.child("moved")) }
            assertArrayEquals(bytes(100), it.readAll(dir.child("moved").child("file")))
            assertArrayEquals("keep".encodeToByteArray(), it.readAll(dir.child("other")))
            assertArrayEquals(bytes(100), it.readAll(dir.child("src").child("file")))
            assertKind(Kind.CONFLICT) { it.copy(dir.child("other"), dir.child("missing").child("x")) }
            assertKind(Kind.MISSING) { it.move(dir.child("missing"), dir.child("x")) }
            it.delete(dir)
        }
    }

    @Test
    fun `ranged reads are positioned and EOF is empty`(): Unit = runBlocking {
        session().use {
            val dir = it.scratch()
            val content = bytes(100_000)
            it.writeAll(dir.child("file"), content)
            it.openRead(dir.child("file"), offset = 40_000).use { download ->
                assertTrue(download.rangeHonored)
                assertEquals(60_000L, download.contentLength)
            }
            assertArrayEquals(content.copyOfRange(40_000, content.size), it.readAll(dir.child("file"), 40_000))
            it.openRead(dir.child("file"), offset = content.size.toLong()).use { download ->
                assertEquals(-1, download.read(ByteArray(10)))
            }
            it.openRead(dir.child("file"), offset = content.size + 1000L).use { download ->
                assertEquals(-1, download.read(ByteArray(10)))
            }
            it.delete(dir)
        }
    }

    @Test
    fun `an expected ETag detects a changed file and accepts an unchanged one`(): Unit = runBlocking {
        session().use {
            val dir = it.scratch()
            val file = dir.child("file")
            it.writeAll(file, bytes(1000))
            // Apache reports a weak ETag right after a write, a strong one a second later.
            val fresh = it.stat(file).etag
            assertNotNull(fresh)
            it.openRead(file, offset = 10, expectedEtag = fresh).use { download -> assertTrue(download.rangeHonored) }
            Thread.sleep(ETAG_GRANULARITY_MS)
            val settled = it.stat(file).etag
            assertNotNull(settled)
            it.openRead(file, offset = 10, expectedEtag = settled).use { download -> assertTrue(download.rangeHonored) }
            it.openRead(file, offset = 10, expectedEtag = fresh).close()

            // Both servers derive ETags from whole-second modification times.
            Thread.sleep(ETAG_GRANULARITY_MS)
            it.writeAll(file, bytes(2000))
            assertKind(Kind.CHANGED) { it.openRead(file, offset = 10, expectedEtag = settled) }
            assertKind(Kind.CHANGED) { it.openRead(file, offset = 10, expectedEtag = fresh) }
            it.delete(dir)
        }
    }

    @Test
    fun `createOnly claims a path exactly once`(): Unit = runBlocking {
        session().use {
            val dir = it.scratch()
            it.writeAll(dir.child("claimed"), ByteArray(0), createOnly = true)
            it.writeAll(dir.child("taken"), "theirs".encodeToByteArray())
            assertKind(Kind.ALREADY_EXISTS) { it.writeAll(dir.child("taken"), ByteArray(0), createOnly = true) }
            assertKind(Kind.ALREADY_EXISTS) { it.writeAll(dir.child("taken"), "mine".encodeToByteArray(), createOnly = true) }
            assertArrayEquals("theirs".encodeToByteArray(), it.readAll(dir.child("taken")))
            assertKind(Kind.ALREADY_EXISTS) { it.writeAll(dir.child("claimed"), ByteArray(0), createOnly = true) }
            it.delete(dir)
        }
    }

    @Test
    fun `a large createOnly body on an existing file leaves it untouched`(): Unit = runBlocking {
        session().use {
            val dir = it.scratch()
            it.writeAll(dir.child("taken"), "theirs".encodeToByteArray())
            val error = assertThrowsSuspending<WebDavException> {
                it.writeAll(dir.child("taken"), bytes(8 * 1024 * 1024), createOnly = true)
            }
            assertTrue(error.kind == Kind.ALREADY_EXISTS || error.kind == Kind.TRANSPORT, error.message)
            assertArrayEquals("theirs".encodeToByteArray(), it.readAll(dir.child("taken")))
            it.delete(dir)
        }
    }

    @Test
    fun `failed and aborted fixed-length uploads leave no file`(): Unit = runBlocking {
        session().use {
            val dir = it.scratch()
            val aborted = it.openWrite(dir.child("aborted"), contentLength = 1_000_000)
            aborted.write(bytes(100_000))
            aborted.abort()
            val short = it.openWrite(dir.child("short"), contentLength = 1000)
            short.write(bytes(10))
            assertKind(Kind.OTHER) { short.close() }
            val orphan = it.openWrite(dir.child("missing").child("file"), contentLength = 3)
            orphan.write(bytes(3))
            assertKind(missingParentPutKind) { orphan.close() }
            Thread.sleep(1000)
            assertEquals(emptyList<WebDavEntry>(), it.list(dir).toList())
            it.delete(dir)
        }
    }

    @Test
    fun `an aborted chunked upload is discarded only by servers that require the final chunk`(): Unit = runBlocking {
        session().use {
            val dir = it.scratch()
            val aborted = it.openWrite(dir.child("aborted"))
            aborted.write(bytes(100_000))
            aborted.abort()
            if (keepsTruncatedChunkedUploads) {
                val deadline = System.currentTimeMillis() + 10_000
                var entry: WebDavEntry? = null
                while (entry == null && System.currentTimeMillis() < deadline) {
                    entry = it.list(dir).toList().singleOrNull()
                    if (entry == null) Thread.sleep(200)
                }
                assertNotNull(entry, "Expected the server to keep the truncated body")
                assertTrue(entry!!.size!! < 100_000)
            } else {
                Thread.sleep(1000)
                assertKind(Kind.MISSING) { it.stat(dir.child("aborted")) }
            }
            it.delete(dir)
        }
    }

    @Test
    fun `capacity matches the server's quota support`(): Unit = runBlocking {
        session().use {
            val capacity = it.capacity(WebDavPath.Root)
            if (!reportsQuota) {
                assertNull(capacity)
                return@runBlocking
            }
            assertNotNull(capacity)
            assertTrue(capacity!!.availableBytes > 0)
            val used = capacity.usedBytes
            assertNotNull(used)
            assertEquals(used!! + capacity.availableBytes, capacity.totalBytes)
        }
    }
}

private const val ETAG_GRANULARITY_MS = 1100L

internal class NextcloudContractTest : WebDavContractTest() {
    override val endpoint: WebDavEndpoint
        get() = NextcloudServer.shared.endpoint()

    override fun credentials() = WebDavCredentials.Basic(NextcloudServer.USER, NextcloudServer.PASSWORD.toCharArray())

    override val reportsQuota = true

    override val missingParentPutKind = Kind.MISSING

    override val keepsTruncatedChunkedUploads = true
}

internal class ApacheContractTest : WebDavContractTest() {
    override val endpoint: WebDavEndpoint
        get() = ApacheDavServer.shared.endpoint()

    override fun credentials() = WebDavCredentials.Basic(ApacheDavServer.USER, ApacheDavServer.PASSWORD.toCharArray())

    override val reportsQuota = false

    // mod_dav resolves the Destination through a subrequest, which mod_dir answers with a slash redirect
    // that carries no Location: "301 Destination URI had an error".
    override val collectionDestinationKind = Kind.PROTOCOL

    @Test
    fun `anonymous access works where the server allows it`(): Unit = runBlocking {
        OkHttpWebDavConnector()
            .connect(ApacheDavServer.shared.endpoint("public"), WebDavCredentials.Anonymous, CertificatePolicy.System)
            .use {
                val dir = WebDavPath.Root.child("t-${Uuid.random()}")
                it.mkdir(dir)
                it.writeAll(dir.child("file"), "anon".encodeToByteArray())
                assertArrayEquals("anon".encodeToByteArray(), it.readAll(dir.child("file")))
                it.delete(dir)
            }
    }
}
