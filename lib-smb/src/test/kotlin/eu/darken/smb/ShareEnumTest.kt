package eu.darken.smb

import eu.darken.smb.protocol.Packet
import eu.darken.smb.protocol.PipeChunk
import eu.darken.smb.protocol.PipeTransport
import eu.darken.smb.protocol.ShareEnumerator
import eu.darken.smb.protocol.Wire
import eu.darken.smb.protocol.checkIoctlResponse
import eu.darken.smb.protocol.decodeShareEnumResponse
import eu.darken.smb.protocol.encodeBind
import eu.darken.smb.protocol.encodeShareEnumRequest
import eu.darken.smb.protocol.put16
import eu.darken.smb.protocol.put32
import eu.darken.smb.protocol.utf16
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

internal class ShareEnumTest {
    private class Share(
        val name: String?,
        val type: Long = 0,
        val remark: String? = "",
        val nameOverride: ((Packet) -> Unit)? = null,
    )

    private class Page(
        val shares: List<Share>,
        val resume: Long? = 0,
        val status: Long = 0,
        val countOverride: Long? = null,
        val arrayCountOverride: Long? = null,
    )

    private fun Packet.ndrString(value: String): Packet = apply {
        val chars = value.length + 1
        u32(chars.toLong()).u32(0).u32(chars.toLong()).bytes("$value\u0000".utf16()).align(4)
    }

    private fun stub(page: Page): ByteArray {
        val packet = Packet().u32(1).u32(1).u32(0x20000)
        packet.u32(page.countOverride ?: page.shares.size.toLong())
        packet.u32(if (page.shares.isEmpty()) 0 else 0x20004)
        if (page.shares.isNotEmpty()) {
            packet.u32(page.arrayCountOverride ?: page.shares.size.toLong())
            page.shares.forEachIndexed { index, share ->
                packet
                    .u32(if (share.name == null) 0 else 0x20008L + index * 8)
                    .u32(share.type)
                    .u32(if (share.remark == null) 0 else 0x2000cL + index * 8)
            }
            for (share in page.shares) {
                share.nameOverride?.invoke(packet) ?: share.name?.let { packet.ndrString(it) }
                share.remark?.let { packet.ndrString(it) }
            }
        }
        packet.u32(page.shares.size.toLong())
        if (page.resume == null) packet.u32(0) else packet.u32(0x30000).u32(page.resume)
        return packet.u32(page.status).build()
    }

    private fun pdu(ptype: Int, flags: Int, callId: Long, body: Packet.() -> Unit): ByteArray =
        Packet()
            .u8(5)
            .u8(0)
            .u8(ptype)
            .u8(flags)
            .u32(0x10)
            .u16(0)
            .u16(0)
            .u32(callId)
            .apply(body)
            .build()
            .also { it.put16(8, it.size) }

    private fun bindAck(callId: Long = 1, result: Int = 0, ptype: Int = 12): ByteArray =
        pdu(ptype, 3, callId) {
            u16(4280).u16(4280).u32(0x1234).u16(4).bytes("135\u0000".toByteArray()).align(4)
            u8(1).u8(0).u16(0).u16(result).u16(0)
            u32(0x8a885d04L).u16(0x1ceb).u16(0x11c9)
            bytes(byteArrayOf(0x9f.toByte(), 0xe8.toByte(), 8, 0, 0x2b, 0x10, 0x48, 0x60))
            u32(2)
        }

    private fun response(
        stub: ByteArray,
        callId: Long,
        flags: Int = 3,
        context: Int = 0,
    ): ByteArray =
        pdu(2, flags, callId) {
            u32(stub.size.toLong()).u16(context).u8(0).u8(0)
            bytes(stub)
        }

    private fun fault(callId: Long, status: Long): ByteArray =
        pdu(3, 3, callId) { u32(0).u16(0).u8(0).u8(0).u32(status).u32(0) }

    private fun fragments(stub: ByteArray, callId: Long, size: Int): List<ByteArray> {
        val parts =
            stub.toList().chunked(size).map { it.toByteArray() }.ifEmpty { listOf(ByteArray(0)) }
        return parts.mapIndexed { index, part ->
            val flags = (if (index == 0) 1 else 0) or (if (index == parts.lastIndex) 2 else 0)
            response(part, callId, flags)
        }
    }

    /** Delivers each message as its own pipe message, split into overflowing chunks. */
    private fun messages(vararg messages: ByteArray, chunk: Int = 65536): List<PipeChunk> =
        messages.flatMap { message ->
            val parts = message.toList().chunked(chunk).map { it.toByteArray() }
            parts.mapIndexed { index, part -> PipeChunk(part, index < parts.lastIndex) }
        }

    private class FakePipe(private val handler: FakePipe.(ByteArray) -> List<PipeChunk>) :
        PipeTransport {
        val requests = mutableListOf<ByteArray>()
        val queue = ArrayDeque<PipeChunk>()
        var reads = 0
        var reader: (() -> PipeChunk)? = null

        override suspend fun transceive(input: ByteArray, maxOutput: Int): PipeChunk {
            assertTrue(maxOutput <= 65536)
            assertTrue(queue.isEmpty())
            requests += input
            queue.addAll(handler(input))
            return next(maxOutput)
        }

        override suspend fun read(maxLength: Int): PipeChunk {
            assertTrue(maxLength <= 65536)
            reads++
            reader?.let { return it() }
            return next(maxLength)
        }

        private fun next(limit: Int): PipeChunk {
            val chunk = queue.removeFirstOrNull() ?: PipeChunk(ByteArray(0), false)
            assertTrue(chunk.data.size <= limit)
            return chunk
        }
    }

    private fun Wire.callId() = u32(12)

    private fun Wire.resume() = u32(size - 4)

    private fun page(resume: Long, reply: (callId: Long) -> List<ByteArray>) = resume to reply

    /** Serves each page by the request's resume handle, one pipe message per PDU. */
    private fun server(vararg pages: Pair<Long, (Long) -> List<ByteArray>>): FakePipe {
        val replies = pages.toMap()
        return FakePipe { request ->
            val wire = Wire(request)
            if (wire.u8(2) == 11) messages(bindAck(wire.callId()))
            else messages(*replies.getValue(wire.resume())(wire.callId()).toTypedArray())
        }
    }

    private fun single(page: Page) = server(page(0) { id -> listOf(response(stub(page), id)) })

    private fun list(pipe: PipeTransport): List<SmbShareInfo> = runBlocking {
        ShareEnumerator(pipe, "nas").list()
    }

    private fun assertProtocol(pipe: PipeTransport) = assertKind(SmbException.Kind.PROTOCOL, pipe)

    private fun assertKind(kind: SmbException.Kind, pipe: PipeTransport): SmbException {
        val error = assertThrows(SmbException::class.java) { list(pipe) }
        assertEquals(kind, error.kind, error.message)
        return error
    }

    private val sample =
        listOf(
            Share("public", 0, "Public files"),
            Share("IPC$", 0x80000003L, "IPC Service"),
            Share("printer", 1, null),
            Share("device", 2),
            Share("temp", 0x40000000L),
            Share("odd", 7),
        )

    private val expected =
        listOf(
            SmbShareInfo("public", SmbShareInfo.Type.DISK, false, "Public files"),
            SmbShareInfo("IPC$", SmbShareInfo.Type.IPC, true, "IPC Service"),
            SmbShareInfo("printer", SmbShareInfo.Type.PRINTER, false, ""),
            SmbShareInfo("device", SmbShareInfo.Type.DEVICE, false, ""),
            SmbShareInfo("temp", SmbShareInfo.Type.DISK, false, ""),
            SmbShareInfo("odd", SmbShareInfo.Type.OTHER, false, ""),
        )

    @Test
    fun `bind requests the srvsvc interface over NDR`() {
        val wire = Wire(encodeBind(1))
        assertEquals(5, wire.u8(0))
        assertEquals(0, wire.u8(1))
        assertEquals(11, wire.u8(2))
        assertEquals(3, wire.u8(3))
        assertEquals(0x10L, wire.u32(4))
        assertEquals(72, wire.u16(8))
        assertEquals(72, wire.size)
        assertEquals(1L, wire.callId())
        assertEquals(1, wire.u8(24))
        assertEquals(0, wire.u16(28))
        assertEquals(1, wire.u8(30))
        assertEquals("c84f324b7016d30112785a47bf6ee188", wire.bytes(32, 16).toHex())
        assertEquals(3, wire.u16(48))
        assertEquals(0, wire.u16(50))
        assertEquals("045d888aeb1cc9119fe808002b104860", wire.bytes(52, 16).toHex())
        assertEquals(2L, wire.u32(68))
    }

    @Test
    fun `share enumeration request encodes level 1 with server name and resume handle`() {
        val wire = Wire(encodeShareEnumRequest(7, "nas", 0x55))
        assertEquals(0, wire.u8(2))
        assertEquals(3, wire.u8(3))
        assertEquals(wire.size, wire.u16(8))
        assertEquals(7L, wire.callId())
        assertEquals(wire.size - 24L, wire.u32(16))
        assertEquals(0, wire.u16(20))
        assertEquals(15, wire.u16(22))
        val stub = Wire(wire.bytes(24, wire.size - 24))
        assertNotEquals(0L, stub.u32(0))
        assertEquals(6L, stub.u32(4))
        assertEquals(0L, stub.u32(8))
        assertEquals(6L, stub.u32(12))
        assertEquals("\\\\nas\u0000", stub.utf16(16, 12))
        assertEquals(1L, stub.u32(28))
        assertEquals(1L, stub.u32(32))
        assertNotEquals(0L, stub.u32(36))
        assertEquals(0L, stub.u32(40))
        assertEquals(0L, stub.u32(44))
        assertEquals(0xFFFFFFFFL, stub.u32(48))
        assertNotEquals(0L, stub.u32(52))
        assertEquals(0x55L, stub.u32(56))
        assertEquals(60, stub.size)
    }


    @Test
    fun `multi entry response decodes types special flag and comments`() {
        val page = decodeShareEnumResponse(stub(Page(sample, resume = 9)), 10_000)
        assertEquals(expected, page.shares)
        assertEquals(9L, page.resume)
        assertEquals(0L, page.status)
        assertEquals(expected, list(single(Page(sample))))
    }

    @Test
    fun `empty successful listing returns no shares`() {
        assertEquals(emptyList<SmbShareInfo>(), list(single(Page(emptyList()))))
        assertEquals(emptyList<SmbShareInfo>(), list(single(Page(emptyList(), resume = null))))
    }

    @Test
    fun `fragmented responses are reassembled across pipe messages`() {
        val body = stub(Page(sample))
        assertEquals(expected, list(server(page(0) { id -> fragments(body, id, 13) })))
        val packed = server(page(0) { id -> listOf(fragments(body, id, 40).concat()) })
        assertEquals(expected, list(packed))
    }

    @Test
    fun `responses larger than one pipe chunk are drained with bounded reads`() {
        val shares = (0 until 1200).map { Share("share$it", 0, "c".repeat(60)) }
        val body = stub(Page(shares))
        assertTrue(body.size > 3 * 65536)
        val pipe = server(page(0) { id -> listOf(fragments(body, id, 60_000).concat()) })
        val result = list(pipe)
        assertEquals(1200, result.size)
        assertEquals("share1199", result.last().name)
        assertTrue(pipe.reads >= 3)
    }

    @Test
    fun `more data pages are requested with the returned resume handle`() {
        val pipe =
            server(
                page(0) { id -> listOf(response(stub(Page(sample.take(2), 11, 234)), id)) },
                page(11) { id -> listOf(response(stub(Page(sample.drop(2), 0, 0)), id)) },
            )
        assertEquals(expected, list(pipe))
        val calls = pipe.requests.drop(1).map { Wire(it) }
        assertEquals(listOf(0L, 11L), calls.map { it.resume() })
        assertEquals(listOf(2L, 3L), calls.map { it.callId() })
    }

    @Test
    fun `resume handle that does not advance is rejected`() {
        assertProtocol(single(Page(sample, 0, 234)))
        assertProtocol(
            server(
                page(0) { id -> listOf(response(stub(Page(sample, 5, 234)), id)) },
                page(5) { id -> listOf(response(stub(Page(sample, 5, 234)), id)) },
            )
        )
        assertProtocol(single(Page(emptyList(), 3, 234)))
        assertProtocol(single(Page(sample, null, 234)))
    }

    @Test
    fun `failure on a later page fails the whole listing`() {
        assertProtocol(
            server(
                page(0) { id -> listOf(response(stub(Page(sample, 4, 234)), id)) },
                page(4) { id -> listOf(fault(id, 0x1c010003)) },
            )
        )
        assertKind(
            SmbException.Kind.ACCESS_DENIED,
            server(
                page(0) { id -> listOf(response(stub(Page(sample, 4, 234)), id)) },
                page(4) { id -> listOf(response(stub(Page(emptyList(), 0, 5)), id)) },
            ),
        )
    }

    @Test
    fun `access denied and other errors keep their kinds`() {
        assertKind(SmbException.Kind.ACCESS_DENIED, single(Page(emptyList(), status = 5)))
        val other = assertProtocol(single(Page(emptyList(), status = 124)))
        assertTrue(other.message!!.contains("124"), other.message)
        val faulted = assertProtocol(server(page(0) { id -> listOf(fault(id, 0x1c010002)) }))
        assertTrue(faulted.message!!.contains("1c010002"), faulted.message)
    }

    @Test
    fun `malformed counts and strings are rejected`() {
        val body = stub(Page(sample))
        for (bad in listOf(body.copyOf(body.size - 4), body.copyOf(60), body + ByteArray(4))) {
            assertProtocol(server(page(0) { id -> listOf(response(bad, id)) }))
        }
        val pages =
            listOf(
                Page(sample.take(1), countOverride = 1000, arrayCountOverride = 1000),
                Page(sample.take(1), arrayCountOverride = 2),
                Page(listOf(Share(null))),
                // actual > max, non-zero offset, length beyond the buffer, lone surrogate
                Page(listOf(Share("x", nameOverride = { it.u32(1).u32(0).u32(2).zeros(4) }))),
                Page(listOf(Share("x", nameOverride = { it.u32(2).u32(1).u32(2).zeros(4) }))),
                Page(
                    listOf(
                        Share("x", nameOverride = { it.u32(0x7fffffff).u32(0).u32(0x7fffffff) })
                    )
                ),
                Page(
                    listOf(
                        Share("x", nameOverride = { it.u32(2).u32(0).u32(2).u16(0xd800).u16(0) })
                    )
                ),
            )
        for (page in pages) assertProtocol(single(page))
        assertThrows(SmbException::class.java) { decodeShareEnumResponse(body, sample.size - 1) }
    }

    @Test
    fun `fragments must belong to the call and arrive in order`() {
        val body = stub(Page(sample))
        val cases: List<(Long) -> List<ByteArray>> =
            listOf(
                { id -> listOf(response(body, id + 1)) },
                { id -> listOf(response(body, id, context = 1)) },
                { id -> listOf(response(body, id, flags = 2)) },
                { id -> fragments(body, id, 50).also { it[1][3] = 1 } },
                { id -> listOf(response(body, id) + response(body, id)) },
                { id -> listOf(response(body, id).also { it.put16(10, 16) }) },
                { id -> listOf(response(body, id).also { it.put16(8, it.size + 8) }) },
                { id -> listOf(response(body, id).also { it.put16(8, 20) }) },
                { id -> listOf(response(body, id).also { it[0] = 4 }) },
                { id -> listOf(response(body, id).also { it[2] = 12 }) },
            )
        for (case in cases) assertProtocol(server(0L to case))
        assertProtocol(FakePipe { messages(bindAck(callId = 9)) })
        assertProtocol(FakePipe { messages(bindAck(result = 2)) })
        assertProtocol(FakePipe { messages(bindAck(ptype = 13)) })
        assertProtocol(FakePipe { messages(bindAck() + bindAck()) })
        assertProtocol(FakePipe { listOf(PipeChunk(bindAck(), true)) })
    }

    @Test
    fun `endless fragments and oversized aggregates are bounded`() {
        fun endless(size: Int) =
            server(page(0) { emptyList() }).apply {
                var first = true
                reader = {
                    val id = Wire(requests.last()).callId()
                    PipeChunk(response(ByteArray(size), id, flags = if (first) 1 else 0), false)
                        .also { first = false }
                }
            }
        val many = assertProtocol(endless(0))
        assertTrue(many.message!!.contains("fragments"), many.message)
        val large = assertProtocol(endless(60_000))
        assertTrue(large.message!!.contains("size limit"), large.message)
        val silent =
            server(page(0) { emptyList() }).apply { reader = { PipeChunk(ByteArray(0), false) } }
        assertProtocol(silent)
    }

    @Test
    fun `IOCTL responses are bounded by their frame`() {
        fun ioctl(
            size: Int = 49,
            offset: Int = 112,
            count: Int = 4,
            frame: Int = 116,
            inputCount: Int = 0,
        ) =
            Wire(
                ByteArray(frame).also {
                    it.put16(64, size)
                    it.put32(88, 112)
                    it.put32(92, inputCount.toLong())
                    it.put32(96, offset.toLong())
                    it.put32(100, count.toLong())
                }
            )
        checkIoctlResponse(ioctl())
        checkIoctlResponse(ioctl(count = 0, offset = 0, frame = 112))
        for (bad in
            listOf(
                ioctl(size = 9),
                ioctl(offset = 113),
                ioctl(count = 5),
                ioctl(offset = 64),
                ioctl(offset = Int.MAX_VALUE),
                ioctl(frame = 108),
                ioctl(inputCount = 8),
            )) {
            assertThrows(SmbException::class.java) { checkIoctlResponse(bad) }
        }
    }

    private fun List<ByteArray>.concat() = fold(byteArrayOf()) { all, next -> all + next }

    private fun ByteArray.toHex() = joinToString("") { "%02x".format(it) }
}
