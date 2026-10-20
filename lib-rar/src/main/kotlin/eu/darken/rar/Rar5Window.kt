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

// Kotlin port of the window handling in libarchive v3.8.10
// libarchive/archive_read_support_format_rar5.c (copy_string, circular_memcpy), with the
// single window buffer replaced by fixed-size chunks that are allocated as the stream grows.

package eu.darken.rar

/**
 * The LZ dictionary of one stream: a ring of [size] bytes stored in chunks. Storage grows
 * chunk by chunk up to min([size], bytes written) and existing chunks are never copied.
 * The last chunk is shorter when [size] is not a multiple of the chunk size, so a full window
 * holds exactly [size] bytes.
 *
 * Growth past [maxWindowBytes] throws [RarUnsupportedException] with
 * [RarUnsupportedReason.DictionaryTooLarge]; growth past [maxStorage] is a [RarCorruptException].
 */
internal class Rar5Window(
    val size: Long,
    chunkSize: Int,
    private val maxStorage: Long,
    private val maxWindowBytes: Long,
) {
    private val chunk = chunkFor(size, chunkSize)
    private val shift = Integer.numberOfTrailingZeros(chunk)
    private val mask = chunk - 1
    private val chunks = ArrayList<ByteArray>()

    /** Bytes of chunk storage allocated so far. */
    var allocated = 0L
        private set

    /** Bytes written since the stream started. */
    var position = 0L
        private set

    /** Ring index of [position]. */
    private var index = 0L

    /** Writes that end at or below this position need neither allocation nor a limit check. */
    private var growLimit = 0L

    fun put(value: Byte) {
        if (position >= growLimit) grow(position + 1)
        chunks[(index ushr shift).toInt()][index.toInt() and mask] = value
        position++
        if (++index == size) index = 0
    }

    /** Appends [length] bytes copied from [distance] bytes back; the caller checked 1 <= distance <= min(position, size). */
    fun copy(distance: Long, length: Int) {
        if (position + length > growLimit) grow(position + length)
        var src = index - distance
        if (src < 0) src += size
        var left = length
        while (left > 0) {
            val dst = chunks[(index ushr shift).toInt()]
            val dstOffset = index.toInt() and mask
            val from = chunks[(src ushr shift).toInt()]
            val fromOffset = src.toInt() and mask
            val n = minOf(left, dst.size - dstOffset, from.size - fromOffset)
            if (distance >= n) {
                System.arraycopy(from, fromOffset, dst, dstOffset, n)
            } else {
                // Overlapping run within one chunk: byte order matters, earlier output feeds later output.
                for (i in 0 until n) dst[dstOffset + i] = from[fromOffset + i]
            }
            left -= n
            position += n
            index += n
            if (index == size) index = 0
            src += n
            if (src == size) src = 0
        }
    }

    /** Ring index of stream position [streamPosition], which must still be inside the window. */
    fun indexOf(streamPosition: Long): Long {
        var i = index - (position - streamPosition)
        if (i < 0) i += size
        return i
    }

    fun byteAt(ringIndex: Long): Int = chunks[(ringIndex ushr shift).toInt()][ringIndex.toInt() and mask].toInt() and 0xFF

    fun next(ringIndex: Long): Long = if (ringIndex + 1 == size) 0 else ringIndex + 1

    /** Copies [length] bytes starting at stream position [streamPosition] into [target]. */
    fun copyOut(streamPosition: Long, target: ByteArray, offset: Int, length: Int) {
        var i = indexOf(streamPosition)
        var out = offset
        var left = length
        while (left > 0) {
            val from = chunks[(i ushr shift).toInt()]
            val fromOffset = i.toInt() and mask
            val n = minOf(left, from.size - fromOffset)
            System.arraycopy(from, fromOffset, target, out, n)
            out += n
            left -= n
            i += n
            if (i == size) i = 0
        }
    }

    private fun grow(end: Long) {
        val needed = minOf(end, size)
        if (needed > maxWindowBytes) {
            throw RarUnsupportedException(RarUnsupportedReason.DictionaryTooLarge(size, maxWindowBytes))
        }
        while (allocated < needed) {
            val length = minOf(chunk.toLong(), size - allocated)
            if (allocated + length > maxStorage) {
                throw RarCorruptException("Solid stream needs more dictionary than its first entry reserved")
            }
            chunks.add(ByteArray(length.toInt()))
            allocated += length
        }
        growLimit = if (allocated == size) Long.MAX_VALUE else minOf(allocated, maxWindowBytes)
    }

    companion object {
        const val DEFAULT_CHUNK_SIZE = 256 * 1024

        /** [chunkSize] (a power of two), reduced to the largest power of two not above [windowSize]. */
        fun chunkFor(windowSize: Long, chunkSize: Int): Int {
            require(chunkSize > 0 && chunkSize and (chunkSize - 1) == 0) { "Chunk size must be a power of two" }
            return if (chunkSize.toLong() <= windowSize) chunkSize else java.lang.Long.highestOneBit(windowSize).toInt()
        }

        /** Chunk storage needed to hold the first [bytes] bytes of a window of [windowSize] bytes. */
        fun storageFor(bytes: Long, windowSize: Long, chunk: Int): Long {
            if (bytes <= 0) return 0
            val rounded = (bytes + chunk - 1) / chunk * chunk
            return minOf(rounded, windowSize)
        }
    }
}
