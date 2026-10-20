package eu.darken.rar

import java.io.ByteArrayOutputStream

/**
 * Writes one RAR5 compressed block with its Huffman tables for crafted tests, then literals and
 * filter records. [bitLengths] are the 20 code lengths of the table that encodes [lengths], the
 * literal, distance, low-distance and repeat tables in that order. Codes are canonical, shortest
 * first and by symbol within a length; the defaults give every symbol a code (literal byte b is
 * the 9-bit code b), which leaves most tables under-subscribed, as valid RAR streams may be.
 */
internal class LzBlock(
    private val bitLengths: IntArray = IntArray(DecodeTable.HUFF_BC) { 5 },
    private val lengths: IntArray = DEFAULT_LENGTHS,
) {
    private val out = BitWriter()
    private val bitCodes = canonical(bitLengths)
    private val literalLengths = lengths.copyOfRange(0, DecodeTable.HUFF_NC)
    private val literalCodes = canonical(literalLengths)

    init {
        require(bitLengths.size == DecodeTable.HUFF_BC && lengths.size == DecodeTable.HUFF_TABLE_SIZE)
        for (length in bitLengths) {
            out.write(length, 4)
            // 15 escapes a run of zeros; a count of 0 makes it the length 15 itself.
            if (length == 15) out.write(0, 4)
        }
        for (length in lengths) out.write(bitCodes[length], bitLengths[length])
    }

    fun literals(bytes: ByteArray) = apply { bytes.forEach { literal(it.toInt() and 0xFF) } }

    fun literal(value: Int) = apply { symbol(value) }

    /** The low [count] bits of [value] as they are, for codes the tables do not assign. */
    fun bits(value: Int, count: Int) = apply { out.write(value, count) }

    /** A filter record: [start] is relative to the output written so far, [channels] only for delta. */
    fun filter(start: Long, length: Long, type: Int, channels: Int = 1) = apply {
        symbol(FILTER)
        filterData(start)
        filterData(length)
        out.write(type, 3)
        if (type == Rar5Filters.DELTA) out.write(channels - 1, 5)
    }

    /** The block header (flags, checksum, size) followed by everything written. */
    fun build(last: Boolean = true): ByteArray {
        val payload = out.toByteArray()
        require(payload.isNotEmpty())
        val bitSize = (out.bits - 8L * (payload.size - 1)).toInt()
        val size = payload.size
        val sizeBytes = when {
            size < 0x100 -> 1
            size < 0x10000 -> 2
            else -> 3
        }
        var flags = (bitSize - 1) or ((sizeBytes - 1) shl 3) or TABLES
        if (last) flags = flags or LAST
        val check = (0x5A xor flags xor size xor (size ushr 8) xor (size ushr 16)) and 0xFF
        val header = byteArrayOf(flags.toByte(), check.toByte()) + ByteArray(sizeBytes) { (size ushr (8 * it)).toByte() }
        return header + payload
    }

    private fun symbol(value: Int) {
        require(literalLengths[value] > 0) { "Symbol $value has no code" }
        out.write(literalCodes[value], literalLengths[value])
    }

    /** 2 bits for the byte count minus one, then the value's bytes, least significant first. */
    private fun filterData(value: Long) {
        require(value in 0..0xFFFFFFFFL)
        val count = when {
            value < 0x100 -> 1
            value < 0x10000 -> 2
            value < 0x1000000 -> 3
            else -> 4
        }
        out.write(count - 1, 2)
        for (i in 0 until count) out.write(((value ushr (8 * i)) and 0xFF).toInt(), 8)
    }

    private class BitWriter {
        private val bytes = ByteArrayOutputStream()
        private var current = 0
        private var used = 0
        var bits = 0L
            private set

        /** The low [count] bits of [value], most significant first. */
        fun write(value: Int, count: Int) {
            for (i in count - 1 downTo 0) {
                current = (current shl 1) or ((value ushr i) and 1)
                bits++
                if (++used == 8) {
                    bytes.write(current)
                    current = 0
                    used = 0
                }
            }
        }

        fun toByteArray(): ByteArray = if (used == 0) bytes.toByteArray() else bytes.toByteArray() + (current shl (8 - used)).toByte()
    }

    companion object {
        const val FILTER = 256
        private const val TABLES = 0x80
        private const val LAST = 0x40

        /** Literal/length 9 bits, distance 6, low distance 4, repeat 6. */
        val DEFAULT_LENGTHS: IntArray = IntArray(DecodeTable.HUFF_NC) { 9 } +
            IntArray(DecodeTable.HUFF_DC) { 6 } + IntArray(DecodeTable.HUFF_LDC) { 4 } + IntArray(DecodeTable.HUFF_RC) { 6 }

        /** Canonical codes for [lengths]; over-subscribed lengths give overlapping codes, which the reader rejects. */
        fun canonical(lengths: IntArray): IntArray {
            val count = IntArray(16)
            for (length in lengths) if (length > 0) count[length]++
            val next = IntArray(16)
            var code = 0
            for (bits in 1 until 16) {
                code = (code + count[bits - 1]) shl 1
                next[bits] = code
            }
            return IntArray(lengths.size) { if (lengths[it] > 0) next[lengths[it]]++ else 0 }
        }
    }
}
