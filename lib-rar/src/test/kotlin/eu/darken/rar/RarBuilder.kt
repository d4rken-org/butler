package eu.darken.rar

import java.io.ByteArrayOutputStream
import java.util.zip.CRC32

/** Writes crafted RAR5 archives block by block, with valid header CRCs unless told otherwise. */
internal class RarBuilder(withSignature: Boolean = true) {
    private val out = ByteArrayOutputStream()

    init {
        if (withSignature) out.write(SIGNATURE)
    }

    fun raw(bytes: ByteArray) = apply { out.write(bytes) }

    /** A base block; [extra] goes after [fields], [data] after the header. */
    fun block(
        type: Long,
        flags: Long = 0,
        fields: ByteArray = ByteArray(0),
        extra: ByteArray = ByteArray(0),
        data: ByteArray = ByteArray(0),
        withData: Boolean = data.isNotEmpty(),
        crcDelta: Int = 0,
    ) = apply {
        var allFlags = flags
        if (extra.isNotEmpty()) allFlags = allFlags or HeaderFlags.EXTRA_DATA
        if (withData) allFlags = allFlags or HeaderFlags.DATA
        val header = ByteArrayOutputStream().apply {
            write(vint(type))
            write(vint(allFlags))
            if (extra.isNotEmpty()) write(vint(extra.size.toLong()))
            if (withData) write(vint(data.size.toLong()))
            write(fields)
            write(extra)
        }.toByteArray()
        val sized = vint(header.size.toLong()) + header
        out.write(le32(crc(sized) + crcDelta))
        out.write(sized)
        out.write(data)
    }

    fun main(flags: Long = 0, volumeNumber: Long? = null, extra: ByteArray = ByteArray(0)) = block(
        type = HeaderType.MAIN,
        fields = vint(if (volumeNumber != null) flags or 0x02 else flags) + (volumeNumber?.let(::vint) ?: ByteArray(0)),
        extra = extra,
    )

    fun file(
        name: String,
        data: ByteArray = ByteArray(0),
        compression: Long = 0,
        directory: Boolean = false,
        size: Long? = if (directory) 0 else data.size.toLong(),
        crc: Int? = if (directory) null else RarBuilder.crc(data),
        extra: ByteArray = ByteArray(0),
        hostOs: Long = 1,
        attributes: Long = if (directory) 0x41ED else 0x81A4,
        unixMtime: Long? = null,
        blockFlags: Long = 0,
        service: Boolean = false,
    ) = apply {
        var fileFlags = 0L
        if (directory) fileFlags = fileFlags or 0x01
        if (unixMtime != null) fileFlags = fileFlags or 0x02
        if (crc != null) fileFlags = fileFlags or 0x04
        if (size == null) fileFlags = fileFlags or 0x08
        val nameBytes = name.toByteArray(Charsets.UTF_8)
        val fields = ByteArrayOutputStream().apply {
            write(vint(fileFlags))
            write(vint(size ?: 0))
            write(vint(attributes))
            if (unixMtime != null) write(le32(unixMtime.toInt()))
            if (crc != null) write(le32(crc))
            write(vint(compression))
            write(vint(hostOs))
            write(vint(nameBytes.size.toLong()))
            write(nameBytes)
        }.toByteArray()
        block(
            type = if (service) HeaderType.SERVICE else HeaderType.FILE,
            flags = blockFlags,
            fields = fields,
            extra = extra,
            data = data,
            withData = true,
        )
    }

    fun end(moreVolumes: Boolean = false) = block(type = HeaderType.ENDARC, fields = vint(if (moreVolumes) 1 else 0))

    fun build(): ByteArray = out.toByteArray()

    companion object {
        val SIGNATURE = byteArrayOf(0x52, 0x61, 0x72, 0x21, 0x1A, 0x07, 0x01, 0x00)

        fun vint(value: Long): ByteArray {
            val out = ByteArrayOutputStream()
            var v = value
            do {
                var b = (v and 0x7F).toInt()
                v = v ushr 7
                if (v != 0L) b = b or 0x80
                out.write(b)
            } while (v != 0L)
            return out.toByteArray()
        }

        fun le32(value: Int) = byteArrayOf(value.toByte(), (value shr 8).toByte(), (value shr 16).toByte(), (value shr 24).toByte())

        fun le64(value: Long) = ByteArray(8) { (value shr (8 * it)).toByte() }

        fun crc(data: ByteArray): Int = CRC32().apply { update(data) }.value.toInt()

        /** An extra-area record: size (covering id and payload), id, payload. */
        fun record(id: Long, payload: ByteArray): ByteArray {
            val body = vint(id) + payload
            return vint(body.size.toLong()) + body
        }

        /** Compression information field for version, solid flag, method, dictionary main/fraction and compat bit. */
        fun compression(version: Int = 0, method: Int = 0, main: Int = 0, fraction: Int = 0, compat: Boolean = false, solid: Boolean = false): Long {
            var v = version.toLong() and 0x3F
            if (solid) v = v or 0x40
            v = v or ((method.toLong() and 0x7) shl 7)
            v = v or ((main.toLong() and 0x1F) shl 10)
            v = v or ((fraction.toLong() and 0x1F) shl 15)
            if (compat) v = v or (1L shl 20)
            return v
        }
    }
}
