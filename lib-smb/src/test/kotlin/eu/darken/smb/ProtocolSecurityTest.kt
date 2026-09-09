package eu.darken.smb

import eu.darken.smb.protocol.Wire
import eu.darken.smb.protocol.put16
import eu.darken.smb.protocol.put32
import eu.darken.smb.protocol.put64
import java.io.Closeable
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.toList
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

@Tag("smb-server")
internal class ProtocolSecurityTest {
    private fun password() = SmbCredentials.Password("butler", "butlerpass".toCharArray())

    @Test
    fun `final authentication signature is verified`() = runBlocking {
        SambaServer().use { server ->
            server.start()
            SmbProxy(
                    server.endpoint("private"),
                    downstream = { frame ->
                        val wire = Wire(frame)
                        if (wire.u16(12) == 1 && wire.u32(8) == 0L)
                            frame[48] = (frame[48].toInt() xor 1).toByte()
                        listOf(frame)
                    },
                )
                .use { proxy ->
                    assertProtocolFailure { KotlinSmbClient().connect(proxy.endpoint, password()) }
                }
        }
    }

    @Test
    fun `signed file data cannot be modified by the transport`() = runBlocking {
        SambaServer().use { server ->
            server.start()
            SmbProxy(
                    server.endpoint("private"),
                    downstream = { frame ->
                        if (Wire(frame).u16(12) == 8 && Wire(frame).u32(8) == 0L)
                            frame[frame.lastIndex] = (frame.last().toInt() xor 1).toByte()
                        listOf(frame)
                    },
                )
                .use { proxy ->
                    KotlinSmbClient(SmbConfig(requireSigning = true))
                        .connect(proxy.endpoint, password())
                        .use { share ->
                            share
                                .openFile(SmbPath.Root.child("signed"), SmbOpenMode.CREATE_NEW)
                                .use { file ->
                                    file.write(0, byteArrayOf(1, 2, 3))
                                    assertProtocolFailure { file.read(0, ByteArray(3)) }
                                    assertFalse(share.connected)
                                }
                        }
                }
        }
    }

    @Test
    fun `encrypted responses reject modified authentication tags`() = runBlocking {
        SambaServer().use { server ->
            server.start()
            SmbProxy(
                    server.endpoint("private"),
                    downstream = { frame ->
                        if (frame[0] == 0xfd.toByte()) frame[4] = (frame[4].toInt() xor 1).toByte()
                        listOf(frame)
                    },
                )
                .use { proxy ->
                    assertProtocolFailure {
                        KotlinSmbClient(SmbConfig(requireEncryption = true))
                            .connect(proxy.endpoint, password())
                    }
                }
        }
    }

    @Test
    fun `responses are dispatched by message id even when reordered`() = runBlocking {
        SambaServer().use { server ->
            server.start()
            val first = AtomicReference<ByteArray?>()
            val enabled = AtomicBoolean(false)
            SmbProxy(
                    server.endpoint("private"),
                    downstream = { frame ->
                        if (enabled.get() && Wire(frame).u16(12) == 5) {
                            if (first.compareAndSet(null, frame)) emptyList()
                            else {
                                enabled.set(false)
                                listOf(frame, first.getAndSet(null)!!)
                            }
                        } else listOf(frame)
                    },
                )
                .use { proxy ->
                    KotlinSmbClient().connect(proxy.endpoint, password()).use { share ->
                        enabled.set(true)
                        withTimeout(5000) {
                            val one = async { share.stat(SmbPath.Root) }
                            val two = async { share.stat(SmbPath.Root) }
                            assertEquals(SmbFileType.DIRECTORY, one.await().type)
                            assertEquals(SmbFileType.DIRECTORY, two.await().type)
                        }
                    }
                }
        }
    }

    @Test
    fun `cancelled request closes its connection and leaves other connections usable`() =
        runBlocking {
            SambaServer().use { server ->
                server.start()
                val readSent = CountDownLatch(1)
                SmbProxy(
                        server.endpoint("private"),
                        upstream = { frame ->
                            if (Wire(frame).u16(12) == 8) {
                                readSent.countDown()
                                emptyList()
                            } else listOf(frame)
                        },
                    )
                    .use { proxy ->
                        KotlinSmbClient().connect(server.endpoint("private"), password()).use {
                            other ->
                            KotlinSmbClient().connect(proxy.endpoint, password()).use { share ->
                                share
                                    .openFile(
                                        SmbPath.Root.child("cancelled"),
                                        SmbOpenMode.CREATE_NEW,
                                    )
                                    .use { file ->
                                        file.write(0, byteArrayOf(1))
                                        val read = async { file.read(0, ByteArray(1)) }
                                        assertTrue(
                                            withContext(Dispatchers.IO) {
                                                readSent.await(3, TimeUnit.SECONDS)
                                            }
                                        )
                                        withTimeout(3000) { read.cancelAndJoin() }
                                        assertFalse(share.connected)
                                        assertEquals(
                                            SmbFileType.DIRECTORY,
                                            other.stat(SmbPath.Root).type,
                                        )
                                    }
                            }
                        }
                    }
            }
        }

    @Test
    fun `server mandated encryption works without a client opt in`() = runBlocking {
        SambaServer(encrypt = true).use { server ->
            server.start()
            KotlinSmbClient().connect(server.endpoint("private"), password()).use { share ->
                share.openFile(SmbPath.Root.child("encrypted"), SmbOpenMode.CREATE_NEW).use {
                    it.write(0, byteArrayOf(42))
                    assertEquals(1, it.read(0, ByteArray(1)))
                }
            }
        }
    }

    @Test
    fun `SMB1 server is identified without authenticating or downgrading`() = runBlocking {
        SambaServer(legacy = true).use { server ->
            server.start()
            try {
                KotlinSmbClient().connect(server.endpoint("private"), password())
                fail<Unit>("SMB1 must be rejected")
            } catch (error: SmbException) {
                assertEquals(SmbException.Kind.UNSUPPORTED_DIALECT, error.kind)
            }
        }
    }

    @Test
    fun `large concurrent transfers adapt to a single granted credit`() = runBlocking {
        SambaServer().use { server ->
            server.start()
            val sawTransfer = AtomicBoolean(false)
            val oversized = AtomicBoolean(false)
            SmbProxy(
                    server.endpoint("public"),
                    upstream = { frame ->
                        val wire = Wire(frame)
                        if (wire.u16(12) in 8..9) {
                            sawTransfer.set(true)
                            if (wire.u16(6) > 1 || wire.u32(68) > 65536) oversized.set(true)
                        }
                        listOf(frame)
                    },
                    downstream = { frame ->
                        frame.put16(14, 1)
                        listOf(frame)
                    },
                )
                .use { proxy ->
                    // Guest replies are unsigned, allowing the proxy to limit grants without
                    // forging signatures.
                    KotlinSmbClient(SmbConfig(dialects = setOf(SmbDialect.SMB_3_0)))
                        .connect(proxy.endpoint, SmbCredentials.Guest)
                        .use { share ->
                            withTimeout(5000) {
                                (1..3)
                                    .map { index ->
                                        async {
                                            val path = SmbPath.Root.child("large$index")
                                            val data =
                                                ByteArray(1_100_000) { (it * index).toByte() }
                                            share.openFile(path, SmbOpenMode.CREATE_NEW).use { file
                                                ->
                                                file.write(0, data)
                                            }
                                            val actual =
                                                share
                                                    .readChunks(path, chunkSize = 1_048_576)
                                                    .toList()
                                                    .fold(byteArrayOf()) { bytes, chunk ->
                                                        bytes + chunk
                                                    }
                                            assertArrayEquals(data, actual)
                                        }
                                    }
                                    .awaitAll()
                            }
                            assertTrue(sawTransfer.get())
                            assertFalse(oversized.get())
                        }
                }
        }
    }

    @Test
    fun `guest cannot satisfy required signing or encryption`() = runBlocking {
        SambaServer().use { server ->
            server.start()
            for (config in
                listOf(SmbConfig(requireSigning = true), SmbConfig(requireEncryption = true))) {
                try {
                    KotlinSmbClient(config)
                        .connect(server.endpoint("public"), SmbCredentials.Guest)
                        .close()
                    fail<Unit>("Guest must not bypass security policy")
                } catch (error: SmbException) {
                    assertEquals(SmbException.Kind.AUTHENTICATION, error.kind)
                }
            }
        }
    }

    @Test
    fun `unrequested oplock notifications are rejected`() = runBlocking {
        SambaServer().use { server ->
            server.start()
            SmbProxy(
                    server.endpoint("private"),
                    downstream = { frame ->
                        if (Wire(frame).u16(12) == 5) {
                            frame.put16(12, 18)
                            frame.put64(24, -1)
                        }
                        listOf(frame)
                    },
                )
                .use { proxy ->
                    KotlinSmbClient().connect(proxy.endpoint, password()).use { share ->
                        assertProtocolFailure { share.stat(SmbPath.Root) }
                        assertFalse(share.connected)
                    }
                }
        }
    }

    @Test
    fun `pending response does not extend request deadline`() = runBlocking {
        SambaServer().use { server ->
            server.start()
            SmbProxy(
                    server.endpoint("public"),
                    downstream = { frame ->
                        if (Wire(frame).u16(12) == 8) {
                            listOf(
                                frame.copyOf(64).also {
                                    it.put32(8, 0x103)
                                    it.put32(16, 3)
                                    it.put64(32, 123)
                                }
                            )
                        } else listOf(frame)
                    },
                )
                .use { proxy ->
                    KotlinSmbClient(SmbConfig(requestTimeout = 500.milliseconds))
                        .connect(proxy.endpoint, SmbCredentials.Guest)
                        .use { share ->
                            share
                                .openFile(SmbPath.Root.child("pending"), SmbOpenMode.CREATE_NEW)
                                .use { file ->
                                    file.write(0, byteArrayOf(1))
                                    withTimeout(3000) {
                                        try {
                                            file.read(0, ByteArray(1))
                                            fail<Unit>("Pending response must time out")
                                        } catch (error: SmbException) {
                                            assertEquals(SmbException.Kind.TRANSPORT, error.kind)
                                        }
                                    }
                                    assertFalse(share.connected)
                                }
                        }
                }
        }
    }

    @Test
    fun `blocking read interruption releases socket and fails request`() = runBlocking {
        SambaServer().use { server ->
            server.start()
            val sent = CountDownLatch(1)
            SmbProxy(
                    server.endpoint("private"),
                    upstream = { frame ->
                        if (Wire(frame).u16(12) == 8) {
                            sent.countDown()
                            emptyList()
                        } else listOf(frame)
                    },
                )
                .use { proxy ->
                    KotlinSmbClient().connect(proxy.endpoint, password()).use { share ->
                        share
                            .openFile(SmbPath.Root.child("interrupt"), SmbOpenMode.CREATE_NEW)
                            .use { file ->
                                val result = java.util.concurrent.CompletableFuture<Throwable>()
                                val thread = Thread {
                                    try {
                                        file.blocking().read(0, ByteArray(1))
                                        result.complete(AssertionError("Read completed"))
                                    } catch (error: Throwable) {
                                        result.complete(error)
                                    }
                                }
                                    .apply {
                                        isDaemon = true
                                        start()
                                    }
                                try {
                                    assertTrue(
                                        withContext(Dispatchers.IO) {
                                            sent.await(3, TimeUnit.SECONDS)
                                        }
                                    )
                                    thread.interrupt()
                                    val error =
                                        withContext(Dispatchers.IO) {
                                            result.get(3, TimeUnit.SECONDS)
                                        }
                                    assertEquals(
                                        SmbException.Kind.TRANSPORT,
                                        (error as SmbException).kind,
                                    )
                                    assertFalse(share.connected)
                                } finally {
                                    thread.interrupt()
                                    withContext(Dispatchers.IO) { thread.join(3000) }
                                }
                            }
                    }
                }
        }
    }

    private suspend fun assertProtocolFailure(block: suspend () -> Unit) {
        try {
            block()
            fail<Unit>("Expected signature verification failure")
        } catch (error: SmbException) {
            assertEquals(SmbException.Kind.PROTOCOL, error.kind)
        }
    }
}

private class SmbProxy(
    target: SmbEndpoint,
    upstream: (ByteArray) -> List<ByteArray> = { listOf(it) },
    downstream: (ByteArray) -> List<ByteArray> = { listOf(it) },
) : Closeable {
    private val listener = ServerSocket(0, 1, InetAddress.getLoopbackAddress())
    private val sockets = java.util.concurrent.CopyOnWriteArrayList<Socket>()
    private val workers = java.util.concurrent.CopyOnWriteArrayList<Thread>()
    val endpoint = SmbEndpoint("127.0.0.1", target.share, listener.localPort)

    init {
        worker {
            try {
                val client = listener.accept().apply { tcpNoDelay = true }.also(sockets::add)
                val server = Socket(target.host, target.port).apply { tcpNoDelay = true }.also(sockets::add)
                worker { relay(client, server, upstream) }
                worker { relay(server, client, downstream) }
            } catch (_: java.io.IOException) {}
        }
    }

    private fun worker(block: () -> Unit) {
        Thread(block, "smb-test-proxy").apply {
            isDaemon = true
            workers.add(this)
            start()
        }
    }

    private fun relay(source: Socket, target: Socket, transform: (ByteArray) -> List<ByteArray>) {
        try {
            val input = DataInputStream(source.getInputStream())
            val output = DataOutputStream(target.getOutputStream())
            while (!source.isClosed) {
                val length = input.readInt()
                check(length in 64..2_000_000)
                val packet = ByteArray(length).also(input::readFully)
                for (frame in transform(packet)) {
                    output.writeInt(frame.size)
                    output.write(frame)
                    output.flush()
                }
            }
        } catch (_: java.io.IOException) {}
    }

    override fun close() {
        listener.close()
        sockets.forEach { runCatching { it.close() } }
        workers.forEach { it.join(3000) }
    }
}
