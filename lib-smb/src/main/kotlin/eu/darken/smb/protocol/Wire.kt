package eu.darken.smb.protocol

import eu.darken.smb.SmbException
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.CodingErrorAction
import kotlin.time.Instant

internal fun protocolCheck(condition: Boolean, message: String) {
    if (!condition) throw SmbException(SmbException.Kind.PROTOCOL, message = message)
}

internal class Wire(private val bytes: ByteArray) {
    val size: Int
        get() = bytes.size

    private fun range(offset: Int, length: Int) {
        protocolCheck(
            offset >= 0 && length >= 0 && offset <= bytes.size - length,
            "Truncated SMB field",
        )
    }

    fun u8(offset: Int): Int {
        range(offset, 1)
        return bytes[offset].toInt() and 255
    }

    fun u16(offset: Int): Int {
        range(offset, 2)
        return u8(offset) or (u8(offset + 1) shl 8)
    }

    fun u32(offset: Int): Long {
        range(offset, 4)
        return (0..3).fold(0L) { value, i -> value or (u8(offset + i).toLong() shl (8 * i)) }
    }

    fun i64(offset: Int): Long {
        range(offset, 8)
        return (0..7).fold(0L) { value, i -> value or (u8(offset + i).toLong() shl (8 * i)) }
    }

    fun int32(offset: Int): Int =
        u32(offset)
            .also { protocolCheck(it <= Int.MAX_VALUE, "SMB field exceeds supported size") }
            .toInt()

    fun bytes(offset: Int, length: Int): ByteArray {
        range(offset, length)
        return bytes.copyOfRange(offset, offset + length)
    }

    fun utf16(offset: Int, length: Int): String {
        range(offset, length)
        protocolCheck(length % 2 == 0, "Odd UTF-16 field length")
        return try {
            Charsets.UTF_16LE.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes, offset, length))
                .toString()
        } catch (error: java.nio.charset.CharacterCodingException) {
            throw SmbException(
                SmbException.Kind.PROTOCOL,
                message = "Malformed UTF-16 field",
                cause = error,
            )
        }
    }
}

internal class Packet {
    private val out = ByteArrayOutputStream()
    val size: Int
        get() = out.size()

    fun u8(value: Int): Packet = apply { out.write(value) }

    fun u16(value: Int): Packet = apply { repeat(2) { out.write(value ushr (it * 8)) } }

    fun u32(value: Long): Packet = apply { repeat(4) { out.write((value ushr (it * 8)).toInt()) } }

    fun i64(value: Long): Packet = apply { repeat(8) { out.write((value ushr (it * 8)).toInt()) } }

    fun bytes(value: ByteArray): Packet = apply { out.write(value) }

    fun zeros(count: Int): Packet = bytes(ByteArray(count))

    fun align(boundary: Int): Packet = zeros((boundary - size % boundary) % boundary)

    fun build(): ByteArray = out.toByteArray()
}

internal fun ByteArray.put16(offset: Int, value: Int) {
    ByteBuffer.wrap(this).order(ByteOrder.LITTLE_ENDIAN).putShort(offset, value.toShort())
}

internal fun ByteArray.put32(offset: Int, value: Long) {
    ByteBuffer.wrap(this).order(ByteOrder.LITTLE_ENDIAN).putInt(offset, value.toInt())
}

internal fun ByteArray.put64(offset: Int, value: Long) {
    ByteBuffer.wrap(this).order(ByteOrder.LITTLE_ENDIAN).putLong(offset, value)
}

internal fun String.utf16(): ByteArray = toByteArray(Charsets.UTF_16LE)

internal fun fileTime(value: Long): Instant =
    Instant.fromEpochMilliseconds(value / 10_000 - 11_644_473_600_000)

internal fun Instant.fileTime(): Long =
    Math.multiplyExact(Math.addExact(toEpochMilliseconds(), 11_644_473_600_000), 10_000)

internal fun statusException(status: Long, phase: Int = 0): SmbException {
    val kind =
        when (status) {
            0xc000000fL,
            0xc0000034L,
            0xc000003aL,
            0xc0000225L -> SmbException.Kind.MISSING
            0xc0000035L -> SmbException.Kind.ALREADY_EXISTS
            0xc0000022L ->
                when (phase) {
                    1 -> SmbException.Kind.AUTHENTICATION
                    2 -> SmbException.Kind.SHARE_ACCESS_DENIED
                    else -> SmbException.Kind.ACCESS_DENIED
                }
            0xc000006dL,
            0xc0000071L,
            0xc0000072L,
            0xc000015bL -> SmbException.Kind.AUTHENTICATION
            0xc00000ccL -> SmbException.Kind.SHARE_MISSING
            0xc0000101L -> SmbException.Kind.DIRECTORY_NOT_EMPTY
            0xc0000103L -> SmbException.Kind.NOT_DIRECTORY
            0xc00000baL -> SmbException.Kind.IS_DIRECTORY
            0xc000007fL -> SmbException.Kind.DISK_FULL
            0xc0000043L -> SmbException.Kind.SHARING_VIOLATION
            0xc0000121L -> SmbException.Kind.ACCESS_DENIED
            0xc000020cL,
            0xc000020dL,
            0xc00000c9L,
            0xc0000203L,
            0xc000035cL,
            0xc000026eL -> SmbException.Kind.TRANSPORT
            else -> SmbException.Kind.OTHER
        }
    return SmbException(kind, status, "SMB status 0x${status.toString(16)} ($kind)")
}
