package eu.darken.smb.protocol

/** A named pipe opened on the IPC$ tree of [connection]. */
internal class NamedPipe private constructor(
    private val connection: Connection,
    private val id: ByteArray,
) : PipeTransport {
    /** Single-credit request size, valid on every dialect including SMB 2.0.2. */
    val maxChunk: Int
        get() = minOf(PIPE_CHUNK_LIMIT, connection.maxTransact, connection.maxRead)

    override suspend fun transceive(input: ByteArray, maxOutput: Int): PipeChunk {
        require(maxOutput in 1..maxChunk && input.size <= maxChunk)
        val body =
            Packet()
                .u16(57)
                .u16(0)
                .u32(FSCTL_PIPE_TRANSCEIVE)
                .bytes(id)
                .u32(120)
                .u32(input.size.toLong())
                .u32(0)
                .u32(0)
                .u32(0)
                .u32(maxOutput.toLong())
                .u32(1)
                .u32(0)
                .bytes(input)
                .build()
        val response =
            Wire(connection.request(11, body, setOf(Connection.STATUS_BUFFER_OVERFLOW)))
        checkIoctlResponse(response)
        protocolCheck(
            response.u32(68) == FSCTL_PIPE_TRANSCEIVE && response.bytes(72, 16).contentEquals(id),
            "Mismatched pipe transceive response",
        )
        val count = response.int32(100)
        protocolCheck(count <= maxOutput, "Pipe transceive exceeds the requested size")
        val data = if (count == 0) ByteArray(0) else response.bytes(response.int32(96), count)
        return PipeChunk(data, response.u32(8) == Connection.STATUS_BUFFER_OVERFLOW)
    }

    override suspend fun read(maxLength: Int): PipeChunk {
        require(maxLength in 1..maxChunk)
        val body =
            Packet()
                .u16(49)
                .zeros(2)
                .u32(maxLength.toLong())
                .i64(0)
                .bytes(id)
                .u32(0)
                .zeros(13)
                .build()
        val response = Wire(connection.request(8, body, setOf(Connection.STATUS_BUFFER_OVERFLOW)))
        protocolCheck(response.u16(64) == 17, "Invalid read response")
        val count = response.int32(68)
        protocolCheck(count <= maxLength, "Pipe read exceeds the requested size")
        return PipeChunk(
            response.bytes(response.u8(66), count),
            response.u32(8) == Connection.STATUS_BUFFER_OVERFLOW,
        )
    }

    suspend fun close() {
        if (!connection.connected) return
        val response = Wire(connection.request(6, Packet().u16(24).zeros(6).bytes(id).build()))
        protocolCheck(response.u16(64) == 60, "Invalid close response")
    }

    companion object {
        private const val FSCTL_PIPE_TRANSCEIVE = 0x0011C017L

        suspend fun open(connection: Connection, name: String): NamedPipe {
            val wireName = name.utf16()
            val body =
                Packet()
                    .u16(57)
                    .u8(0)
                    .u8(0)
                    .u32(2)
                    .zeros(16)
                    // Read/write data and attributes, read control, synchronize.
                    .u32(0x0012019F)
                    .u32(0)
                    .u32(3)
                    .u32(1)
                    .u32(0)
                    .u16(120)
                    .u16(wireName.size)
                    .zeros(8)
                    .bytes(wireName)
                    .build()
            val response = Wire(connection.request(5, body))
            protocolCheck(response.u16(64) == 89, "Invalid create response")
            return NamedPipe(connection, response.bytes(128, 16))
        }
    }
}
