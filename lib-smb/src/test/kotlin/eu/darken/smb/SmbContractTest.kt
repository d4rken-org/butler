package eu.darken.smb

import kotlin.time.Instant
import kotlin.uuid.Uuid
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.wait.strategy.Wait

/** Identical assertions run first against SMBJ, then against the Kotlin protocol implementation. */
@Tag("smb-server")
internal abstract class SmbContractTest {
    protected abstract fun connector(config: SmbConfig = SmbConfig()): SmbConnector

    private suspend fun privateShare(config: SmbConfig = SmbConfig()): SmbShare =
        connector(config).connect(server.endpoint("private"), password())

    @Test
    fun `chunk flows retain buffers and release handles on early completion`() = runBlocking {
        privateShare().use { share ->
            val path = SmbPath.Root.child(Uuid.random().toString())
            val first = ByteArray(100_000) { (it % 251).toByte() }
            val second = byteArrayOf(7, 8, 9)
            assertEquals(100_003L, share.writeChunks(path, flowOf(first, second)))
            val chunks = share.readChunks(path, chunkSize = 8192)
            val collected = chunks.toList()
            assertArrayEquals(
                first + second,
                collected.fold(byteArrayOf()) { bytes, chunk -> bytes + chunk },
            )
            assertArrayEquals(first.copyOf(8192), chunks.take(1).toList().single())
            share.delete(path)
        }
    }

    @Test
    fun `blocking and suspend views share file contents and closure`() = runBlocking {
        privateShare().use { share ->
            val path = SmbPath.Root.child(Uuid.random().toString())
            val file = share.openFile(path, SmbOpenMode.CREATE_NEW)
            file.use {
                val blocking = it.blocking()
                blocking.write(2, byteArrayOf(99, 1, 2, 3, 99), 1, 3)
                assertEquals(5L, it.size())
                val bytes = ByteArray(5)
                assertEquals(3, blocking.read(2, bytes, 1, 3))
                assertArrayEquals(byteArrayOf(0, 1, 2, 3, 0), bytes)
                blocking.resize(3)
                blocking.flush()
                assertEquals(3L, it.size())
                blocking.close()
                assertThrows(Exception::class.java) { blocking.size() }
            }
            share.delete(path)
        }
    }

    @Test
    fun `create enumerate rename and delete preserve metadata and content`() = runBlocking {
        privateShare().use { share ->
            val dir = SmbPath.Root.child(Uuid.random().toString())
            share.mkdir(dir)
            assertEquals(SmbFileType.DIRECTORY, share.stat(dir).type)
            val file = dir.child("Grüße 日本語.txt")
            val content = "hello SMB".encodeToByteArray()
            share.openFile(file, SmbOpenMode.CREATE_NEW).use {
                it.write(0, content)
                it.flush()
            }
            val listing = share.list(dir).toList()
            assertEquals(listOf(file), listing.map { it.path })
            assertEquals(content.size.toLong(), listing.single().size)
            assertEquals(SmbFileType.FILE, listing.single().type)
            val modified = Instant.fromEpochMilliseconds(1_700_000_000_000)
            share.setModifiedAt(file, modified)
            assertEquals(modified, share.stat(file).modifiedAt)
            share.openFile(file).use {
                val buffer = ByteArray(content.size + 5)
                assertEquals(content.size, it.read(0, buffer, 2, content.size))
                assertArrayEquals(content, buffer.copyOfRange(2, 2 + content.size))
                assertEquals(-1, it.read(content.size.toLong(), buffer))
                assertEquals(0, it.read(content.size.toLong(), buffer, 0, 0))
            }
            val moved = dir.child("renamed.txt")
            share.move(file, moved)
            assertFailure(SmbException.Kind.MISSING) { share.stat(file) }
            assertEquals(content.size.toLong(), share.stat(moved).size)
            share.delete(moved)
            share.delete(dir)
            assertFailure(SmbException.Kind.MISSING) { share.stat(dir) }
        }
    }

    @Test
    fun `create new never overwrites and rename never replaces an existing file`() = runBlocking {
        privateShare().use { share ->
            val dir = SmbPath.Root.child(Uuid.random().toString())
            share.mkdir(dir)
            val first = dir.child("first")
            val second = dir.child("second")
            for (path in listOf(first, second)) share.openFile(path, SmbOpenMode.CREATE_NEW).use {
                it.write(0, byteArrayOf(42))
            }
            assertFailure(SmbException.Kind.ALREADY_EXISTS) {
                share.openFile(first, SmbOpenMode.CREATE_NEW)
            }
            assertFailure(SmbException.Kind.ALREADY_EXISTS) { share.move(first, second) }
            assertEquals(1L, share.stat(first).size)
            assertEquals(1L, share.stat(second).size)
            assertFailure(SmbException.Kind.DIRECTORY_NOT_EMPTY) { share.delete(dir) }
            share.delete(dir, recursive = true)
        }
    }

    @Test
    fun `random access supports sparse offsets beyond two GiB and truncation`() = runBlocking {
        privateShare().use { share ->
            val path = SmbPath.Root.child(Uuid.random().toString())
            val offset = 3L * 1024 * 1024 * 1024
            val payload = byteArrayOf(1, 2, 3, 4)
            share.openFile(path, SmbOpenMode.READ_WRITE).use {
                it.write(offset, payload)
                assertEquals(offset + payload.size, it.size())
                val readBack = ByteArray(4)
                assertEquals(4, it.read(offset, readBack))
                assertArrayEquals(payload, readBack)
                it.resize(2)
                assertEquals(2, it.size())
            }
            share.delete(path)
        }
    }

    @Test
    fun `open existing preserves bytes overwrite truncates and append starts from current size`() =
        runBlocking {
            privateShare().use { share ->
                val path = SmbPath.Root.child(Uuid.random().toString())
                share.openFile(path, SmbOpenMode.CREATE_NEW).use { it.write(0, byteArrayOf(1, 2)) }
                share.openFile(path, SmbOpenMode.READ_WRITE).use {
                    it.write(it.size(), byteArrayOf(3))
                }
                share.openFile(path, SmbOpenMode.READ_WRITE).use { assertEquals(3L, it.size()) }
                share.openFile(path, SmbOpenMode.OVERWRITE).use { assertEquals(0L, it.size()) }
                share.delete(path)
            }
        }

    @Test
    fun `listing is repeatable and a short collection leaves the session usable`() = runBlocking {
        privateShare().use { share ->
            val dir = SmbPath.Root.child(Uuid.random().toString())
            share.mkdir(dir)
            repeat(240) {
                share
                    .openFile(
                        dir.child("entry-$it-" + ("日".repeat(60) + "x".repeat(50))),
                        SmbOpenMode.CREATE_NEW,
                    )
                    .close()
            }
            val listing = share.list(dir)
            assertEquals(1, listing.take(1).toList().size)
            assertEquals(240, listing.toList().size)
            assertEquals(240, listing.toList().size)
            share.delete(dir, recursive = true)
        }
    }

    @Test
    fun `guest can open public share`() = runBlocking {
        connector().connect(server.endpoint("public"), SmbCredentials.Guest).use {
            assertEquals(SmbFileType.DIRECTORY, it.stat(SmbPath.Root).type)
        }
    }

    @Test
    fun `wrong credentials and unknown share have distinct errors`() = runBlocking {
        assertFailure(SmbException.Kind.AUTHENTICATION) {
            connector()
                .connect(
                    server.endpoint("private"),
                    SmbCredentials.Password("butler", "wrong".toCharArray()),
                )
        }
        assertFailure(SmbException.Kind.SHARE_MISSING) {
            connector().connect(server.endpoint("absent"), password())
        }
    }

    @ParameterizedTest
    @EnumSource(SmbDialect::class)
    fun `all existing SMB dialects authenticate and perform signed IO`(dialect: SmbDialect) =
        runBlocking {
            privateShare(SmbConfig(dialects = setOf(dialect), requireSigning = true)).use {
                assertEquals(dialect, it.dialect)
                assertEquals(SmbFileType.DIRECTORY, it.stat(SmbPath.Root).type)
                val capacity = it.capacity()
                assertTrue(capacity.totalBytes > 0)
                assertTrue(capacity.freeBytes in 0..capacity.totalBytes)
            }
        }

    @ParameterizedTest
    @EnumSource(SmbDialect::class, names = ["SMB_3_0", "SMB_3_0_2", "SMB_3_1_1"])
    fun `SMB3 encryption authenticates and transfers file data`(dialect: SmbDialect) = runBlocking {
        privateShare(SmbConfig(dialects = setOf(dialect), requireEncryption = true)).use {
            transferLargeFile(it)
        }
    }

    @Test
    fun `server required signing is honored without client opt in`() = runBlocking {
        connector().connect(secureServer.endpoint("private"), password()).use {
            transferLargeFile(it)
        }
    }

    @Test
    fun `large signed transfers split requests and preserve offset slices`() = runBlocking {
        privateShare(SmbConfig(requireSigning = true)).use { transferLargeFile(it) }
    }

    private suspend fun transferLargeFile(share: SmbShare) {
        val path = SmbPath.Root.child(Uuid.random().toString())
        val payload = ByteArray(1024 * 1024 + 17).also { java.util.Random(42).nextBytes(it) }
        share.openFile(path, SmbOpenMode.CREATE_NEW).use { file ->
            file.write(0, payload, 3, payload.size - 6)
            file.write(0, ByteArray(0))
            file.flush()
            val result = ByteArray(payload.size - 6)
            var done = 0
            while (done < result.size) {
                val count = file.read(done.toLong(), result, done, result.size - done)
                assertTrue(count > 0)
                done += count
            }
            assertArrayEquals(payload.copyOfRange(3, payload.size - 3), result)
            assertEquals(4, file.read(result.size - 4L, ByteArray(10)))
            share.openFile(path).use { assertEquals(result.size.toLong(), it.size()) }
        }
        share.delete(path)
    }

    @Test
    fun `path type failures remain distinct from missing paths`() = runBlocking {
        privateShare().use { share ->
            val dir = SmbPath.Root.child(Uuid.random().toString())
            share.mkdir(dir)
            val file = dir.child("file")
            share.openFile(file, SmbOpenMode.CREATE_NEW).close()
            assertFailure(SmbException.Kind.ALREADY_EXISTS) { share.mkdir(dir) }
            assertFailure(SmbException.Kind.MISSING) {
                share.stat(dir.child("absent").child("child"))
            }
            assertFailure(SmbException.Kind.MISSING) {
                share.mkdir(dir.child("absent").child("child"))
            }
            assertFailure(SmbException.Kind.IS_DIRECTORY) { share.openFile(dir) }
            assertFailure(SmbException.Kind.NOT_DIRECTORY) { share.list(file).toList() }
            val moved = SmbPath.Root.child(Uuid.random().toString())
            share.move(dir, moved)
            assertEquals(SmbFileType.FILE, share.stat(moved.child("file")).type)
            share.delete(moved, recursive = true)
        }
    }

    @Test
    fun `share permission denial is separate from authentication failure`() = runBlocking {
        assertFailure(SmbException.Kind.SHARE_ACCESS_DENIED) {
            connector().connect(server.endpoint("restricted"), password())
        }
        connector().connect(server.endpoint("readonly"), password()).use { share ->
            assertFailure(SmbException.Kind.ACCESS_DENIED) {
                share.openFile(SmbPath.Root.child("forbidden"), SmbOpenMode.CREATE_NEW)
            }
        }
    }

    @Test
    fun `password authentication never silently becomes guest`() = runBlocking {
        assertFailure(SmbException.Kind.AUTHENTICATION) {
            connector()
                .connect(
                    server.endpoint("public"),
                    SmbCredentials.Password("nonexistent-user", "wrong".toCharArray()),
                )
        }
    }

    @Test
    fun `concurrent operations share a session and caller may wipe credentials`() = runBlocking {
        val secret = "butlerpass".toCharArray()
        connector()
            .connect(server.endpoint("private"), SmbCredentials.Password("butler", secret))
            .use { share ->
                secret.fill('\u0000')
                coroutineScope {
                    (1..8)
                        .map { async { share.stat(SmbPath.Root) } }
                        .awaitAll()
                        .forEach { assertEquals(SmbFileType.DIRECTORY, it.type) }
                }
            }
    }

    @Test
    fun `recursive delete handles empty and nested directories`() = runBlocking {
        privateShare().use { share ->
            val root = SmbPath.Root.child(Uuid.random().toString())
            share.mkdir(root)
            assertTrue(share.list(root).toList().isEmpty())
            repeat(3) { index ->
                val child = root.child("child$index")
                share.mkdir(child)
                val nested = child.child("nested")
                share.mkdir(nested)
                repeat(4) {
                    share.openFile(nested.child("file$it"), SmbOpenMode.CREATE_NEW).close()
                }
            }
            share.delete(root, recursive = true)
            assertFailure(SmbException.Kind.MISSING) { share.stat(root) }
        }
    }

    @Test
    fun `failed chunk producer leaves partial bytes without replaying`() = runBlocking {
        privateShare().use { share ->
            val path = SmbPath.Root.child(Uuid.random().toString())
            val failure = IllegalStateException("producer failed")
            try {
                share.writeChunks(
                    path,
                    flow {
                        emit(byteArrayOf(1, 2, 3))
                        throw failure
                    },
                )
                fail<Unit>("Expected producer failure")
            } catch (error: IllegalStateException) {
                assertSame(failure, error)
            }
            assertArrayEquals(byteArrayOf(1, 2, 3), share.readChunks(path).toList().single())
            share.delete(path)
        }
    }

    private suspend fun assertFailure(kind: SmbException.Kind, block: suspend () -> Unit) {
        try {
            block()
            fail<Unit>("Expected $kind")
        } catch (error: SmbException) {
            assertEquals(kind, error.kind)
        }
    }

    private fun password() = SmbCredentials.Password("butler", "butlerpass".toCharArray())

    companion object {
        private val server: SambaServer by lazy { SambaServer().also { it.start() } }
        private val secureServer: SambaServer by lazy {
            SambaServer(secure = true).also { it.start() }
        }
    }
}

internal class SmbjContractTest : SmbContractTest() {
    override fun connector(config: SmbConfig): SmbConnector = SmbjOracle(config)
}

internal class SambaServer(
    secure: Boolean = false,
    encrypt: Boolean = false,
    legacy: Boolean = false,
) :
    GenericContainer<SambaServer>(
        "dperson/samba@sha256:66088b78a19810dd1457a8f39340e95e663c728083efa5fe7dc0d40b2478e869"
    ) {
    init {
        withExposedPorts(445)
        val command =
            mutableListOf(
                "-u",
                "butler;butlerpass",
                "-u",
                "other;otherpass",
                "-s",
                "private;/srv/private;no;no;no;butler",
                "-s",
                "public;/srv/public;yes;no;yes",
                "-s",
                "readonly;/srv/readonly;yes;yes;yes",
                "-s",
                "restricted;/srv/restricted;no;no;no;other",
                "-g",
                "map to guest = Bad User",
                "-g",
                "server min protocol = ${if (legacy) "NT1" else "SMB2_02"}",
                "-g",
                "server max protocol = ${if (legacy) "NT1" else "SMB3_11"}",
                "-p",
            )
        if (secure) command.addAll(listOf("-g", "server signing = mandatory"))
        if (encrypt) command.addAll(listOf("-g", "smb encrypt = required"))
        withCommand(*command.toTypedArray())
        waitingFor(Wait.forListeningPort())
    }

    fun endpoint(share: String) = SmbEndpoint(host, share, getMappedPort(445))
}
