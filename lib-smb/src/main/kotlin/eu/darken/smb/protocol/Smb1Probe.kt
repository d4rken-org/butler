package eu.darken.smb.protocol

import eu.darken.smb.SmbConfig
import eu.darken.smb.SmbEndpoint
import java.net.InetSocketAddress
import java.net.Socket
import kotlin.coroutines.resume
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

/** Negotiate only, used after an SMB2 negotiate was hung up on. Never authenticates over SMB1. */
internal suspend fun isSmb1Only(endpoint: SmbEndpoint, config: SmbConfig): Boolean =
    withContext(Dispatchers.IO) {
        suspendCancellableCoroutine { continuation ->
            val socket = Socket()
            continuation.invokeOnCancellation { runCatching { socket.close() } }
            val result =
                try {
                    socket.use {
                        socket.connect(
                            InetSocketAddress(endpoint.host, endpoint.port),
                            config.connectTimeout.inWholeMilliseconds.toInt(),
                        )
                        socket.soTimeout = config.requestTimeout.inWholeMilliseconds.toInt()
                        val dialects =
                            byteArrayOf(2) +
                                "NT LM 0.12\u0000".toByteArray() +
                                byteArrayOf(2) +
                                "SMB 2.002\u0000".toByteArray() +
                                byteArrayOf(2) +
                                "SMB 2.???\u0000".toByteArray()
                        val header =
                            byteArrayOf(0xff.toByte(), 0x53, 0x4d, 0x42, 0x72) + ByteArray(27)
                        header[9] = 0x18
                        header.put16(10, 0xc801)
                        val packet =
                            header + Packet().u8(0).u16(dialects.size).bytes(dialects).build()
                        socket
                            .getOutputStream()
                            .write(
                                byteArrayOf(
                                    0,
                                    0,
                                    (packet.size ushr 8).toByte(),
                                    packet.size.toByte(),
                                ) + packet
                            )
                        val input = java.io.DataInputStream(socket.getInputStream())
                        val frame = input.readInt()
                        if (frame !in 35..131072) false
                        else {
                            val bytes = ByteArray(frame).also(input::readFully)
                            bytes[0] == 0xff.toByte() &&
                                bytes[4] == 0x72.toByte() &&
                                Wire(bytes).u32(5) == 0L &&
                                Wire(bytes).u16(33) == 0
                        }
                    }
                } catch (_: Exception) {
                    false
                }
            continuation.resume(result)
        }
    }
