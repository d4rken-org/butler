package eu.darken.smb.protocol

import eu.darken.smb.SmbBlockingFile
import eu.darken.smb.SmbCapacity
import eu.darken.smb.SmbDialect
import eu.darken.smb.SmbEntry
import eu.darken.smb.SmbFile
import eu.darken.smb.SmbFileType
import eu.darken.smb.SmbOpenMode
import eu.darken.smb.SmbPath
import eu.darken.smb.SmbResource
import eu.darken.smb.SmbShare
import eu.darken.smb.use
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Instant
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList

internal class NativeShare(private val connection: Connection) : SmbShare {
    override val connected: Boolean
        get() = connection.connected

    override val dialect: SmbDialect
        get() = connection.dialect

    override suspend fun stat(path: SmbPath): SmbEntry = open(path, access = 0x80).use { it.entry }

    override fun list(path: SmbPath): Flow<SmbEntry> = flow {
        open(path, access = 0x81, options = 1).use { directory ->
            var restart = true
            var previous: ByteArray? = null
            while (true) {
                currentCoroutineContext().ensureActive()
                val pattern = "*".utf16()
                val request =
                    Packet()
                        .u16(33)
                        .u8(37)
                        .u8(if (restart) 1 else 0)
                        .u32(0)
                        .bytes(directory.id)
                        .u16(96)
                        .u16(pattern.size)
                        .u32(65536)
                        .bytes(pattern)
                        .build()
                val response = connection.request(14, request, setOf(0x80000006, 0xc000000f))
                val wire = Wire(response)
                if (wire.u32(8) != 0L) break
                protocolCheck(wire.u16(64) == 9, "Invalid directory response")
                val data = wire.bytes(wire.u16(66), wire.int32(68))
                if (data.isEmpty() || previous?.contentEquals(data) == true) break
                previous = data
                val entries = Wire(data)
                var offset = 0
                while (offset < data.size) {
                    val next = entries.int32(offset)
                    val nameLength = entries.int32(offset + 60)
                    val name = entries.utf16(offset + 104, nameLength)
                    protocolCheck(
                        next == 0 ||
                            (next >= 104 + nameLength &&
                                next % 8 == 0 &&
                                next <= data.size - offset),
                        "Invalid directory entry chain",
                    )
                    if (SmbPath.isValidSegment(name)) {
                        emit(
                            SmbEntry(
                                path.child(name),
                                type(entries.u32(offset + 56)),
                                entries.i64(offset + 40),
                                fileTime(entries.i64(offset + 24)),
                                fileTime(entries.i64(offset + 8)),
                            )
                        )
                    }
                    if (next == 0) break
                    offset += next
                }
                restart = false
            }
        }
    }

    override suspend fun mkdir(path: SmbPath) {
        require(path.segments.isNotEmpty())
        open(path, access = 0x100081, disposition = 2, options = 1, attributes = 0x10).close()
    }

    override suspend fun delete(path: SmbPath, recursive: Boolean) {
        require(path.segments.isNotEmpty()) { "Cannot delete the share root" }
        if (recursive && stat(path).type == SmbFileType.DIRECTORY) {
            list(path).toList().forEach { delete(it.path, recursive = true) }
        }
        open(path, access = 0x10000).use { setInfo(it.id, 13, byteArrayOf(1)) }
    }

    override suspend fun move(source: SmbPath, destination: SmbPath) {
        require(source.segments.isNotEmpty() && destination.segments.isNotEmpty())
        val name = destination.wireName()
        open(source, access = 0x10080).use {
            setInfo(it.id, 10, Packet().zeros(8).i64(0).u32(name.size.toLong()).bytes(name).build())
        }
    }

    override suspend fun openFile(path: SmbPath, mode: SmbOpenMode): SmbFile {
        val disposition =
            when (mode) {
                SmbOpenMode.READ -> 1
                SmbOpenMode.CREATE_NEW -> 2
                SmbOpenMode.OVERWRITE -> 5
                else -> 3
            }
        val writable = mode != SmbOpenMode.READ
        return open(
                path,
                access = if (writable) 0xc0000000 else 0x80000000,
                disposition = disposition,
                options = 0x40,
                attributes = 0x80,
            )
            .let { NativeFile(it, writable) }
    }

    override suspend fun setModifiedAt(path: SmbPath, instant: Instant) {
        open(path, access = 0x100).use {
            setInfo(it.id, 4, Packet().zeros(16).i64(instant.fileTime()).zeros(16).build())
        }
    }

    override suspend fun capacity(): SmbCapacity =
        open(SmbPath.Root, access = 0x80).use {
            val request = Packet().u16(41).u8(2).u8(7).u32(32).zeros(16).bytes(it.id).build()
            val response = Wire(connection.request(16, request))
            protocolCheck(response.u16(64) == 9, "Invalid filesystem information response")
            val info = Wire(response.bytes(response.u16(66), response.int32(68)))
            val allocationUnit = Math.multiplyExact(info.u32(24), info.u32(28))
            val total = Math.multiplyExact(info.i64(0), allocationUnit)
            val free = Math.multiplyExact(info.i64(8), allocationUnit)
            protocolCheck(total >= 0 && free in 0..total, "Invalid filesystem capacity")
            SmbCapacity(total, free)
        }

    override fun disconnect() = connection.disconnect()

    override suspend fun close() = disconnect()

    private suspend fun setInfo(id: ByteArray, infoClass: Int, data: ByteArray) {
        val request =
            Packet()
                .u16(33)
                .u8(1)
                .u8(infoClass)
                .u32(data.size.toLong())
                .u16(96)
                .u16(0)
                .u32(0)
                .bytes(id)
                .bytes(data)
                .build()
        val response = Wire(connection.request(17, request))
        protocolCheck(response.u16(64) == 2, "Invalid set information response")
    }

    private suspend fun open(
        path: SmbPath,
        access: Long,
        disposition: Int = 1,
        options: Int = 0,
        attributes: Int = 0,
    ): OpenEntry {
        val name = path.wireName()
        require(name.size <= 65535) { "SMB path exceeds wire length" }
        val body =
            Packet()
                .u16(57)
                .u8(0)
                .u8(0)
                .u32(2)
                .zeros(16)
                .u32(access)
                .u32(attributes.toLong())
                .u32(7)
                .u32(disposition.toLong())
                .u32((options or 0x00200000).toLong())
                .u16(120)
                .u16(name.size)
                .zeros(8)
                .bytes(if (name.isEmpty()) ByteArray(1) else name)
                .build()
        val response = Wire(connection.request(5, body))
        protocolCheck(response.u16(64) == 89, "Invalid create response")
        return OpenEntry(
            response.bytes(128, 16),
            SmbEntry(
                path,
                type(response.u32(120)),
                response.i64(112),
                fileTime(response.i64(88)),
                fileTime(response.i64(72)),
            ),
        )
    }

    private inner class OpenEntry(val id: ByteArray, val entry: SmbEntry) : SmbResource {
        val closed = AtomicBoolean(false)

        override suspend fun close() {
            if (!closed.compareAndSet(false, true) || !connection.connected) return
            val response = Wire(connection.request(6, Packet().u16(24).zeros(6).bytes(id).build()))
            protocolCheck(response.u16(64) == 60, "Invalid close response")
        }
    }

    private inner class NativeFile(entry: OpenEntry, writable: Boolean) : SmbFile {
        private val file = BlockingFile(entry, writable)

        override fun blocking(): SmbBlockingFile = file

        override suspend fun read(offset: Long, buffer: ByteArray, start: Int, length: Int): Int =
            connection.cancellable {
                file.read(offset, buffer, start, length)
            }

        override suspend fun write(offset: Long, buffer: ByteArray, start: Int, length: Int) =
            connection.cancellable {
                file.write(offset, buffer, start, length)
            }

        override suspend fun size(): Long = connection.cancellable { file.size() }

        override suspend fun resize(size: Long) = connection.cancellable { file.resize(size) }

        override suspend fun flush() = connection.cancellable { file.flush() }

        override suspend fun close() {
            if (!connection.connected) return
            connection.cancellable { file.close() }
        }
    }

    private inner class BlockingFile(private val entry: OpenEntry, private val writable: Boolean) :
        SmbBlockingFile {
        private fun checkOpen() {
            check(!entry.closed.get()) { "SMB file is closed" }
        }

        private fun range(offset: Long, buffer: ByteArray, start: Int, length: Int) {
            require(offset >= 0 && length >= 0 && offset <= Long.MAX_VALUE - length)
            require(start >= 0 && start <= buffer.size - length)
        }

        override fun read(offset: Long, buffer: ByteArray, start: Int, length: Int): Int {
            checkOpen()
            range(offset, buffer, start, length)
            if (length == 0) return 0
            val count = length.coerceAtMost(connection.maxRead)
            val body =
                Packet()
                    .u16(49)
                    .zeros(2)
                    .u32(count.toLong())
                    .i64(offset)
                    .bytes(entry.id)
                    .u32(1)
                    .zeros(13)
                    .build()
            val response = Wire(connection.requestBlocking(8, body, setOf(0xc0000011)))
            if (response.u32(8) == 0xc0000011L) return -1
            protocolCheck(response.u16(64) == 17, "Invalid read response")
            val dataLength = response.int32(68)
            protocolCheck(dataLength in 1..count, "Invalid SMB read length")
            response.bytes(response.u8(66), dataLength).copyInto(buffer, start)
            return dataLength
        }

        override fun write(offset: Long, buffer: ByteArray, start: Int, length: Int) {
            checkOpen()
            check(writable) { "SMB file was opened read-only" }
            range(offset, buffer, start, length)
            var written = 0
            while (written < length) {
                val count = (length - written).coerceAtMost(connection.maxWrite)
                val body =
                    Packet()
                        .u16(49)
                        .u16(112)
                        .u32(count.toLong())
                        .i64(offset + written)
                        .bytes(entry.id)
                        .zeros(16)
                        .bytes(buffer.copyOfRange(start + written, start + written + count))
                        .build()
                val response = Wire(connection.requestBlocking(9, body))
                protocolCheck(response.u16(64) == 17, "Invalid write response")
                val accepted = response.int32(68)
                protocolCheck(accepted in 1..count, "Invalid SMB write length")
                written += accepted
            }
        }

        override fun size(): Long {
            checkOpen()
            val request = Packet().u16(41).u8(1).u8(5).u32(24).zeros(16).bytes(entry.id).build()
            val response = Wire(connection.requestBlocking(16, request))
            protocolCheck(response.u16(64) == 9, "Invalid file information response")
            return Wire(response.bytes(response.u16(66), response.int32(68))).i64(8).also {
                protocolCheck(it >= 0, "Negative file size")
            }
        }

        override fun resize(size: Long) {
            checkOpen()
            check(writable) { "SMB file was opened read-only" }
            require(size >= 0)
            val data = Packet().i64(size).build()
            val body =
                Packet()
                    .u16(33)
                    .u8(1)
                    .u8(20)
                    .u32(8)
                    .u16(96)
                    .u16(0)
                    .u32(0)
                    .bytes(entry.id)
                    .bytes(data)
                    .build()
            val response = Wire(connection.requestBlocking(17, body))
            protocolCheck(response.u16(64) == 2, "Invalid set information response")
        }

        override fun flush() {
            checkOpen()
            val response =
                Wire(
                    connection.requestBlocking(7, Packet().u16(24).zeros(6).bytes(entry.id).build())
                )
            protocolCheck(response.u16(64) == 4, "Invalid flush response")
        }

        override fun close() {
            if (!entry.closed.compareAndSet(false, true) || !connection.connected) return
            val response =
                Wire(
                    connection.requestBlocking(6, Packet().u16(24).zeros(6).bytes(entry.id).build())
                )
            protocolCheck(response.u16(64) == 60, "Invalid close response")
        }
    }
}

private fun SmbPath.wireName(): ByteArray = segments.joinToString("\\").utf16()

private fun type(attributes: Long): SmbFileType =
    when {
        attributes and 0x400 != 0L -> SmbFileType.REPARSE_POINT
        attributes and 0x10 != 0L -> SmbFileType.DIRECTORY
        else -> SmbFileType.FILE
    }
