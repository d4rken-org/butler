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

// Kotlin port of the RAR5 filters in libarchive v3.8.10
// libarchive/archive_read_support_format_rar5.c (run_delta_filter, run_e8e9_filter,
// run_arm_filter).

package eu.darken.rar

/** Filter types as numbered in the RAR5 bit stream; they index [RarReader.filterCounts]. */
internal object Rar5Filters {
    const val DELTA = 0
    const val E8 = 1
    const val E8E9 = 2
    const val ARM = 3
    const val TYPE_COUNT = 4

    private const val FILE_SIZE = 0x1000000

    /**
     * Writes the delta-decoded [length] bytes that start at stream position [streamStart] into
     * [out]. The input is stored channel by channel, the output interleaves the channels.
     */
    fun delta(window: Rar5Window, streamStart: Long, length: Int, channels: Int, out: ByteArray, cancellation: () -> Unit) {
        var index = window.indexOf(streamStart)
        var done = 0
        for (channel in 0 until channels) {
            var previous = 0
            var dest = channel
            while (dest < length) {
                previous = (previous - window.byteAt(index)) and 0xFF
                out[dest] = previous.toByte()
                index = window.next(index)
                dest += channels
                if (++done and 0xFFFF == 0) cancellation()
            }
        }
    }

    /**
     * Converts the absolute x86 CALL (0xE8) and, with [extended], JMP (0xE9) targets in
     * out[0, length) back to relative ones. [fileOffset] is the block's offset in the file.
     */
    fun e8e9(out: ByteArray, length: Int, fileOffset: Long, extended: Boolean, cancellation: () -> Unit) {
        var i = 0
        while (i < length - 4) {
            val b = out[i].toInt() and 0xFF
            i++
            if (i and 0xFFFF == 0) cancellation()
            if (b == 0xE8 || (extended && b == 0xE9)) {
                val offset = ((i + fileOffset) % FILE_SIZE).toInt()
                val address = le32(out, i)
                if (address < 0) {
                    if (address + offset >= 0) putLe32(out, i, address + FILE_SIZE)
                } else {
                    if (address - FILE_SIZE < 0) putLe32(out, i, address - offset)
                }
                i += 4
            }
        }
    }

    /** Converts the absolute ARM BL (0xEB) targets in out[0, length) back to relative ones. */
    fun arm(out: ByteArray, length: Int, fileOffset: Long, cancellation: () -> Unit) {
        var i = 0
        while (i < length - 3) {
            if (out[i + 3].toInt() and 0xFF == 0xEB) {
                var offset = le32(out, i) and 0x00FFFFFF
                offset -= ((i + fileOffset) / 4).toInt()
                offset = (offset and 0x00FFFFFF) or 0xEB000000.toInt()
                putLe32(out, i, offset)
            }
            i += 4
            if (i and 0xFFFF == 0) cancellation()
        }
    }

    private fun le32(b: ByteArray, at: Int): Int =
        (b[at].toInt() and 0xFF) or
            ((b[at + 1].toInt() and 0xFF) shl 8) or
            ((b[at + 2].toInt() and 0xFF) shl 16) or
            ((b[at + 3].toInt() and 0xFF) shl 24)

    private fun putLe32(b: ByteArray, at: Int, value: Int) {
        b[at] = value.toByte()
        b[at + 1] = (value ushr 8).toByte()
        b[at + 2] = (value ushr 16).toByte()
        b[at + 3] = (value ushr 24).toByte()
    }
}
