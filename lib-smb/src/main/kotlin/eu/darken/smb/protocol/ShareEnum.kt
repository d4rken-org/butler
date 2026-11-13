package eu.darken.smb.protocol

import eu.darken.smb.SmbException
import eu.darken.smb.SmbShareInfo
import java.io.ByteArrayOutputStream

/** One pipe read result; [more] reports STATUS_BUFFER_OVERFLOW, i.e. the message continues. */
internal class PipeChunk(val data: ByteArray, val more: Boolean)

/** A message-mode named pipe carrying DCE/RPC PDUs. */
internal interface PipeTransport {
    suspend fun transceive(input: ByteArray, maxOutput: Int): PipeChunk

    suspend fun read(maxLength: Int): PipeChunk
}

internal const val PIPE_CHUNK_LIMIT = 65536
internal const val RPC_RESPONSE_BYTE_LIMIT = 4 * 1024 * 1024
internal const val RPC_FRAGMENT_LIMIT = 256
internal const val SHARE_ENTRY_LIMIT = 10_000
internal const val SHARE_PAGE_LIMIT = 64
internal const val RPC_FRAGMENT_SIZE = 4280

private const val PTYPE_REQUEST = 0
private const val PTYPE_RESPONSE = 2
private const val PTYPE_FAULT = 3
private const val PTYPE_BIND = 11
private const val PTYPE_BIND_ACK = 12
private const val PTYPE_BIND_NAK = 13
private const val FIRST_FRAGMENT = 1
private const val LAST_FRAGMENT = 2
private const val BIND_CALL_ID = 1L
private const val OPNUM_NETR_SHARE_ENUM = 15
private const val ERROR_ACCESS_DENIED = 5L
private const val ERROR_MORE_DATA = 234L

private val SRVSVC_SYNTAX = dceUuid("4b324fc8-1670-01d3-1278-5a47bf6ee188")
private val NDR_SYNTAX = dceUuid("8a885d04-1ceb-11c9-9fe8-08002b104860")

/** UUIDs on the wire store their first three fields little-endian. */
private fun dceUuid(text: String): ByteArray {
    val raw = text.replace("-", "").chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    return raw.copyOfRange(0, 4).reversedArray() +
        raw.copyOfRange(4, 6).reversedArray() +
        raw.copyOfRange(6, 8).reversedArray() +
        raw.copyOfRange(8, 16)
}

private fun Packet.rpcHeader(ptype: Int, callId: Long): Packet =
    u8(5).u8(0).u8(ptype).u8(FIRST_FRAGMENT or LAST_FRAGMENT).u32(0x10).u16(0).u16(0).u32(callId)

private fun ByteArray.withFragmentLength(): ByteArray = also { it.put16(8, it.size) }

internal fun encodeBind(callId: Long = BIND_CALL_ID): ByteArray =
    Packet()
        .rpcHeader(PTYPE_BIND, callId)
        .u16(RPC_FRAGMENT_SIZE)
        .u16(RPC_FRAGMENT_SIZE)
        .u32(0)
        .u8(1)
        .u8(0)
        .u16(0)
        .u16(0)
        .u8(1)
        .u8(0)
        .bytes(SRVSVC_SYNTAX)
        .u16(3)
        .u16(0)
        .bytes(NDR_SYNTAX)
        .u32(2)
        .build()
        .withFragmentLength()

/** NetrShareEnum, level 1, with a resume handle and no preferred size limit. */
internal fun encodeShareEnumRequest(callId: Long, host: String, resume: Long): ByteArray {
    val server = "\\\\$host\u0000"
    val stub =
        Packet()
            .u32(0x00020000)
            .u32(server.length.toLong())
            .u32(0)
            .u32(server.length.toLong())
            .bytes(server.utf16())
            .align(4)
            .u32(1)
            .u32(1)
            .u32(0x00020004)
            .u32(0)
            .u32(0)
            .u32(0xFFFFFFFFL)
            .u32(0x00020008)
            .u32(resume)
            .build()
    return Packet()
        .rpcHeader(PTYPE_REQUEST, callId)
        .u32(stub.size.toLong())
        .u16(0)
        .u16(OPNUM_NETR_SHARE_ENUM)
        .bytes(stub)
        .build()
        .withFragmentLength()
}

private fun checkRpcHeader(wire: Wire, callId: Long) {
    protocolCheck(wire.size >= 16, "Truncated RPC fragment")
    protocolCheck(wire.u8(0) == 5 && wire.u8(1) == 0, "Unsupported RPC version")
    protocolCheck(wire.u8(4) == 0x10, "Unsupported RPC data representation")
    protocolCheck(wire.u16(8) == wire.size, "RPC fragment length mismatch")
    protocolCheck(wire.u16(10) == 0, "Unexpected RPC authentication")
    protocolCheck(wire.u32(12) == callId, "Mismatched RPC call id")
}

/** Returns the server's maximum receive fragment size. */
internal fun decodeBindAck(fragment: ByteArray, callId: Long = BIND_CALL_ID): Int {
    val wire = Wire(fragment)
    checkRpcHeader(wire, callId)
    if (wire.u8(2) == PTYPE_BIND_NAK)
        throw SmbException(SmbException.Kind.PROTOCOL, message = "RPC bind rejected")
    protocolCheck(wire.u8(2) == PTYPE_BIND_ACK, "Unexpected RPC bind response")
    protocolCheck(
        wire.u8(3) and FIRST_FRAGMENT != 0 && wire.u8(3) and LAST_FRAGMENT != 0,
        "Fragmented RPC bind response",
    )
    val maxReceive = wire.u16(18)
    val secondaryAddress = wire.u16(24)
    val results = (26 + secondaryAddress + 3) and -4
    protocolCheck(wire.u8(results) >= 1, "RPC bind returned no results")
    protocolCheck(wire.u16(results + 4) == 0, "RPC bind did not accept the srvsvc syntax")
    protocolCheck(
        wire.bytes(results + 8, 16).contentEquals(NDR_SYNTAX) && wire.u32(results + 24) == 2L,
        "RPC bind accepted an unexpected transfer syntax",
    )
    protocolCheck(maxReceive >= 24, "Invalid RPC fragment size")
    return maxReceive
}

internal class RpcResponseFragment(val stub: ByteArray, val last: Boolean)

internal fun decodeResponseFragment(
    fragment: ByteArray,
    callId: Long,
    first: Boolean,
): RpcResponseFragment {
    val wire = Wire(fragment)
    checkRpcHeader(wire, callId)
    protocolCheck(wire.size >= 24, "Truncated RPC response")
    if (wire.u8(2) == PTYPE_FAULT) {
        val status = wire.u32(24)
        throw SmbException(
            SmbException.Kind.PROTOCOL,
            message = "RPC fault 0x${status.toString(16)}",
        )
    }
    protocolCheck(wire.u8(2) == PTYPE_RESPONSE, "Unexpected RPC response type")
    protocolCheck((wire.u8(3) and FIRST_FRAGMENT != 0) == first, "Out of sequence RPC fragment")
    protocolCheck(wire.u16(20) == 0, "Mismatched RPC context id")
    return RpcResponseFragment(wire.bytes(24, wire.size - 24), wire.u8(3) and LAST_FRAGMENT != 0)
}

internal class ShareEnumPage(val shares: List<SmbShareInfo>, val resume: Long?, val status: Long)

private class NdrReader(bytes: ByteArray) {
    private val wire = Wire(bytes)
    private var offset = 0
    val remaining: Int
        get() = wire.size - offset

    fun u32(): Long {
        val padding = (4 - offset % 4) % 4
        protocolCheck(padding <= remaining, "Truncated NDR data")
        offset += padding
        return wire.u32(offset).also { offset += 4 }
    }

    fun string(): String {
        val max = u32()
        val start = u32()
        val actual = u32()
        protocolCheck(start == 0L && actual <= max, "Invalid NDR string bounds")
        protocolCheck(actual * 2 <= remaining, "Truncated NDR string")
        val length = (actual * 2).toInt()
        val text = wire.utf16(offset, length)
        offset += length
        return text.removeSuffix("\u0000")
    }
}

private fun shareType(type: Long): SmbShareInfo.Type =
    when (type and 0x0FFFFFFF) {
        0L -> SmbShareInfo.Type.DISK
        1L -> SmbShareInfo.Type.PRINTER
        2L -> SmbShareInfo.Type.DEVICE
        3L -> SmbShareInfo.Type.IPC
        else -> SmbShareInfo.Type.OTHER
    }

/** Decodes a NetrShareEnum level 1 response stub; [maxEntries] bounds the share array. */
internal fun decodeShareEnumResponse(stub: ByteArray, maxEntries: Int): ShareEnumPage {
    val reader = NdrReader(stub)
    protocolCheck(reader.u32() == 1L && reader.u32() == 1L, "Unexpected share information level")
    val shares =
        if (reader.u32() == 0L) emptyList()
        else {
            val count = reader.u32()
            val buffer = reader.u32()
            if (buffer == 0L) {
                protocolCheck(count == 0L, "Missing share array")
                emptyList()
            } else {
                protocolCheck(count <= maxEntries, "Share enumeration exceeds the entry limit")
                protocolCheck(reader.u32() == count, "Share array size mismatch")
                protocolCheck(count * 12 <= reader.remaining, "Truncated share array")
                val headers =
                    List(count.toInt()) { Triple(reader.u32(), reader.u32(), reader.u32()) }
                headers.map { (name, type, remark) ->
                    protocolCheck(name != 0L, "Share without a name")
                    SmbShareInfo(
                        name = reader.string(),
                        type = shareType(type),
                        special = type and 0x80000000L != 0L,
                        comment = if (remark == 0L) "" else reader.string(),
                    )
                }
            }
        }
    reader.u32()
    val resume = if (reader.u32() == 0L) null else reader.u32()
    val status = reader.u32()
    protocolCheck(reader.remaining == 0, "Unexpected data after share enumeration")
    return ShareEnumPage(shares, resume, status)
}

/** Splits pipe output into RPC fragments, charging every received byte to [budget]. */
private class FragmentReader(
    private val transport: PipeTransport,
    private val maxChunk: Int,
    private val budget: ShareEnumerator.Budget,
    first: PipeChunk,
) {
    private var pending = ByteArray(0)
    private var more = false

    init {
        accept(first)
    }

    private fun accept(chunk: PipeChunk) {
        protocolCheck(chunk.data.size <= maxChunk, "RPC pipe returned an oversized chunk")
        protocolCheck(
            chunk.data.size <= RPC_RESPONSE_BYTE_LIMIT - budget.bytes,
            "RPC response exceeds the size limit",
        )
        budget.bytes += chunk.data.size
        pending += chunk.data
        more = chunk.more
    }

    private suspend fun fill() {
        val chunk = transport.read(maxChunk)
        protocolCheck(chunk.data.isNotEmpty(), "RPC pipe returned no data")
        accept(chunk)
    }

    suspend fun next(): ByteArray {
        while (pending.size < 16) fill()
        val length = Wire(pending).u16(8)
        protocolCheck(length >= 16, "Invalid RPC fragment length")
        while (pending.size < length) fill()
        return pending.copyOf(length).also { pending = pending.copyOfRange(length, pending.size) }
    }

    fun finish() {
        protocolCheck(pending.isEmpty() && !more, "Unexpected data after the final RPC fragment")
    }
}

/** srvsvc client over an already opened pipe; one instance per enumeration. */
internal class ShareEnumerator(
    private val transport: PipeTransport,
    private val host: String,
    private val maxChunk: Int = PIPE_CHUNK_LIMIT,
) {
    class Budget {
        var bytes = 0
    }

    private val budget = Budget()

    init {
        require(maxChunk in 1..PIPE_CHUNK_LIMIT)
    }

    private suspend fun exchange(request: ByteArray): FragmentReader {
        protocolCheck(request.size <= maxChunk, "RPC request exceeds the pipe limit")
        return FragmentReader(transport, maxChunk, budget, transport.transceive(request, maxChunk))
    }

    private suspend fun call(callId: Long, request: ByteArray): ByteArray {
        val reader = exchange(request)
        val stub = ByteArrayOutputStream()
        var index = 0
        while (true) {
            protocolCheck(index < RPC_FRAGMENT_LIMIT, "RPC response has too many fragments")
            val fragment = decodeResponseFragment(reader.next(), callId, first = index == 0)
            stub.write(fragment.stub)
            index++
            if (fragment.last) break
        }
        reader.finish()
        return stub.toByteArray()
    }

    suspend fun list(): List<SmbShareInfo> {
        val bindReader = exchange(encodeBind(BIND_CALL_ID))
        val serverMaxReceive = decodeBindAck(bindReader.next(), BIND_CALL_ID)
        bindReader.finish()
        val shares = mutableListOf<SmbShareInfo>()
        var resume = 0L
        for (page in 0 until SHARE_PAGE_LIMIT) {
            val callId = BIND_CALL_ID + 1 + page
            val request = encodeShareEnumRequest(callId, host, resume)
            protocolCheck(
                request.size <= minOf(serverMaxReceive, RPC_FRAGMENT_SIZE),
                "Server name does not fit into one RPC fragment",
            )
            val result =
                decodeShareEnumResponse(call(callId, request), SHARE_ENTRY_LIMIT - shares.size)
            when (result.status) {
                0L -> return shares + result.shares
                ERROR_MORE_DATA -> {
                    val next = result.resume
                    protocolCheck(
                        next != null && next != resume && result.shares.isNotEmpty(),
                        "Share enumeration made no progress",
                    )
                    shares += result.shares
                    resume = next!!
                }
                ERROR_ACCESS_DENIED ->
                    throw SmbException(
                        SmbException.Kind.ACCESS_DENIED,
                        message = "Server denied share enumeration",
                    )
                else ->
                    throw SmbException(
                        SmbException.Kind.PROTOCOL,
                        message = "Share enumeration failed with WERROR ${result.status}",
                    )
            }
        }
        throw SmbException(
            SmbException.Kind.PROTOCOL,
            message = "Share enumeration exceeded $SHARE_PAGE_LIMIT pages",
        )
    }
}
