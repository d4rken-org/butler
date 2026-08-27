package eu.darken.butler.common.files.smb

import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import javax.net.SocketFactory

/**
 * smbj's default factory connects with the OS default timeout, which on an unreachable host is
 * minutes. This bounds the connect attempt so an unplugged NAS fails the UI quickly.
 */
class SmbSocketFactory : SocketFactory() {

    override fun createSocket(host: String, port: Int): Socket = connect(InetSocketAddress(host, port))

    override fun createSocket(host: String, port: Int, localHost: InetAddress, localPort: Int): Socket =
        connect(InetSocketAddress(host, port), InetSocketAddress(localHost, localPort))

    override fun createSocket(host: InetAddress, port: Int): Socket = connect(InetSocketAddress(host, port))

    override fun createSocket(
        address: InetAddress,
        port: Int,
        localAddress: InetAddress,
        localPort: Int,
    ): Socket = connect(InetSocketAddress(address, port), InetSocketAddress(localAddress, localPort))

    private fun connect(remote: InetSocketAddress, local: InetSocketAddress? = null): Socket = Socket().apply {
        local?.let { bind(it) }
        soTimeout = SOCKET_TIMEOUT_MS
        connect(remote, CONNECT_TIMEOUT_MS)
    }

    companion object {
        const val CONNECT_TIMEOUT_MS = 10_000
        const val SOCKET_TIMEOUT_MS = 30_000
    }
}
