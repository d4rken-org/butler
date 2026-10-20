/*-
* Copyright (c) 2018 Grzegorz Antoniak (http://antoniak.org)
* All rights reserved.
*
* Redistribution and use in source and binary forms, with or without
* modification, are permitted provided that the following conditions
* are met:
* 1. Redistributions of source code must retain the above copyright
*    notice, this list of conditions and the following disclaimer.
* 2. Redistributions in binary form must reproduce the above copyright
*    notice, this list of conditions and the following disclaimer in the
*    documentation and/or other materials provided with the distribution.
*
* THIS SOFTWARE IS PROVIDED BY THE AUTHOR(S) ``AS IS'' AND ANY EXPRESS OR
* IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES
* OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE DISCLAIMED.
* IN NO EVENT SHALL THE AUTHOR(S) BE LIABLE FOR ANY DIRECT, INDIRECT,
* INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT
* NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE,
* DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY
* THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
* (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF
* THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
*/

// Kotlin port of the RAR5 header parsing in libarchive v3.8.10
// libarchive/archive_read_support_format_rar5.c.

package eu.darken.rar

import java.util.zip.CRC32
import kotlin.time.Instant

internal object RarSignature {
    enum class Kind { RAR5, RAR4, UNKNOWN }

    const val RAR5_SIZE = 8
    private val PREFIX = byteArrayOf(0x52, 0x61, 0x72, 0x21, 0x1A, 0x07)

    /** Classifies the first [length] bytes of [bytes]: RAR5 needs 8 bytes, RAR4 is recognized from 7. */
    fun detect(bytes: ByteArray, length: Int = bytes.size): Kind {
        val available = minOf(length, bytes.size)
        if (available < 7) return Kind.UNKNOWN
        for (i in PREFIX.indices) if (bytes[i] != PREFIX[i]) return Kind.UNKNOWN
        return when {
            bytes[6] == 0x00.toByte() -> Kind.RAR4
            available >= 8 && bytes[6] == 0x01.toByte() && bytes[7] == 0x00.toByte() -> Kind.RAR5
            else -> Kind.UNKNOWN
        }
    }
}

internal object HeaderType {
    const val MARK = 0L
    const val MAIN = 1L
    const val FILE = 2L
    const val SERVICE = 3L
    const val CRYPT = 4L
    const val ENDARC = 5L
}

internal object HeaderFlags {
    const val EXTRA_DATA = 0x0001L
    const val DATA = 0x0002L
    const val SKIP_IF_UNKNOWN = 0x0004L
    const val SPLIT_BEFORE = 0x0008L
    const val SPLIT_AFTER = 0x0010L
}

private fun corrupt(message: String) = RarCorruptException(message)

private fun limit(name: String, value: Long, max: Long) =
    RarUnsupportedException(RarUnsupportedReason.LimitExceeded(name, value, max))

/** Bounded little-endian reads over one CRC-checked header. */
internal class HeaderCursor(private val bytes: ByteArray, private var pos: Int, private val end: Int) {
    val remaining: Int get() = end - pos

    /** RAR5 variable-length integer; values above Long.MAX_VALUE are rejected. */
    fun vint(): Long {
        var result = 0L
        var shift = 0
        repeat(10) {
            if (pos >= end) throw corrupt("Truncated number in header")
            val b = bytes[pos++].toInt() and 0xFF
            val bits = (b and 0x7F).toLong()
            if (shift == 63 && bits != 0L) throw corrupt("Number too large")
            result = result or (bits shl shift)
            if (b and 0x80 == 0) return result
            shift += 7
        }
        throw corrupt("Number too long")
    }

    fun vintInt(what: String): Int {
        val v = vint()
        if (v > Int.MAX_VALUE) throw corrupt("$what is too large")
        return v.toInt()
    }

    fun u8(): Int {
        need(1)
        return bytes[pos++].toInt() and 0xFF
    }

    fun u32(): Long {
        need(4)
        var v = 0L
        for (i in 0 until 4) v = v or ((bytes[pos + i].toLong() and 0xFF) shl (8 * i))
        pos += 4
        return v
    }

    fun u64(): Long {
        need(8)
        var v = 0L
        for (i in 0 until 8) v = v or ((bytes[pos + i].toLong() and 0xFF) shl (8 * i))
        pos += 8
        return v
    }

    fun bytes(count: Int): ByteArray {
        need(count)
        val out = bytes.copyOfRange(pos, pos + count)
        pos += count
        return out
    }

    fun skip(count: Int) {
        need(count)
        pos += count
    }

    fun slice(count: Int): HeaderCursor {
        need(count)
        val sub = HeaderCursor(bytes, pos, pos + count)
        pos += count
        return sub
    }

    private fun need(count: Int) {
        if (count < 0 || count > remaining) throw corrupt("Header field exceeds header size")
    }
}

/** A base block: header type, flags and the cursor positioned after the common fields. */
internal class Block(
    val type: Long,
    val flags: Long,
    val extraSize: Int,
    val dataSize: Long,
    val body: HeaderCursor,
) {
    val splitBefore: Boolean get() = flags and HeaderFlags.SPLIT_BEFORE != 0L
    val splitAfter: Boolean get() = flags and HeaderFlags.SPLIT_AFTER != 0L

    fun extraArea(): HeaderCursor? = if (extraSize > 0) body.slice(extraSize) else null

    companion object {
        /** Parses [data], the size field followed by the header body, whose CRC was already verified. */
        fun parse(data: ByteArray, sizeFieldLength: Int): Block {
            val cursor = HeaderCursor(data, sizeFieldLength, data.size)
            val type = cursor.vint()
            val flags = cursor.vint()
            val extraSize = if (flags and HeaderFlags.EXTRA_DATA != 0L) cursor.vint() else 0L
            val dataSize = if (flags and HeaderFlags.DATA != 0L) cursor.vint() else 0L
            if (extraSize > cursor.remaining) throw corrupt("Extra area exceeds header size")
            return Block(type, flags, extraSize.toInt(), dataSize, cursor)
        }

        fun crcOf(data: ByteArray): Int = CRC32().apply { update(data) }.value.toInt()
    }
}

internal class MainHeader(
    val volume: Boolean,
    val volumeNumber: Int,
    val solid: Boolean,
    val recovery: Boolean,
    val locked: Boolean,
) {
    companion object {
        private const val VOLUME = 0x0001L
        private const val VOLUME_NUMBER = 0x0002L
        private const val SOLID = 0x0004L
        private const val PROTECT = 0x0008L
        private const val LOCK = 0x0010L
        private const val EXTRA_LOCATOR = 0x01L
        private const val LOCATOR_QLIST = 0x01L
        private const val LOCATOR_RECOVERY = 0x02L

        fun parse(block: Block, limits: RarLimits): MainHeader {
            val c = block.body
            val flags = c.vint()
            val volumeNumber = if (flags and VOLUME_NUMBER != 0L) c.vintInt("Volume number") else 0
            block.extraArea()?.let { extra ->
                forEachRecord(extra, limits) { id, record ->
                    if (id == EXTRA_LOCATOR) {
                        val locatorFlags = record.vint()
                        if (locatorFlags and LOCATOR_QLIST != 0L) record.vint()
                        if (locatorFlags and LOCATOR_RECOVERY != 0L) record.vint()
                    }
                }
            }
            return MainHeader(
                volume = flags and VOLUME != 0L,
                volumeNumber = volumeNumber,
                solid = flags and SOLID != 0L,
                recovery = flags and PROTECT != 0L,
                locked = flags and LOCK != 0L,
            )
        }
    }
}

internal class EndHeader(val moreVolumes: Boolean) {
    companion object {
        // libarchive never reads the flags field; headers without one occur and mean "last volume".
        fun parse(block: Block): EndHeader =
            EndHeader(block.body.remaining > 0 && block.body.vint() and 0x01L != 0L)
    }
}

/** Archive encryption header (HEAD_CRYPT); only its parameters, the key derivation is elsewhere. */
internal class CryptHeader(val algorithm: Long, val flags: Long, val kdfCount: Int, val salt: ByteArray?, val check: ByteArray?) {
    companion object {
        fun parse(block: Block): CryptHeader {
            val c = block.body
            val algorithm = c.vint()
            val flags = c.vint()
            // The layout after the flags is only known for AES-256 (algorithm 0).
            if (algorithm != 0L) return CryptHeader(algorithm, flags, 0, null, null)
            val kdfCount = c.u8()
            val salt = c.bytes(16)
            val check = if (flags and 0x01L != 0L) c.bytes(12) else null
            return CryptHeader(algorithm, flags, kdfCount, salt, check)
        }
    }
}

/** File encryption record; only its parameters, the key derivation is elsewhere. */
internal class FileCrypt(
    val algorithm: Long,
    val flags: Long,
    val kdfCount: Int,
    val salt: ByteArray?,
    val iv: ByteArray?,
    val check: ByteArray?,
) {
    val usesMac: Boolean get() = flags and 0x02L != 0L
}

/**
 * Compression information field. The algorithm-version and dictionary rule follows 7-Zip's
 * Rar5Handler.h (CItem::Get_AlgoVersion_RawBits, Get_DictSize_Main/Frac, Get_DictSize64).
 */
internal class CompressionInfo(val raw: Long) {
    val algorithmVersion: Int = (raw and 0x3F).toInt()
    val solid: Boolean = raw and 0x40L != 0L
    val method: Int = ((raw shr 7) and 0x7).toInt()
    val rar5Compat: Boolean = (raw shr 20) and 0x1L != 0L
    val supportedVersion: Boolean = algorithmVersion == 0 || (algorithmVersion == 1 && rar5Compat)
    private val mainMask: Long = if (algorithmVersion == 0) 0xFL else 0x1FL
    val dictionaryMain: Int = ((raw shr 10) and mainMask).toInt()
    val dictionaryFraction: Int = if (algorithmVersion == 0) 0 else ((raw shr 15) and 0x1F).toInt()

    /** Window size in bytes for a supported version, null otherwise. */
    val windowBytes: Long? = if (!supportedVersion) {
        null
    } else {
        val shift = 12 + dictionaryMain
        val base = 32L + dictionaryFraction
        if (shift >= 63 || base > (Long.MAX_VALUE shr shift)) throw corrupt("Dictionary size overflows")
        base shl shift
    }
}

internal class FileHeader(
    val service: Boolean,
    val name: String,
    val splitBefore: Boolean,
    val splitAfter: Boolean,
    val isDirectory: Boolean,
    val size: Long?,
    val attributes: Long,
    val crc32: Int?,
    val compression: CompressionInfo,
    val hostOs: RarHostOs,
    val dataSize: Long,
    val mtime: Instant?,
    val ctime: Instant?,
    val atime: Instant?,
    val blake2sp: ByteArray?,
    val version: Int?,
    val redirection: RarRedirection?,
    val crypt: FileCrypt?,
) {
    val checksum: RarChecksum?
        get() = when {
            blake2sp != null -> RarChecksum.Blake2sp(blake2sp)
            crc32 != null -> RarChecksum.Crc32(crc32)
            else -> null
        }

    companion object {
        private const val FLAG_DIRECTORY = 0x0001L
        private const val FLAG_UTIME = 0x0002L
        private const val FLAG_CRC32 = 0x0004L
        private const val FLAG_UNKNOWN_SIZE = 0x0008L

        private const val EX_CRYPT = 0x01L
        private const val EX_HASH = 0x02L
        private const val EX_HTIME = 0x03L
        private const val EX_VERSION = 0x04L
        private const val EX_REDIR = 0x05L
        private const val EX_UOWNER = 0x06L

        private const val HASH_BLAKE2SP = 0L
        private const val BLAKE2SP_SIZE = 32

        private const val HTIME_UNIX = 0x01L
        private const val HTIME_MTIME = 0x02L
        private const val HTIME_CTIME = 0x04L
        private const val HTIME_ATIME = 0x08L
        private const val HTIME_UNIX_NS = 0x10L

        private const val OWNER_USER_NAME = 0x01L
        private const val OWNER_GROUP_NAME = 0x02L
        private const val OWNER_USER_UID = 0x04L
        private const val OWNER_GROUP_GID = 0x08L

        private const val REDIR_SYMLINK_IS_DIR = 0x01L

        fun parse(block: Block, service: Boolean, limits: RarLimits): FileHeader {
            val c = block.body
            val fileFlags = c.vint()
            val unpackedSize = c.vint()
            val attributes = c.vint()
            val unixMtime = if (fileFlags and FLAG_UTIME != 0L) c.u32() else null
            val crc = if (fileFlags and FLAG_CRC32 != 0L) c.u32().toInt() else null
            val compression = CompressionInfo(c.vint())
            val hostOs = when (c.vint()) {
                0L -> RarHostOs.WINDOWS
                1L -> RarHostOs.UNIX
                else -> RarHostOs.OTHER
            }
            val nameSize = c.vint()
            if (nameSize == 0L) throw corrupt("No file name specified")
            if (nameSize > c.remaining) throw corrupt("File name exceeds header size")
            if (nameSize > limits.maxNameBytes) throw limit("maxNameBytes", nameSize, limits.maxNameBytes.toLong())
            val name = String(c.bytes(nameSize.toInt()), Charsets.UTF_8)

            val isDirectory = fileFlags and FLAG_DIRECTORY != 0L
            val size = if (fileFlags and FLAG_UNKNOWN_SIZE != 0L) null else unpackedSize
            if (isDirectory) {
                // Directory headers still declare HFL_DATA, but must not carry data.
                if (unpackedSize != 0L || block.dataSize != 0L) throw corrupt("Directory entries cannot have any data")
            } else if (block.flags and HeaderFlags.DATA == 0L) {
                throw corrupt("No data found in file/service block")
            }

            val extra = Extra()
            val area = block.extraArea()
            // Service headers are skipped unread; RAR 5.21 and older wrote a short Subdata record there.
            if (area != null && !service) parseExtra(area, extra, limits)

            return FileHeader(
                service = service,
                name = name,
                splitBefore = block.splitBefore,
                splitAfter = block.splitAfter,
                isDirectory = isDirectory,
                size = size,
                attributes = attributes,
                crc32 = crc,
                compression = compression,
                hostOs = hostOs,
                dataSize = block.dataSize,
                mtime = extra.mtime ?: unixMtime?.let { Instant.fromEpochSeconds(it) },
                ctime = extra.ctime,
                atime = extra.atime,
                blake2sp = extra.blake2sp,
                version = extra.version,
                redirection = extra.redirection,
                crypt = extra.crypt,
            )
        }

        private class Extra {
            var mtime: Instant? = null
            var ctime: Instant? = null
            var atime: Instant? = null
            var blake2sp: ByteArray? = null
            var version: Int? = null
            var redirection: RarRedirection? = null
            var crypt: FileCrypt? = null
        }

        private fun parseExtra(area: HeaderCursor, extra: Extra, limits: RarLimits) {
            forEachRecord(area, limits) { id, record -> parseRecord(id, record, extra, limits) }
        }

        private fun parseRecord(id: Long, record: HeaderCursor, extra: Extra, limits: RarLimits) {
            when (id) {
                EX_CRYPT -> extra.crypt = parseCrypt(record)
                EX_HASH -> parseHash(record, extra)
                EX_HTIME -> parseTimes(record, extra)
                EX_VERSION -> {
                    record.vint() // flags, unused
                    extra.version = record.vintInt("File version")
                }
                EX_REDIR -> extra.redirection = parseRedirection(record, limits)
                EX_UOWNER -> parseOwner(record)
                else -> Unit // Unknown and subdata records are skipped.
            }
        }

        private fun parseCrypt(record: HeaderCursor): FileCrypt {
            val algorithm = record.vint()
            val flags = record.vint()
            if (algorithm != 0L) return FileCrypt(algorithm, flags, 0, null, null, null)
            val kdfCount = record.u8()
            val salt = record.bytes(16)
            val iv = record.bytes(16)
            val check = if (flags and 0x01L != 0L) record.bytes(12) else null
            return FileCrypt(algorithm, flags, kdfCount, salt, iv, check)
        }

        private fun parseHash(record: HeaderCursor, extra: Extra) {
            if (record.vint() == HASH_BLAKE2SP) {
                if (record.remaining < BLAKE2SP_SIZE) throw corrupt("Truncated BLAKE2sp hash")
                extra.blake2sp = record.bytes(BLAKE2SP_SIZE)
            }
            // Other hash types are ignored; the entry may still carry a CRC32.
        }

        private fun parseTimes(record: HeaderCursor, extra: Extra) {
            val flags = record.vint()
            val unix = flags and HTIME_UNIX != 0L
            val present = listOf(HTIME_MTIME, HTIME_CTIME, HTIME_ATIME).map { flags and it != 0L }
            val seconds = LongArray(3)
            val nanos = IntArray(3)
            for (i in 0 until 3) {
                if (!present[i]) continue
                if (unix) {
                    seconds[i] = record.u32()
                } else {
                    val ticks = record.u64().toULong()
                    seconds[i] = (ticks / 10_000_000uL).toLong() - WINDOWS_TO_UNIX_SECONDS
                    nanos[i] = ((ticks % 10_000_000uL).toLong() * 100).toInt()
                }
            }
            if (unix && flags and HTIME_UNIX_NS != 0L) {
                for (i in 0 until 3) {
                    if (!present[i]) continue
                    // Same tolerance as 7-Zip: the top two bits are reserved, out-of-range values are dropped.
                    val ns = (record.u32() and 0x3FFFFFFF).toInt()
                    if (ns < 1_000_000_000) nanos[i] = ns
                }
            }
            fun at(i: Int) = if (present[i]) Instant.fromEpochSeconds(seconds[i], nanos[i]) else null
            extra.mtime = at(0)
            extra.ctime = at(1)
            extra.atime = at(2)
        }

        private fun parseRedirection(record: HeaderCursor, limits: RarLimits): RarRedirection {
            val type = record.vint()
            val flags = record.vint()
            val targetSize = record.vint()
            if (targetSize == 0L) throw corrupt("No link target specified")
            if (targetSize > record.remaining) throw corrupt("Link target exceeds record size")
            if (targetSize > limits.maxNameBytes) throw limit("maxNameBytes", targetSize, limits.maxNameBytes.toLong())
            val target = String(record.bytes(targetSize.toInt()), Charsets.UTF_8)
            val redirectionType = when (type) {
                1L -> RarRedirectionType.UNIX_SYMLINK
                2L -> RarRedirectionType.WINDOWS_SYMLINK
                3L -> RarRedirectionType.JUNCTION
                4L -> RarRedirectionType.HARDLINK
                5L -> RarRedirectionType.FILE_COPY
                else -> RarRedirectionType.UNKNOWN
            }
            return RarRedirection(redirectionType, target, flags and REDIR_SYMLINK_IS_DIR != 0L)
        }

        private fun parseOwner(record: HeaderCursor) {
            val flags = record.vint()
            for (bit in listOf(OWNER_USER_NAME, OWNER_GROUP_NAME)) {
                if (flags and bit == 0L) continue
                val size = record.vint()
                if (size > record.remaining) throw corrupt("Owner name exceeds record size")
                record.skip(size.toInt())
            }
            if (flags and OWNER_USER_UID != 0L) record.vint()
            if (flags and OWNER_GROUP_GID != 0L) record.vint()
        }

        private const val WINDOWS_TO_UNIX_SECONDS = 11_644_473_600L
    }
}

/** Walks the records of an extra area: size (covering id and data), id, data. */
private inline fun forEachRecord(area: HeaderCursor, limits: RarLimits, action: (Long, HeaderCursor) -> Unit) {
    var count = 0
    while (area.remaining > 0) {
        if (++count > limits.maxExtraRecords) {
            throw limit("maxExtraRecords", count.toLong(), limits.maxExtraRecords.toLong())
        }
        val size = area.vint()
        if (size == 0L || size > area.remaining) throw corrupt("Extra record size exceeds extra area")
        val record = area.slice(size.toInt())
        val id = record.vint()
        action(id, record)
    }
}
