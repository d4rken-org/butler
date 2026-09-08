package eu.darken.smb

import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

internal class CancellationTest {
    @Test
    fun `cancelled negotiation releases the socket promptly`() = runBlocking {
        ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { server ->
            val received = CompletableFuture<Unit>()
            val eof = CompletableFuture<Int>()
            val peer = Thread {
                server.accept().use { socket ->
                    socket.soTimeout = 3000
                    val input = java.io.DataInputStream(socket.getInputStream())
                    val size = input.readInt()
                    input.readFully(ByteArray(size))
                    received.complete(Unit)
                    eof.complete(input.read())
                }
            }
                .apply {
                    isDaemon = true
                    start()
                }
            val request =
                async(Dispatchers.Default) {
                    KotlinSmbClient()
                        .connect(
                            SmbEndpoint("127.0.0.1", "share", server.localPort),
                            SmbCredentials.Guest,
                        )
                }
            withContext(Dispatchers.IO) { received.get(3, TimeUnit.SECONDS) }
            request.cancelAndJoin()
            assertEquals(-1, withContext(Dispatchers.IO) { eof.get(3, TimeUnit.SECONDS) })
            peer.join(3000)
        }
    }

    @Test
    fun `resource cleanup survives cancellation and preserves original failure`() = runBlocking {
        val original = IllegalStateException("body")
        val closeFailure = IllegalStateException("close")
        var closed = false
        val resource =
            object : SmbResource {
                override suspend fun close() {
                    delay(1)
                    closed = true
                    throw closeFailure
                }
            }
        try {
            resource.use { throw original }
        } catch (error: IllegalStateException) {
            assertSame(original, error)
            assertArrayEquals(arrayOf(closeFailure), error.suppressed)
        }
        assertTrue(closed)
        var cancelledClose = false
        withTimeoutOrNull(25.milliseconds) {
            object : SmbResource {
                    override suspend fun close() {
                        delay(1)
                        cancelledClose = true
                    }
                }
                .use { awaitCancellation() }
        }
        assertTrue(cancelledClose)
    }
}
