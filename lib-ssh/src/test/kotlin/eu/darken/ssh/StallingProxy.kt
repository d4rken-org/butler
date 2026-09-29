package eu.darken.ssh

import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread

/** Forwards TCP to [targetHost]; while [stalled], server-to-client bytes are held back. */
internal class StallingProxy(private val targetHost: String, private val targetPort: Int) :
    AutoCloseable {
    private val listener = ServerSocket(0, 50, InetAddress.getLoopbackAddress())
    private val sockets = CopyOnWriteArrayList<Socket>()

    @Volatile var stalled: Boolean = false

    val endpoint: SftpEndpoint
        get() = SftpEndpoint(listener.inetAddress.hostAddress, listener.localPort)

    init {
        thread(isDaemon = true, name = "stalling-proxy") {
            while (!listener.isClosed) {
                val client = runCatching { listener.accept() }.getOrNull() ?: break
                val upstream = Socket(targetHost, targetPort)
                sockets += client
                sockets += upstream
                pump(client.getInputStream(), upstream.getOutputStream(), gated = false)
                pump(upstream.getInputStream(), client.getOutputStream(), gated = true)
            }
        }
    }

    private fun pump(input: InputStream, output: OutputStream, gated: Boolean) =
        thread(isDaemon = true, name = "stalling-proxy-pump") {
            val buffer = ByteArray(64 * 1024)
            try {
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    while (gated && stalled && !listener.isClosed) Thread.sleep(10)
                    output.write(buffer, 0, count)
                    output.flush()
                }
            } catch (_: Exception) {
            } finally {
                runCatching { output.close() }
                runCatching { input.close() }
            }
        }

    override fun close() {
        listener.close()
        sockets.forEach { runCatching { it.close() } }
    }
}
