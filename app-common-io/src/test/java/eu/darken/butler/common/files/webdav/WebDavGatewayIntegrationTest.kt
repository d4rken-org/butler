package eu.darken.butler.common.files.webdav

import eu.darken.butler.common.adb.AdbManager
import eu.darken.butler.common.files.APath
import eu.darken.butler.common.files.APathGateway
import eu.darken.butler.common.files.Existence
import eu.darken.butler.common.files.FileSystemOps
import eu.darken.butler.common.files.GatewaySwitch
import eu.darken.butler.common.files.LocalPath
import eu.darken.butler.common.files.LookupOptions
import eu.darken.butler.common.files.MoveOutcome
import eu.darken.butler.common.files.WebDavPath
import eu.darken.butler.common.files.actions.CopyAction
import eu.darken.butler.common.files.actions.DeleteAction
import eu.darken.butler.common.files.actions.MoveAction
import eu.darken.butler.common.files.actions.PathActionIssue
import eu.darken.butler.common.files.errors.PathAlreadyExistsException
import eu.darken.butler.common.files.errors.PathPermissionDeniedException
import eu.darken.butler.common.files.errors.ReadException
import eu.darken.butler.common.files.errors.WriteException
import eu.darken.butler.common.files.local.LocalFileSystemOps
import eu.darken.butler.common.files.local.LocalGateway
import eu.darken.butler.common.files.local.LocalPathLookup
import eu.darken.butler.common.files.local.accessibility.LocalPathAccessChecker
import eu.darken.butler.common.files.local.routing.AccessMode
import eu.darken.butler.common.files.local.routing.LocalPathRoutingPolicy
import eu.darken.butler.common.files.local.routing.ModeSession
import eu.darken.butler.common.files.local.routing.ModeSessionFactory
import eu.darken.butler.common.files.local.routing.RouteDecision
import eu.darken.butler.common.files.metadata.FileSystem
import eu.darken.butler.common.files.metadata.FileType
import eu.darken.butler.common.files.operations.TransferStrategy
import eu.darken.butler.common.files.smb.FakeUpgradeRepo
import eu.darken.butler.common.files.webdav.credentials.WebDavCredential
import eu.darken.butler.common.files.webdav.credentials.WebDavCredentialStore
import eu.darken.butler.common.files.webdav.location.TrustedCertificate
import eu.darken.butler.common.files.webdav.location.WebDavLocation
import eu.darken.butler.common.files.write.AtomicFileWriter
import eu.darken.butler.common.root.RootManager
import eu.darken.butler.common.storage.StorageManager2
import eu.darken.webdav.FakeWebDavServer
import eu.darken.webdav.ServerCertificate
import eu.darken.webdav.TestCertificates
import eu.darken.webdav.WebDavEndpoint
import eu.darken.webdav.WebDavException
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.longs.shouldBeInRange
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestScope
import mockwebserver3.MockResponse
import mockwebserver3.RecordedRequest
import mockwebserver3.SocketEffect
import okio.buffer
import okio.sink
import okio.source
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir
import testhelpers.BaseTest
import testhelpers.coroutine.TestDispatcherProvider
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.SequenceInputStream
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import kotlin.random.Random
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.Uuid

/**
 * Drives [GatewaySwitch] against [FakeWebDavServer], the in-memory WebDAV server lib-webdav's own
 * tests run against.
 */
@Timeout(120)
class WebDavGatewayIntegrationTest : BaseTest() {

    private val idA = Uuid.parse("aaaaaaaa-0000-0000-0000-000000000001")
    private val idB = Uuid.parse("bbbbbbbb-0000-0000-0000-000000000002")

    private val dispatchers = TestDispatcherProvider(Dispatchers.IO)

    @TempDir
    lateinit var spoolDir: File

    private val servers = mutableListOf<FakeWebDavServer>()
    private val rigs = mutableListOf<Rig>()

    @AfterEach
    fun tearDown(): Unit = runBlocking {
        rigs.forEach { it.pool.close() }
        servers.forEach { it.close() }
    }

    private class Rig(
        val switch: GatewaySwitch,
        val pool: WebDavClientPool,
        val ops: WebDavFileSystemOps,
    )

    /** Records every request it answered; [inject] may replace the response. */
    private class Recorder(server: FakeWebDavServer) {
        val requests = CopyOnWriteArrayList<RecordedRequest>()

        @Volatile
        var inject: ((RecordedRequest, MockResponse) -> MockResponse)? = null

        init {
            server.responseTransformer = { request, response ->
                requests.add(request)
                inject?.invoke(request, response) ?: response
            }
        }

        fun of(method: String, name: String) = requests.filter {
            it.method == method && it.url.pathSegments.filter(String::isNotEmpty).lastOrNull() == name
        }
    }

    private fun server(certificate: okhttp3.tls.HeldCertificate? = null, configure: FakeWebDavServer.() -> Unit = {}) =
        FakeWebDavServer(certificate = certificate).apply(configure).start().also { servers.add(it) }

    private fun location(
        id: Uuid,
        server: FakeWebDavServer,
        url: String = server.baseUrl,
        pin: TrustedCertificate? = null,
    ) = testWebDavLocation(
        id = id,
        url = WebDavEndpoint(url).url,
        username = "butler",
        pinnedCertificate = pin,
    )

    /** A spool that cannot hand out a single file: proves that a transfer streamed instead. */
    private fun noSpool() = WebDavSpool { File(spoolDir, "not-a-directory").apply { writeText("x") } }

    private fun rig(
        vararg locations: WebDavLocation,
        password: String = "butlerpass",
        pro: Boolean = true,
        spool: WebDavSpool = WebDavSpool { spoolDir },
    ): Rig {
        val credentialStore = mockk<WebDavCredentialStore>(relaxed = true) {
            every { evictions } returns MutableSharedFlow()
            coEvery { resolve(any()) } answers {
                WebDavCredential.Password(firstArg<WebDavLocation>().username!!, password.toCharArray())
            }
        }
        val pool = WebDavClientPool(
            appScope = TestScope(),
            locationManager = FakeWebDavLocationManager(locations.toList()),
            credentialStore = credentialStore,
            connectorFactory = WebDavConnectorFactoryModule.connectorFactory(),
            upgradeRepo = FakeUpgradeRepo(pro = pro),
        )
        val ops = WebDavFileSystemOps(pool, dispatchers, spool)
        val switch = GatewaySwitch(
            appScope = TestScope(),
            dispatcherProvider = dispatchers,
            safGateway = mockk(relaxed = true),
            localGateway = localGateway(),
            archiveGateway = mockk(relaxed = true),
            smbGateway = mockk(relaxed = true),
            sftpGateway = mockk(relaxed = true),
            webdavGateway = WebDavGateway(TestScope(), dispatchers, ops, pool),
            safLocationManager = mockk(relaxed = true),
            proxyPfdFactory = mockk(relaxed = true),
        )
        return Rig(switch, pool, ops).also { rigs.add(it) }
    }

    /** Direct access only, with no root, ADB or isolated service behind it. */
    private fun localGateway(): LocalGateway {
        val ops = LocalFileSystemOps(mockk(relaxed = true))
        return LocalGateway(
            appScope = TestScope(),
            dispatcherProvider = dispatchers,
            fileSystemOps = ops,
            rootManager = mockk<RootManager>(relaxed = true),
            adbManager = mockk<AdbManager>(relaxed = true),
            accessChecker = mockk<LocalPathAccessChecker>(relaxed = true) {
                every { shouldTryNormalAccess(any(), any()) } returns true
            },
            isolatedServiceClient = mockk(relaxed = true),
            storageManager = mockk<StorageManager2>(relaxed = true),
            routingPolicy = mockk<LocalPathRoutingPolicy>(relaxed = true) {
                coEvery { classify(any(), any(), any()) } returns RouteDecision.Allowed(AccessMode.DIRECT)
                every { proactiveChildren(any()) } returns emptySet()
            },
            modeSessionFactory = mockk<ModeSessionFactory>(relaxed = true) {
                coEvery { open(AccessMode.DIRECT) } answers { ModeSession(AccessMode.DIRECT, ops, null, null) }
            },
        )
    }

    private fun a(vararg segments: String) = WebDavPath(idA, segments.toList())
    private fun b(vararg segments: String) = WebDavPath(idB, segments.toList())

    private suspend fun GatewaySwitch.write(path: APath<*>, text: String) =
        openOutputStream(path).sink().buffer().use { it.writeUtf8(text) }

    private suspend fun GatewaySwitch.read(path: APath<*>): String =
        openInputStream(path).source().buffer().use { it.readUtf8() }

    private fun FakeWebDavServer.text(path: String): String? = content(path)?.decodeToString()

    private fun assertNoSpoolLeft() {
        spoolDir.listFiles().orEmpty().toList() shouldBe emptyList()
    }

    // region browsing and reading

    @Test
    fun `lookup, list, read and quota`(): Unit = runBlocking {
        val server = server {
            putFile("docs/a b.txt", "hello webdav".encodeToByteArray())
            mkdirs("docs/sub")
        }
        val switch = rig(location(idA, server)).switch

        val file = switch.lookup(a("docs", "a b.txt"), LookupOptions()).shouldBeInstanceOf<WebDavPathLookup>()
        file.fileType shouldBe FileType.FILE
        file.size shouldBe 12L
        file.etag shouldBe server.etag("docs/a b.txt")
        file.contentType shouldBe "application/octet-stream"
        file.modifiedAt.shouldNotBeNull()

        switch.lookup(a(), LookupOptions()).fileType shouldBe FileType.DIRECTORY

        val listing = switch.lookupFiles(a("docs"), LookupOptions())
        listing.associate { it.name to it.fileType } shouldBe mapOf(
            "a b.txt" to FileType.FILE,
            "sub" to FileType.DIRECTORY,
        )
        switch.listFiles(a("docs")) shouldContainExactlyInAnyOrder listOf(a("docs", "a b.txt"), a("docs", "sub"))

        switch.read(a("docs", "a b.txt")) shouldBe "hello webdav"

        switch.exists(a("docs", "missing")) shouldBe false
        switch.existsStrict(a("docs", "missing")) shouldBe Existence.ABSENT
        switch.existsStrict(a("docs", "a b.txt")) shouldBe Existence.PRESENT
        shouldThrow<ReadException> { switch.lookup(a("docs", "missing"), LookupOptions()) }
            .let { WebDavStatusMapper.isMissing(it) shouldBe true }
        switch.lookup(a("docs", "missing"), LookupOptions(fallbackToUnknown = true)).fileType shouldBe FileType.UNKNOWN

        switch.getFileSystem(a()) shouldBe FileSystem()
        server.quotaAvailableBytes = 1000
        server.quotaUsedBytes = 24
        switch.getFileSystem(a()) shouldBe FileSystem(freeSpace = 1000, totalSpace = 1024)
        server.quotaAvailableBytes = -3
        switch.getFileSystem(a()) shouldBe FileSystem()
    }

    @Test
    fun `walk and du list one collection at a time`(): Unit = runBlocking {
        val server = server {
            putFile("tree/a.txt", ByteArray(10))
            putFile("tree/one/b.txt", ByteArray(20))
            putFile("tree/one/two/c.txt", ByteArray(30))
        }
        val switch = rig(location(idA, server)).switch

        switch.walk(a("tree"), LookupOptions(), APathGateway.WalkOptions()).toList().map { it.name } shouldContainExactlyInAnyOrder
            listOf("a.txt", "one", "b.txt", "two", "c.txt")
        switch.du(a("tree"), APathGateway.DuOptions()) shouldBe 60L
    }

    /** Previews read through a seekable handle; every combination of server quirks must serve any offset. */
    @Test
    fun `a read-only handle serves random offsets`(): Unit = runBlocking {
        val content = Random(42).nextBytes(1024 * 1024 + 123)
        for (honorRange in listOf(true, false)) {
            for (weakEtags in listOf(false, true)) {
                val server = server {
                    this.honorRange = honorRange
                    this.weakEtags = weakEtags
                    putFile("big.bin", content)
                }
                val rig = rig(location(idA, server))

                rig.switch.file(a("big.bin"), readWrite = false).use { handle ->
                    handle.size() shouldBe content.size.toLong()
                    val random = Random(7)
                    val offsets = listOf(0L, 10L, 5L, 700_000L, 300L, content.size - 10L) +
                        List(20) { random.nextLong(content.size.toLong()) }
                    offsets.forEach { offset ->
                        val buffer = ByteArray(4096)
                        var filled = 0
                        while (filled < buffer.size) {
                            val read = handle.read(offset + filled, buffer, filled, buffer.size - filled)
                            if (read == -1) break
                            filled += read
                        }
                        val expected = content.copyOfRange(offset.toInt(), minOf(content.size, offset.toInt() + 4096))
                        buffer.copyOf(filled) shouldBe expected
                    }
                    handle.read(content.size.toLong(), ByteArray(1), 0, 1) shouldBe -1
                }
                rig.pool.close()
            }
        }
    }

    @Test
    fun `a read-only handle never mixes two versions of a file`(): Unit = runBlocking {
        for (weakEtags in listOf(false, true)) {
            val server = server {
                this.weakEtags = weakEtags
                putFile("doc.txt", ByteArray(64 * 1024) { 1 })
            }
            val rig = rig(location(idA, server))

            rig.switch.file(a("doc.txt"), readWrite = false).use { handle ->
                handle.read(0, ByteArray(100), 0, 100) shouldBe 100
                server.putFile("doc.txt", ByteArray(64 * 1024) { 2 })

                val error = shouldThrow<IOException> { handle.read(0, ByteArray(100), 0, 100) }
                WebDavStatusMapper.kindOf(error) shouldBe WebDavException.Kind.CHANGED
            }
            rig.pool.close()
        }
    }

    // endregion

    // region writing

    @Test
    fun `an output stream uploads with its length declared`(): Unit = runBlocking {
        val server = server { rejectUnknownLengthPut = true }
        val recorder = Recorder(server)
        val switch = rig(location(idA, server)).switch

        switch.write(a("up.txt"), "uploaded")

        server.text("up.txt") shouldBe "uploaded"
        recorder.of("PUT", "up.txt").map { it.headers["Content-Length"] } shouldBe listOf("8")
        assertNoSpoolLeft()
    }

    /** What the editor does when it saves a file. */
    @Test
    fun `an editor save replaces a WebDAV file and reads back`(): Unit = runBlocking {
        val server = server {
            rejectUnknownLengthPut = true
            putFile("notes/todo.txt", "line one\n".encodeToByteArray())
        }
        val switch = rig(location(idA, server)).switch
        val writer = AtomicFileWriter(switch, "WebDavGatewayIntegrationTest")

        writer.replace(a("notes", "todo.txt"), AtomicFileWriter.OriginalAccess.FromTarget) { context ->
            val original = context.openOriginalSource().buffer().use { it.readUtf8() }
            context.sink.writeUtf8(original + "line two\n")
        }
        writer.replace(a("notes", "new.txt"), AtomicFileWriter.OriginalAccess.None) { context ->
            context.sink.writeUtf8("fresh")
        }

        switch.read(a("notes", "todo.txt")) shouldBe "line one\nline two\n"
        switch.read(a("notes", "new.txt")) shouldBe "fresh"
        switch.listFiles(a("notes")).map { it.name } shouldContainExactlyInAnyOrder listOf("todo.txt", "new.txt")
        assertNoSpoolLeft()
    }

    @Test
    fun `a writable handle edits a spooled copy and uploads it on close`(): Unit = runBlocking {
        val server = server {
            rejectUnknownLengthPut = true
            putFile("overwrite.txt", "abcdef".encodeToByteArray())
            putFile("shrink.txt", "abcdef".encodeToByteArray())
            putFile("sparse.txt", "ab".encodeToByteArray())
            putFile("truncate.txt", "abcdef".encodeToByteArray())
            putFile("flush.txt", "abc".encodeToByteArray())
        }
        val recorder = Recorder(server)
        val switch = rig(location(idA, server)).switch

        switch.file(a("overwrite.txt"), readWrite = true).use { it.write(0, "X".encodeToByteArray(), 0, 1) }
        server.text("overwrite.txt") shouldBe "Xbcdef"

        switch.file(a("shrink.txt"), readWrite = true).use { it.resize(3) }
        server.text("shrink.txt") shouldBe "abc"

        switch.file(a("sparse.txt"), readWrite = true).use { handle ->
            handle.write(5, "Z".encodeToByteArray(), 0, 1)
            handle.size() shouldBe 6L
        }
        server.content("sparse.txt") shouldBe byteArrayOf('a'.code.toByte(), 'b'.code.toByte(), 0, 0, 0, 'Z'.code.toByte())

        // Truncating first means the old content is never needed
        switch.file(a("truncate.txt"), readWrite = true).use { handle ->
            handle.resize(0)
            handle.write(0, "new".encodeToByteArray(), 0, 3)
        }
        server.text("truncate.txt") shouldBe "new"
        recorder.of("GET", "truncate.txt") shouldBe emptyList()

        switch.file(a("flush.txt"), readWrite = true).use { handle ->
            handle.write(3, "d".encodeToByteArray(), 0, 1)
            handle.flush()
            server.text("flush.txt") shouldBe "abcd"
        }
        recorder.of("PUT", "flush.txt").size shouldBe 1

        // A handle that only reads never uploads
        switch.file(a("overwrite.txt"), readWrite = true).use { it.size() shouldBe 6L }
        recorder.of("PUT", "overwrite.txt").size shouldBe 1

        recorder.requests.filter { it.method == "PUT" }.forEach { it.headers["Content-Length"].shouldNotBeNull() }
        assertNoSpoolLeft()
    }

    @Test
    fun `a failed flush is not replayed on close`(): Unit = runBlocking {
        val server = server {
            rejectUnknownLengthPut = true
            putFile("flaky.txt", "abc".encodeToByteArray())
        }
        val recorder = Recorder(server)
        val switch = rig(location(idA, server)).switch
        var failed = false
        recorder.inject = { request, response ->
            if (!failed && request.method == "PUT" && request.headers["Content-Length"] != "0") {
                failed = true
                MockResponse.Builder().code(500).build()
            } else {
                response
            }
        }

        val handle = switch.file(a("flaky.txt"), readWrite = true)
        handle.write(3, "d".encodeToByteArray(), 0, 1)
        shouldThrow<IOException> { handle.flush() }
        runCatching { handle.close() }

        recorder.of("PUT", "flaky.txt").count { it.headers["Content-Length"] != "0" } shouldBe 1
        assertNoSpoolLeft()
    }

    @Test
    fun `appending to a missing file creates it`(): Unit = runBlocking {
        val server = server { rejectUnknownLengthPut = true }
        val switch = rig(location(idA, server)).switch

        switch.openOutputStream(a("log.txt"), append = true).use { it.write("one".encodeToByteArray()) }
        switch.openOutputStream(a("log.txt"), append = true).use { it.write("two".encodeToByteArray()) }

        server.text("log.txt") shouldBe "onetwo"
        assertNoSpoolLeft()
    }

    @Test
    fun `a failed upload on close throws and leaves no spool behind`(): Unit = runBlocking {
        val server = server {
            putFile("gone/handle.txt", "x".encodeToByteArray())
            mkdirs("gone2")
        }
        val switch = rig(location(idA, server)).switch

        val handle = switch.file(a("gone", "handle.txt"), readWrite = true)
        handle.write(0, "y".encodeToByteArray(), 0, 1)
        val stream = switch.openOutputStream(a("gone2", "stream.txt"))
        stream.write("data".encodeToByteArray())

        switch.delete(a("gone"), recursive = true) shouldBe true
        switch.delete(a("gone2"), recursive = true) shouldBe true

        shouldThrow<IOException> { handle.close() }
        shouldThrow<IOException> { stream.close() }
        assertNoSpoolLeft()
        server.exists("gone") shouldBe false
        server.exists("gone2") shouldBe false
    }

    @Test
    fun `an exclusive create never replaces anything`(): Unit = runBlocking {
        val server = server {
            putFile("keep.txt", "keep".encodeToByteArray())
            mkdirs("dir")
        }
        val switch = rig(location(idA, server)).switch

        shouldThrow<PathAlreadyExistsException> { switch.createFile(a("keep.txt")) }
        shouldThrow<PathAlreadyExistsException> { switch.createFile(a("dir")) }
        server.text("keep.txt") shouldBe "keep"

        switch.createFile(a("deep", "er", "new.txt"), createParents = true)
        server.content("deep/er/new.txt") shouldBe ByteArray(0)
    }

    @Test
    fun `a non-recursive delete refuses a collection with content`(): Unit = runBlocking {
        val server = server {
            putFile("full/a.txt", "a".encodeToByteArray())
        }
        val switch = rig(location(idA, server)).switch

        shouldThrow<WriteException> { switch.delete(a("full"), recursive = false) }
        server.text("full/a.txt") shouldBe "a"

        switch.delete(a("full", "a.txt"), recursive = false) shouldBe true
        switch.delete(a("full"), recursive = false) shouldBe true
        switch.delete(a("full"), recursive = false) shouldBe false
        server.exists("full") shouldBe false
    }

    /** Answers the `Depth: 1` listing of `full` with [body] as the multistatus content. */
    private fun Recorder.injectListingOfFull(body: (basePath: String) -> String) {
        inject = { request, response ->
            val name = request.url.pathSegments.filter(String::isNotEmpty).lastOrNull()
            if (request.method == "PROPFIND" && request.headers["Depth"] == "1" && name == "full") {
                val members = body(request.url.encodedPath.substringBefore("full"))
                MockResponse.Builder()
                    .code(207)
                    .addHeader("Content-Type", "application/xml; charset=utf-8")
                    .body("""<?xml version="1.0"?><d:multistatus xmlns:d="DAV:">$members</d:multistatus>""")
                    .build()
            } else {
                response
            }
        }
    }

    private fun davCollection(href: String) =
        "<d:response><d:href>$href</d:href><d:propstat><d:prop><d:resourcetype><d:collection/></d:resourcetype></d:prop>" +
            "<d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response>"

    private fun davFile(href: String) =
        "<d:response><d:href>$href</d:href><d:propstat><d:prop><d:resourcetype/><d:getcontentlength>1</d:getcontentlength>" +
            "</d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response>"

    @Test
    fun `a non-recursive delete refuses a collection with an unreadable member`(): Unit = runBlocking {
        val server = server {
            putFile("full/secret.txt", "s".encodeToByteArray())
        }
        val recorder = Recorder(server)
        val switch = rig(location(idA, server)).switch
        recorder.injectListingOfFull { base ->
            davCollection("${base}full/") +
                "<d:response><d:href>${base}full/secret.txt</d:href><d:status>HTTP/1.1 403 Forbidden</d:status></d:response>"
        }

        shouldThrow<PathPermissionDeniedException> { switch.delete(a("full"), recursive = false) }

        recorder.of("DELETE", "full") shouldBe emptyList()
        server.exists("full/secret.txt") shouldBe true
    }

    @Test
    fun `a non-recursive delete refuses a collection listed under another path`(): Unit = runBlocking {
        val server = server {
            putFile("full/secret.txt", "s".encodeToByteArray())
        }
        val recorder = Recorder(server)
        val switch = rig(location(idA, server)).switch
        recorder.injectListingOfFull { davCollection("/backend/full/") + davFile("/backend/full/secret.txt") }

        val error = shouldThrow<WriteException> { switch.delete(a("full"), recursive = false) }
        WebDavStatusMapper.kindOf(error) shouldBe WebDavException.Kind.PROTOCOL

        recorder.of("DELETE", "full") shouldBe emptyList()
        server.exists("full/secret.txt") shouldBe true
    }

    @Test
    fun `mkdir, rename and delete`(): Unit = runBlocking {
        val server = server {
            putFile("file", "f".encodeToByteArray())
        }
        val switch = rig(location(idA, server)).switch

        switch.createDir(a("x"))
        switch.lookup(a("x"), LookupOptions()).fileType shouldBe FileType.DIRECTORY
        shouldThrow<PathAlreadyExistsException> { switch.createDir(a("x")) }
        switch.createDir(a("x"), createParents = true)
        switch.createDir(a("p", "q", "r"), createParents = true)
        server.isCollection("p/q/r") shouldBe true

        shouldThrow<PathAlreadyExistsException> { switch.createDir(a("file"), createParents = true) }
        shouldThrow<PathAlreadyExistsException> { switch.createDir(a("file", "sub"), createParents = true) }
        server.text("file") shouldBe "f"

        switch.move(a("x"), a("y")) shouldBe MoveOutcome.Moved
        switch.exists(a("x")) shouldBe false
        switch.lookup(a("y"), LookupOptions()).fileType shouldBe FileType.DIRECTORY

        switch.delete(setOf(a("y"), a("p")), DeleteAction.Options(recursive = true)).toList()
        server.exists("y") shouldBe false
        server.exists("p") shouldBe false
    }

    // endregion

    // region transfers

    @Test
    fun `copies and moves within one location run on the server`(): Unit = runBlocking {
        val server = server {
            putFile("src/a.txt", "server side".encodeToByteArray())
            putFile("tree/inner/f.txt", "f".encodeToByteArray())
            mkdirs("dst")
            mkdirs("moved")
        }
        val recorder = Recorder(server)
        val switch = rig(location(idA, server)).switch

        switch.copy(setOf(a("src", "a.txt")), a("dst"), null, CopyAction.Options()).toList()
        server.text("dst/a.txt") shouldBe "server side"
        recorder.of("COPY", "a.txt").size shouldBe 1

        switch.move(setOf(a("src", "a.txt")), a("moved"), null, MoveAction.Options()).toList()
        server.text("moved/a.txt") shouldBe "server side"
        server.exists("src/a.txt") shouldBe false
        recorder.of("MOVE", "a.txt").size shouldBe 1

        switch.move(setOf(a("tree")), a("dst"), null, MoveAction.Options()).toList()
        server.text("dst/tree/inner/f.txt") shouldBe "f"
        server.exists("tree") shouldBe false

        recorder.requests.none { it.method == "GET" || it.method == "PUT" } shouldBe true
    }

    @Test
    fun `transfers within one location never replace an existing destination`(): Unit = runBlocking {
        val server = server {
            putFile("one.txt", "one".encodeToByteArray())
            putFile("two.txt", "two".encodeToByteArray())
            putFile("into/one.txt", "other".encodeToByteArray())
            mkdirs("coll")
            // Stock Apache: a MOVE onto a collection named without a trailing slash gets a 301 without Location
            redirectSlashlessCollections = true
        }
        val rig = rig(location(idA, server))
        val switch = rig.switch

        shouldThrow<PathAlreadyExistsException> { switch.move(a("one.txt"), a("two.txt")) }
        shouldThrow<PathAlreadyExistsException> { switch.move(a("one.txt"), a("coll")) }
        shouldThrow<PathAlreadyExistsException> { rig.ops.copy(a("one.txt"), a("coll")) }
        shouldThrow<PathAlreadyExistsException> { rig.ops.copy(a("one.txt"), a("two.txt")) }

        val skip: suspend (PathActionIssue) -> PathActionIssue.Resolution = { issue ->
            issue.shouldBeInstanceOf<PathActionIssue.PathAlreadyExists>()
            PathActionIssue.PathAlreadyExists.Resolution.Skip()
        }
        switch.copy(setOf(a("one.txt")), a("into"), skip, CopyAction.Options()).toList()

        server.text("one.txt") shouldBe "one"
        server.text("two.txt") shouldBe "two"
        server.text("into/one.txt") shouldBe "other"
        server.isCollection("coll") shouldBe true
    }

    @Test
    fun `copy and move between WebDAV and local storage`(@TempDir tempDir: File): Unit = runBlocking {
        val server = server { rejectUnknownLengthPut = true }
        val recorder = Recorder(server)
        val switch = rig(location(idA, server), spool = noSpool()).switch
        val local = LocalPath.build(tempDir)
        val upload = Random(3).nextBytes(300 * 1024)

        File(tempDir, "up.bin").writeBytes(upload)
        switch.copy(setOf(local.child("up.bin")), a(), null, CopyAction.Options()).toList()
        server.content("up.bin") shouldBe upload
        recorder.of("PUT", "up.bin").map { it.headers["Content-Length"] } shouldBe listOf("0", upload.size.toString())

        server.putFile("down.txt", "from the server".encodeToByteArray())
        switch.copy(setOf(a("down.txt")), local, null, CopyAction.Options()).toList()
        File(tempDir, "down.txt").readText() shouldBe "from the server"

        File(tempDir, "moved-up.txt").writeText("moved up")
        switch.move(setOf(local.child("moved-up.txt")), a(), null, MoveAction.Options()).toList()
        server.text("moved-up.txt") shouldBe "moved up"
        File(tempDir, "moved-up.txt").exists() shouldBe false

        server.putFile("moved-down.txt", "moved down".encodeToByteArray())
        switch.move(setOf(a("moved-down.txt")), local, null, MoveAction.Options()).toList()
        File(tempDir, "moved-down.txt").readText() shouldBe "moved down"
        server.exists("moved-down.txt") shouldBe false

        File(tempDir, "folder/nested").mkdirs()
        File(tempDir, "folder/nested/n.txt").writeText("nested")
        switch.copy(setOf(local.child("folder")), a(), null, CopyAction.Options()).toList()
        server.text("folder/nested/n.txt") shouldBe "nested"

        recorder.requests.filter { it.method == "PUT" }.forEach { it.headers["Content-Length"].shouldNotBeNull() }
        // Only streamed uploads can have succeeded above
        shouldThrow<WriteException> { switch.write(a("spooled.txt"), "x") }
    }

    @Test
    fun `copies between two WebDAV locations upload with a known length`(): Unit = runBlocking {
        val serverA = server { putFile("a.txt", "across".encodeToByteArray()) }
        val serverB = server { rejectUnknownLengthPut = true }
        val switch = rig(location(idA, serverA), location(idB, serverB), spool = noSpool()).switch

        switch.copy(setOf(a("a.txt")), b(), null, CopyAction.Options()).toList()
        serverB.text("a.txt") shouldBe "across"

        serverA.putFile("m.txt", "moving across".encodeToByteArray())
        switch.move(setOf(a("m.txt")), b(), null, MoveAction.Options()).toList()
        serverB.text("m.txt") shouldBe "moving across"
        serverA.exists("m.txt") shouldBe false
    }

    @Test
    fun `a lost PUT response fails the copy and leaves no claimed file`(@TempDir tempDir: File): Unit =
        runBlocking {
            val server = server()
            val recorder = Recorder(server)
            val switch = rig(location(idA, server)).switch
            File(tempDir, "up.bin").writeBytes(Random(5).nextBytes(256 * 1024))
            recorder.inject = { request, response ->
                if (request.method == "PUT" && request.headers["Content-Length"] != "0") {
                    response.newBuilder().onResponseStart(SocketEffect.CloseSocket()).build()
                } else {
                    response
                }
            }

            shouldThrow<Exception> {
                switch.copy(setOf(LocalPath.build(tempDir).child("up.bin")), a(), null, CopyAction.Options()).toList()
            }

            server.exists("up.bin") shouldBe false
            // An upload is a mutation and is never replayed
            recorder.of("PUT", "up.bin").count { it.headers["Content-Length"] != "0" } shouldBe 1
            File(tempDir, "up.bin").exists() shouldBe true
        }

    @Test
    fun `an upload interrupted mid-body fails the copy and leaves no claimed file`(@TempDir tempDir: File): Unit =
        runBlocking {
            val server = server()
            val recorder = Recorder(server)
            val rig = rig(location(idA, server))
            val content = Random(6).nextBytes(256 * 1024)
            File(tempDir, "up.bin").writeBytes(content)
            val source = LocalPath.build(tempDir).child("up.bin")
            val localOps = LocalFileSystemOps(mockk(relaxed = true))
            // Two upload-pipe-fulls, so the PUT is on the wire when the source breaks off
            val delivered = 128 * 1024
            val sourceOps = object : FileSystemOps<LocalPath, LocalPathLookup> by localOps {
                override suspend fun openInputStream(path: LocalPath): InputStream = SequenceInputStream(
                    ByteArrayInputStream(content, 0, delivered),
                    object : InputStream() {
                        override fun read(): Int = throw IOException("source failed")
                    },
                )
            }

            val error = shouldThrow<Exception> {
                WebDavUploadCopyStrategy<LocalPath, LocalPathLookup>(rig.ops).transferFile(
                    sourceLookup = localOps.lookup(source, LookupOptions.BASE),
                    destination = a("up.bin"),
                    sourceOps = sourceOps,
                    destOps = rig.ops,
                    options = TransferStrategy.Options(),
                    onProgress = {},
                )
            }
            generateSequence<Throwable>(error) { it.cause }.any { it.message == "source failed" } shouldBe true

            server.exists("up.bin") shouldBe false
            // Only the empty claim was answered: no body was ever completed
            recorder.of("PUT", "up.bin").map { it.headers["Content-Length"] } shouldBe listOf("0")

            val received = buildList<RecordedRequest> {
                val deadline = System.nanoTime() + 10.seconds.inWholeNanoseconds
                while (none { it.method == "PUT" && it.failure != null } && System.nanoTime() < deadline) {
                    server.server.takeRequest(100, TimeUnit.MILLISECONDS)?.let(::add)
                }
                generateSequence { server.server.takeRequest(0, TimeUnit.MILLISECONDS) }.forEach(::add)
            }
            val uploads = received.filter {
                it.method == "PUT" && it.headers["Content-Length"] == content.size.toString()
            }
            uploads.size shouldBe 1
            uploads.single().failure.shouldNotBeNull()
            uploads.single().bodySize shouldBeInRange (1L until content.size.toLong())

            File(tempDir, "up.bin").exists() shouldBe true
        }

    // endregion

    // region failures

    @Test
    fun `server failures map to Butler exceptions`(): Unit = runBlocking {
        val server = server {
            putFile("secret/a.txt", "a".encodeToByteArray())
            putFile("locked.txt", "l".encodeToByteArray())
            putFile("file.txt", "f".encodeToByteArray())
        }
        val recorder = Recorder(server)
        val switch = rig(location(idA, server), location(idB, server, url = server.baseUrl + "file.txt")).switch
        recorder.inject = { request, response ->
            val name = request.url.pathSegments.filter(String::isNotEmpty).lastOrNull()
            when {
                name == "secret" -> MockResponse.Builder().code(403).build()
                name == "full.txt" && request.method == "PUT" -> MockResponse.Builder().code(507).build()
                name == "locked.txt" && request.method == "DELETE" -> MockResponse.Builder().code(423).build()
                else -> response
            }
        }

        shouldThrow<PathPermissionDeniedException> { switch.lookupFiles(a("secret"), LookupOptions()) }

        val full = shouldThrow<WriteException> { switch.write(a("full.txt"), "data") }
        WebDavStatusMapper.kindOf(full) shouldBe WebDavException.Kind.DISK_FULL

        val locked = shouldThrow<WriteException> { switch.delete(a("locked.txt"), recursive = true) }
        WebDavStatusMapper.kindOf(locked) shouldBe WebDavException.Kind.LOCKED

        shouldThrow<WebDavNotCollectionException> { switch.lookup(b(), LookupOptions()) }

        val missingParent = shouldThrow<ReadException> { switch.createDir(a("no", "such", "dir")) }
        missingParent.path shouldBe a("no", "such")
    }

    @Test
    fun `sign-in and reachability failures`(): Unit = runBlocking {
        val server = server()
        val wrongPassword = rig(location(idA, server), password = "wrong").switch
        shouldThrow<WebDavAuthException> { wrongPassword.lookup(a(), LookupOptions()) }
            .isWebDavSignInFailure() shouldBe true

        val missingRoot = rig(location(idA, server, url = server.baseUrl + "gone/")).switch
        missingRoot.existsStrict(a()) shouldBe Existence.ABSENT

        val closed = server().also { it.close() }
        val offline = rig(location(idA, closed)).switch
        shouldThrow<WebDavUnreachableException> { offline.lookup(a(), LookupOptions()) }
        offline.existsStrict(a()) shouldBe Existence.UNKNOWN
    }

    @Test
    fun `an untrusted certificate is reported for a trust decision, a pinned one is accepted`(): Unit = runBlocking {
        val held = TestCertificates.leaf("localhost")
        val server = server(certificate = held) { putFile("tls.txt", "secure".encodeToByteArray()) }
        val presented = TrustedCertificate.from(ServerCertificate(held.certificate.encoded))
        val other = TrustedCertificate.from(ServerCertificate(TestCertificates.leaf("localhost").certificate.encoded))

        val untrusted = shouldThrow<WebDavCertificateException> {
            rig(location(idA, server)).switch.lookup(a(), LookupOptions())
        }
        untrusted.stored.shouldBeNull()
        untrusted.presented.toTrustedCertificate() shouldBe presented
        untrusted.locationId shouldBe idA

        rig(location(idA, server, pin = presented)).switch.read(a("tls.txt")) shouldBe "secure"

        val changed = shouldThrow<WebDavCertificateException> {
            rig(location(idA, server, pin = other)).switch.lookup(a(), LookupOptions())
        }
        changed.stored!!.toTrustedCertificate() shouldBe other
        changed.presented.toTrustedCertificate() shouldBe presented
    }

    @Test
    fun `a free user cannot read a byte`(): Unit = runBlocking {
        val server = server { putFile("a.txt", "paid".encodeToByteArray()) }
        val switch = rig(location(idA, server), pro = false).switch

        shouldThrow<WebDavProRequiredException> { switch.read(a("a.txt")) }
        shouldThrow<WebDavProRequiredException> { switch.file(a("a.txt"), readWrite = false) }
        shouldThrow<WebDavProRequiredException> { switch.lookupFiles(a(), LookupOptions()) }

        server.requestCount shouldBe 0
    }

    // endregion
}
