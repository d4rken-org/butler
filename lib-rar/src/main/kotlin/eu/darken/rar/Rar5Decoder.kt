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

// Kotlin port of the RAR5 LZ decoder in libarchive v3.8.10
// libarchive/archive_read_support_format_rar5.c (create_decode_tables, decode_number,
// parse_tables, parse_block_header, parse_filter_data, parse_filter, decode_code_length,
// copy_string, do_uncompress_block, process_block, apply_filters, do_uncompress_file).

package eu.darken.rar

/** Canonical Huffman decoding table (struct decode_table). */
internal class DecodeTable {
    var size = 0
        private set
    var quickBits = 0
        private set
    val decodeLen = IntArray(16)
    val decodePos = IntArray(16)
    val quickLen = IntArray(1 shl 10)
    val quickNum = IntArray(1 shl 10)
    val decodeNum = IntArray(HUFF_NC)
    private val counts = IntArray(16)
    private val nextPos = IntArray(16)

    /** Builds the table from [size] bit lengths at lengths[offset]; false for an over-subscribed code. */
    fun build(lengths: ByteArray, offset: Int, size: Int): Boolean {
        counts.fill(0)
        decodeNum.fill(0)
        this.size = size
        quickBits = if (size == HUFF_NC) 10 else 7
        for (i in 0 until size) counts[lengths[offset + i].toInt() and 15]++
        counts[0] = 0
        decodePos[0] = 0
        decodeLen[0] = 0
        var upperLimit = 0
        for (i in 1 until 16) {
            upperLimit += counts[i]
            decodeLen[i] = upperLimit shl (16 - i)
            decodePos[i] = decodePos[i - 1] + counts[i - 1]
            upperLimit = upperLimit shl 1
        }
        if (upperLimit > 65536) return false

        decodePos.copyInto(nextPos)
        for (i in 0 until size) {
            val length = lengths[offset + i].toInt() and 15
            if (length > 0) decodeNum[nextPos[length]++] = i
        }

        var currentLength = 1
        for (code in 0 until (1 shl quickBits)) {
            val bitField = code shl (16 - quickBits)
            while (currentLength < 16 && bitField >= decodeLen[currentLength]) currentLength++
            quickLen[code] = currentLength
            val dist = (bitField - decodeLen[currentLength - 1]) ushr (16 - currentLength)
            val pos = decodePos[currentLength and 15] + dist
            quickNum[code] = if (currentLength < 16 && pos < size) decodeNum[pos] else 0
        }
        return true
    }

    companion object {
        const val HUFF_BC = 20
        const val HUFF_NC = 306
        const val HUFF_DC = 64
        const val HUFF_LDC = 16
        const val HUFF_RC = 44
        const val HUFF_TABLE_SIZE = HUFF_NC + HUFF_DC + HUFF_LDC + HUFF_RC
    }
}

/**
 * Decodes one RAR5 LZ stream: the entries from a non-solid entry through the solid entries
 * that continue it. History, Huffman tables, repeat distances and the last match length carry
 * over between entries; filters, block state and output positions are per entry.
 *
 * Output is produced incrementally: [read] decodes until some bytes are ready and returns
 * them, so unflushed data never exceeds half the window plus one match.
 */
internal class Rar5Decoder(
    windowSize: Long,
    chunkSize: Int,
    maxStorage: Long,
    private val limits: RarLimits,
    private val cancellation: () -> Unit,
    private val filterCounts: IntArray,
) {
    val window = Rar5Window(windowSize, chunkSize, maxStorage, limits.maxWindowBytes)

    private val input = Rar5BitInput()
    private val bd = DecodeTable()
    private val ld = DecodeTable()
    private val dd = DecodeTable()
    private val ldd = DecodeTable()
    private val rd = DecodeTable()
    private val bitLengths = ByteArray(DecodeTable.HUFF_BC)
    private val tableLengths = ByteArray(DecodeTable.HUFF_TABLE_SIZE)
    private var tablesRead = false
    private val distCache = LongArray(4)
    private var lastLength = 0

    // Per entry; positions are relative to the entry's first byte.
    private var size: Long? = null
    private var entryStart = 0L
    private var flushed = 0L
    private val filters = ArrayDeque<Filter>()
    private var pendingFilterBytes = 0L

    /** End of the latest filter of this entry; a new filter must not start before it. */
    private var lastFilterEnd = -1L
    private var filterOut = ByteArray(0)
    private var filterOutPos = 0
    private var filterOutLength = 0
    private var blockOpen = false
    private var nextBlock = 0L
    private var bitSize = 0
    private var lastBlock = false
    private var ended = false

    /** The declared size was delivered; the rest of the entry's compressed data must produce nothing. */
    private var finishing = false
    private var sinceCancellation = 0

    private val written: Long get() = window.position - entryStart

    /**
     * Starts the next entry of this stream; [size] null means the unpacked size is unknown,
     * [packedSize] null that the packed data ends where [source] does.
     */
    fun startEntry(packedSize: Long?, size: Long?, source: PackedSource) {
        input.reset(packedSize, source)
        this.size = size
        entryStart = window.position
        flushed = 0
        filters.clear()
        pendingFilterBytes = 0
        lastFilterEnd = -1
        filterOutPos = 0
        filterOutLength = 0
        blockOpen = false
        nextBlock = 0
        lastBlock = false
        ended = false
        finishing = false
    }

    /** Up to [length] bytes of the current entry's output, or -1 at its end. */
    fun read(target: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        while (true) {
            val declared = size
            if (filterOutPos < filterOutLength) {
                val n = minOf(length, filterOutLength - filterOutPos)
                System.arraycopy(filterOut, filterOutPos, target, offset, n)
                filterOutPos += n
                flushed += n
                if (declared != null && flushed == declared) finishEntry()
                return n
            }
            if (declared != null && flushed == declared) {
                finishEntry()
                return -1
            }
            val first = filters.firstOrNull()
            if (first != null && first.start < flushed) throw RarCorruptException("Filter starts inside data already written")
            val ready = if (first != null) minOf(first.start, written) else written
            if (flushed < ready) {
                val n = minOf(length.toLong(), ready - flushed).toInt()
                window.copyOut(entryStart + flushed, target, offset, n)
                flushed += n
                if (declared != null && flushed == declared) finishEntry()
                return n
            }
            if (first != null && flushed == first.start && written >= first.start + first.length) {
                filters.removeFirst()
                pendingFilterBytes -= first.length
                runFilter(first)
                continue
            }
            if (ended) {
                if (declared != null) throw RarCorruptException("Compressed data ended after $flushed of $declared bytes")
                if (first != null && first.start < written) throw RarCorruptException("Compressed data ended inside a filtered range")
                return -1
            }
            val goal = if (first != null && flushed == first.start) first.start + first.length else flushed + OUTPUT_STEP
            decode(goal)
        }
    }

    /**
     * Once a known-size entry delivered its declared size, decodes the rest of its compressed data
     * up to its end, before the last bytes are returned. Any further output, or a filter (which can
     * only cover data past the declared size), is corrupt.
     */
    private fun finishEntry() {
        if (ended) return
        finishing = true
        decode(Long.MAX_VALUE)
    }

    private fun decode(goal: Long) {
        while (written < goal && !ended) {
            if (!blockOpen) {
                if (!openBlock()) ended = true
            } else if (decodeBlock(goal)) {
                blockOpen = false
                if (lastBlock) ended = true
            }
        }
    }

    /** Reads the next block header (parse_block_header) and its tables; false if no data is left. */
    private fun openBlock(): Boolean {
        cancellation()
        if (input.endsAt(nextBlock)) return false
        input.seek(nextBlock)
        val flags = input.readByte()
        val checksum = input.readByte()
        val byteCount = (flags ushr 3) and 7
        if (byteCount > 2) throw RarCorruptException("Unsupported block header size ${byteCount + 1}")
        var blockSize = 0
        for (i in 0..byteCount) blockSize = blockSize or (input.readByte() shl (8 * i))
        val expected = (0x5A xor flags xor blockSize xor (blockSize ushr 8) xor (blockSize ushr 16)) and 0xFF
        if (expected != checksum) throw RarCorruptException("Compressed block header checksum mismatch")
        val start = input.bytePosition
        if (input.beyondEnd(start + blockSize)) throw RarCorruptException("Compressed block exceeds the entry's data")
        nextBlock = start + blockSize
        bitSize = 1 + (flags and 7)
        lastBlock = flags and 0x40 != 0
        input.beginBlock(nextBlock)
        blockOpen = true
        if (flags and 0x80 != 0) parseTables()
        return true
    }

    private fun parseTables() {
        cancellation()
        var w = 0
        while (w < DecodeTable.HUFF_BC) {
            if (input.bytePosition >= nextBlock) throw RarCorruptException("Truncated data in Huffman tables")
            val value = input.nibble()
            if (value == ESCAPE) {
                val count = input.nibble()
                if (count == 0) {
                    bitLengths[w++] = ESCAPE.toByte()
                } else {
                    var k = 0
                    while (k < count + 2 && w < DecodeTable.HUFF_BC) {
                        bitLengths[w++] = 0
                        k++
                    }
                }
            } else {
                bitLengths[w++] = value.toByte()
            }
        }
        if (!bd.build(bitLengths, 0, DecodeTable.HUFF_BC)) throw RarCorruptException("Invalid Huffman bit length table")

        var i = 0
        while (i < DecodeTable.HUFF_TABLE_SIZE) {
            val num = decodeNumber(bd)
            when {
                num < 16 -> tableLengths[i++] = num.toByte()
                num < 18 -> {
                    if (i == 0) throw RarCorruptException("Huffman table repeats a length before the first one")
                    var n = repeatCount(num == 16)
                    while (n-- > 0 && i < DecodeTable.HUFF_TABLE_SIZE) {
                        tableLengths[i] = tableLengths[i - 1]
                        i++
                    }
                }
                else -> {
                    var n = repeatCount(num == 18)
                    while (n-- > 0 && i < DecodeTable.HUFF_TABLE_SIZE) tableLengths[i++] = 0
                }
            }
        }
        var at = 0
        if (!ld.build(tableLengths, at, DecodeTable.HUFF_NC)) throw RarCorruptException("Invalid literal table")
        at += DecodeTable.HUFF_NC
        if (!dd.build(tableLengths, at, DecodeTable.HUFF_DC)) throw RarCorruptException("Invalid distance table")
        at += DecodeTable.HUFF_DC
        if (!ldd.build(tableLengths, at, DecodeTable.HUFF_LDC)) throw RarCorruptException("Invalid low distance table")
        at += DecodeTable.HUFF_LDC
        if (!rd.build(tableLengths, at, DecodeTable.HUFF_RC)) throw RarCorruptException("Invalid repeat length table")
        tablesRead = true
    }

    /** 3 bits + 3 for the short repeat codes, 7 bits + 11 for the long ones. */
    private fun repeatCount(short: Boolean): Int {
        val bits = input.peek16()
        return if (short) {
            input.skip(3)
            (bits ushr 13) + 3
        } else {
            input.skip(7)
            (bits ushr 9) + 11
        }
    }

    private fun decodeNumber(table: DecodeTable): Int {
        val bitField = input.peek16() and 0xFFFE
        val quickBits = table.quickBits
        if (bitField < table.decodeLen[quickBits]) {
            val code = bitField ushr (16 - quickBits)
            input.skip(table.quickLen[code])
            return table.quickNum[code]
        }
        var bits = 15
        for (i in quickBits + 1 until 15) {
            if (bitField < table.decodeLen[i]) {
                bits = i
                break
            }
        }
        input.skip(bits)
        val dist = (bitField - table.decodeLen[bits - 1]) ushr (16 - bits)
        var pos = table.decodePos[bits] + dist
        if (pos >= table.size) pos = 0
        return table.decodeNum[pos]
    }

    /** Decodes until the block ends (true) or [goal] output bytes exist (false); do_uncompress_block. */
    private fun decodeBlock(goal: Long): Boolean {
        while (true) {
            if (written >= goal) return false
            if (input.blockFinished(bitSize)) return true
            if (!tablesRead) throw RarCorruptException("Compressed block without Huffman tables")
            val num = decodeNumber(ld)
            when {
                num < 256 -> {
                    val declared = size
                    if (declared != null && written >= declared) throw exceedsSize()
                    window.put(num.toByte())
                    produced(1)
                }
                num >= 262 -> match(num - 262)
                num == 256 -> parseFilter()
                num == 257 -> if (lastLength != 0) copyString(lastLength, distCache[0])
                else -> repeatMatch(num - 258)
            }
        }
    }

    private fun match(lengthCode: Int) {
        var length = codeLength(lengthCode)
        val slot = decodeNumber(dd)
        var dist = 1L
        var dbits = 0
        if (slot < 4) {
            dist += slot
        } else {
            dbits = slot / 2 - 1
            dist += (2L or (slot and 1).toLong()) shl dbits
        }
        if (dbits > 0) {
            if (dbits >= 4) {
                if (dbits > 4) {
                    val add = input.peek32()
                    input.skip(dbits - 4)
                    dist += (add ushr (36 - dbits)).toLong() shl 4
                }
                dist += decodeNumber(ldd)
            } else {
                dist += input.readBits(dbits)
            }
        }
        if (dist > 0x100) {
            length++
            if (dist > 0x2000) {
                length++
                if (dist > 0x40000) length++
            }
        }
        distCache[3] = distCache[2]
        distCache[2] = distCache[1]
        distCache[1] = distCache[0]
        distCache[0] = dist
        lastLength = length
        copyString(length, dist)
    }

    private fun repeatMatch(index: Int) {
        val dist = distCache[index]
        for (i in index downTo 1) distCache[i] = distCache[i - 1]
        distCache[0] = dist
        val length = codeLength(decodeNumber(rd))
        lastLength = length
        copyString(length, dist)
    }

    /** decode_code_length */
    private fun codeLength(code: Int): Int {
        if (code < 8) return 2 + code
        val lbits = code / 4 - 1
        return 2 + ((4 or (code and 3)) shl lbits) + input.readBits(lbits)
    }

    private fun copyString(length: Int, dist: Long) {
        val declared = size
        if (declared != null && length > declared - written) throw exceedsSize()
        if (dist < 1 || dist > window.position || dist > window.size) {
            throw RarCorruptException("Match distance $dist exceeds the available dictionary")
        }
        window.copy(dist, length)
        produced(length)
    }

    private fun exceedsSize() = if (finishing) {
        continuesPastSize()
    } else {
        RarCorruptException("Unpacked data exceeds the declared size of ${size} bytes")
    }

    private fun continuesPastSize() = RarCorruptException("Compressed data continues past the declared size of $size bytes")

    private fun produced(count: Int) {
        sinceCancellation += count
        if (sinceCancellation >= CANCELLATION_INTERVAL) {
            sinceCancellation = 0
            cancellation()
        }
    }

    /** parse_filter */
    private fun parseFilter() {
        if (finishing) throw continuesPastSize()
        val start = filterData()
        val length = filterData()
        val type = input.peek16() ushr 13
        input.skip(3)
        val blockStart = written + start
        // libarchive v3.8.10 (is_valid_filter_block_start) skips the overlap check when the previous
        // filter started at offset 0; overlapping filters are never valid, so it applies to all here.
        if (length < 4 || length > MAX_FILTER_LENGTH || length > window.size / 2 || blockStart < lastFilterEnd) {
            throw RarCorruptException("Invalid filter (start $blockStart, length $length)")
        }
        if (type >= Rar5Filters.TYPE_COUNT) throw RarUnsupportedException(RarUnsupportedReason.UnknownFilter(type), "Unsupported filter type $type")
        if (filters.size >= limits.maxPendingFilters) throw RarCorruptException("More than ${limits.maxPendingFilters} pending filters")
        if (pendingFilterBytes + length > limits.maxPendingFilterBytes) {
            throw RarCorruptException("Pending filters cover more than ${limits.maxPendingFilterBytes} bytes")
        }
        val channels = if (type == Rar5Filters.DELTA) input.readBits(5) + 1 else 0
        filters.addLast(Filter(type, channels, blockStart, length.toInt()))
        pendingFilterBytes += length
        lastFilterEnd = blockStart + length
    }

    /** parse_filter_data: 1 to 4 little-endian bytes. */
    private fun filterData(): Long {
        val count = input.readBits(2) + 1
        var data = 0L
        for (i in 0 until count) {
            data = data or ((input.peek16() ushr 8).toLong() shl (8 * i))
            input.skip(8)
        }
        return data
    }

    private fun runFilter(filter: Filter) {
        cancellation()
        if (filterOut.size < filter.length) filterOut = ByteArray(filter.length)
        val streamStart = entryStart + filter.start
        when (filter.type) {
            Rar5Filters.DELTA -> Rar5Filters.delta(window, streamStart, filter.length, filter.channels, filterOut, cancellation)
            Rar5Filters.E8, Rar5Filters.E8E9 -> {
                window.copyOut(streamStart, filterOut, 0, filter.length)
                Rar5Filters.e8e9(filterOut, filter.length, filter.start, filter.type == Rar5Filters.E8E9, cancellation)
            }
            else -> {
                window.copyOut(streamStart, filterOut, 0, filter.length)
                Rar5Filters.arm(filterOut, filter.length, filter.start, cancellation)
            }
        }
        filterCounts[filter.type]++
        filterOutPos = 0
        filterOutLength = filter.length
    }

    private class Filter(val type: Int, val channels: Int, val start: Long, val length: Int)

    private companion object {
        const val ESCAPE = 15
        const val MAX_FILTER_LENGTH = 0x400000L
        const val OUTPUT_STEP = 64 * 1024
        const val CANCELLATION_INTERVAL = 64 * 1024
    }
}
