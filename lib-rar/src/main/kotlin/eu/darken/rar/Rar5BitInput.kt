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

// Kotlin port of the bit reader in libarchive v3.8.10
// libarchive/archive_read_support_format_rar5.c (read_bits_16, read_bits_32, skip_bits,
// read_consume_bits), reading through a fixed buffer instead of a whole block in memory.

package eu.darken.rar

/** Supplies an entry's packed bytes: at least one byte per call, or -1 once all of them were read. */
internal fun interface PackedSource {
    fun read(buffer: ByteArray, offset: Int, length: Int): Int
}

/**
 * MSB-first bit reader over one entry's packed data. Reads never pull bytes past [limit]
 * (the current block's end); bytes at or past it read as zero, whatever the buffer holds.
 */
internal class Rar5BitInput {
    private val buf = ByteArray(BUFFER_SIZE)
    private var source = PackedSource { _, _, _ -> -1 }

    /** Null while unknown (an entry split across volumes): then the data ends where the source does. */
    var packedSize: Long? = 0L
        private set

    /** Packed offset of buf[0]. */
    private var base = 0L
    private var fill = 0
    private var pos = 0
    private var bit = 0
    private var limit = 0L

    /** Buffer index of the first byte at or past [limit] or past the buffered data. */
    private var validEnd = 0

    val bytePosition: Long get() = base + pos

    fun reset(packedSize: Long?, source: PackedSource) {
        this.packedSize = packedSize
        this.source = source
        base = 0
        fill = 0
        pos = 0
        bit = 0
        limit = 0
        validEnd = 0
    }

    /** Moves to the byte at packed offset [target], which is not before the buffered data. */
    fun seek(target: Long) {
        bit = 0
        val end = base + fill
        if (target <= end) {
            check(target >= base) { "Seek before the buffered data" }
            pos = (target - base).toInt()
        } else {
            var skip = target - end
            base = end
            fill = 0
            pos = 0
            while (skip > 0) {
                val n = source.read(buf, 0, minOf(skip, BUFFER_SIZE.toLong()).toInt())
                if (n <= 0) throw ArchiveInput.unexpectedEnd()
                base += n
                skip -= n
            }
        }
        updateValidEnd()
    }

    /** A byte-aligned byte anywhere in the packed data, for block headers. */
    fun readByte(): Int {
        val size = packedSize
        limit = size ?: Long.MAX_VALUE
        updateValidEnd()
        if (size != null && bytePosition >= size) throw RarCorruptException("Compressed data ends inside a block header")
        ensure(1)
        return at(pos++)
    }

    /** True if the packed data ends at or before [offset]; with an unknown size this reads ahead to find out. */
    fun endsAt(offset: Long): Boolean {
        packedSize?.let { return offset >= it }
        seek(offset)
        if (pos < fill) return false
        base += fill
        fill = 0
        pos = 0
        val n = source.read(buf, 0, BUFFER_SIZE)
        if (n > 0) fill = n
        updateValidEnd()
        return n <= 0
    }

    /** True if a block ending at packed offset [end] reaches past the known packed size. */
    fun beyondEnd(end: Long): Boolean = packedSize?.let { end > it } ?: false

    /** Starts a block whose data ends at packed offset [end]; the position must be byte-aligned. */
    fun beginBlock(end: Long) {
        limit = end
        bit = 0
        updateValidEnd()
    }

    /** True once the position passed the block's last valid bit ([bitSize] bits of its last byte). */
    fun blockFinished(bitSize: Int): Boolean {
        val at = base + pos
        return at >= limit || (at == limit - 1 && bit >= bitSize)
    }

    /** The next 16 bits (read_bits_16). */
    fun peek16(): Int {
        if (base + pos >= limit) throw RarCorruptException("Premature end of a compressed block")
        ensure(3)
        val v = (at(pos) shl 16) or (at(pos + 1) shl 8) or at(pos + 2)
        return (v ushr (8 - bit)) and 0xFFFF
    }

    /** The next 32 bits (read_bits_32). */
    fun peek32(): Int {
        if (base + pos >= limit) throw RarCorruptException("Premature end of a compressed block")
        ensure(5)
        var v = (at(pos) shl 24) or (at(pos + 1) shl 16) or (at(pos + 2) shl 8) or at(pos + 3)
        v = v shl bit
        v = v or (at(pos + 4) ushr (8 - bit))
        return v
    }

    fun skip(bits: Int) {
        val n = bit + bits
        pos += n ushr 3
        bit = n and 7
    }

    /** Reads and consumes [n] bits, 1..16 (read_consume_bits). */
    fun readBits(n: Int): Int {
        val v = peek16() ushr (16 - n)
        skip(n)
        return v
    }

    /** The next 4 bits without the end-of-block check; Huffman bit lengths are stored as nibbles. */
    fun nibble(): Int {
        ensure(1)
        val v = (at(pos) ushr (4 - bit)) and 0xF
        skip(4)
        return v
    }

    private fun at(i: Int): Int = if (i < validEnd) buf[i].toInt() and 0xFF else 0

    private fun updateValidEnd() {
        validEnd = minOf(fill.toLong(), limit - base).toInt()
    }

    /** Makes buf[pos, pos + n) readable, or as much of it as lies before [limit]. */
    private fun ensure(n: Int) {
        if (pos + n <= validEnd) return
        if (base + fill >= limit) return
        if (pos > 0) {
            val keep = fill - pos
            System.arraycopy(buf, pos, buf, 0, keep)
            base += pos
            fill = keep
            pos = 0
        }
        while (fill < pos + n && base + fill < limit) {
            val want = minOf((BUFFER_SIZE - fill).toLong(), limit - (base + fill)).toInt()
            val r = source.read(buf, fill, want)
            if (r <= 0) throw ArchiveInput.unexpectedEnd()
            fill += r
        }
        updateValidEnd()
    }

    private companion object {
        const val BUFFER_SIZE = 64 * 1024
    }
}
