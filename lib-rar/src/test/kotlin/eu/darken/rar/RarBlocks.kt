package eu.darken.rar

/** Walks the base blocks of a well-formed archive, for tests that edit fixtures in place. */
internal object RarBlocks {
    /** [start, headerEnd) is CRC, size field and header; [headerEnd, dataEnd) the block's data. */
    class Raw(val start: Int, val headerEnd: Int, val dataEnd: Int, val type: Long, val name: String?)

    fun parse(bytes: ByteArray): List<Raw> {
        val blocks = mutableListOf<Raw>()
        var pos = RarSignature.RAR5_SIZE
        while (pos + 4 < bytes.size) {
            var size = 0
            var sizeLength = 0
            while (true) {
                val b = bytes[pos + 4 + sizeLength].toInt() and 0xFF
                size = size or ((b and 0x7F) shl (7 * sizeLength))
                sizeLength++
                if (b and 0x80 == 0) break
            }
            val headerEnd = pos + 4 + sizeLength + size
            val block = Block.parse(bytes.copyOfRange(pos + 4, headerEnd), sizeLength)
            val name = when (block.type) {
                HeaderType.FILE, HeaderType.SERVICE -> FileHeader.parse(block, block.type == HeaderType.SERVICE, RarLimits()).name
                else -> null
            }
            val dataEnd = headerEnd + block.dataSize.toInt()
            blocks += Raw(pos, headerEnd, dataEnd, block.type, name)
            // RAR pads volumes after their end header.
            if (block.type == HeaderType.ENDARC) break
            pos = dataEnd
        }
        return blocks
    }

    fun file(bytes: ByteArray, name: String): Raw = parse(bytes).single { it.type == HeaderType.FILE && it.name == name }

    /** The packed data of file entry [name]. */
    fun data(bytes: ByteArray, name: String): ByteArray = file(bytes, name).let { bytes.copyOfRange(it.headerEnd, it.dataEnd) }

    /** The parsed base header of [block]. */
    fun header(bytes: ByteArray, block: Raw): Block {
        var sizeLength = 1
        while (bytes[block.start + 4 + sizeLength - 1].toInt() and 0x80 != 0) sizeLength++
        return Block.parse(bytes.copyOfRange(block.start + 4, block.headerEnd), sizeLength)
    }

    /** The encryption record of file entry [name]. */
    fun crypt(bytes: ByteArray, name: String): FileCrypt =
        checkNotNull(FileHeader.parse(header(bytes, file(bytes, name)), service = false, limits = RarLimits()).crypt)

    /**
     * The archive encryption header that starts an archive with encrypted headers. Only this first
     * block can be walked; everything after it is encrypted.
     */
    fun cryptHeader(bytes: ByteArray): Raw {
        val start = RarSignature.RAR5_SIZE
        var size = 0
        var sizeLength = 0
        while (true) {
            val b = bytes[start + 4 + sizeLength].toInt() and 0xFF
            size = size or ((b and 0x7F) shl (7 * sizeLength))
            sizeLength++
            if (b and 0x80 == 0) break
        }
        val end = start + 4 + sizeLength + size
        val raw = Raw(start, end, end, HeaderType.CRYPT, null)
        check(header(bytes, raw).type == HeaderType.CRYPT) { "Not a header-encrypted archive" }
        return raw
    }

    /** Recomputes [block]'s header CRC after its header bytes were edited. */
    fun reseal(bytes: ByteArray, block: Raw) {
        RarBuilder.le32(RarBuilder.crc(bytes.copyOfRange(block.start + 4, block.headerEnd))).copyInto(bytes, block.start)
    }

    /** Offset of [needle] inside bytes[from, to). */
    fun find(bytes: ByteArray, needle: ByteArray, from: Int, to: Int): Int =
        (from..to - needle.size).first { i -> needle.indices.all { bytes[i + it] == needle[it] } }
}
