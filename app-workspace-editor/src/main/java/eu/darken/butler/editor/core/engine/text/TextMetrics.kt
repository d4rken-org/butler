package eu.darken.butler.editor.core.engine.text

import eu.darken.butler.editor.core.engine.LineEnding
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

/**
 * Line-break and byte/char measurement helpers shared by the piece-table engine.
 *
 * "Universal" breaks are `\r\n`, `\n`, `\r`, with `\r\n` counted as ONE break. All range
 * parameters treat the range as isolated text: a trailing `\r` counts as a lone break even
 * if the character after the range is `\n` — callers correct such seams via piece/block flags.
 */
object TextMetrics {

    fun countBreaks(text: CharSequence, start: Int = 0, end: Int = text.length): Int {
        var count = 0
        var i = start
        while (i < end) {
            when (text[i]) {
                '\r' -> {
                    count++
                    if (i + 1 < end && text[i + 1] == '\n') i++
                }
                '\n' -> count++
            }
            i++
        }
        return count
    }

    /**
     * Index (relative to [start]) just after the [n]th break in `[start, end)`, 1-based [n].
     * For a CRLF the returned index is after the `\n`; for a trailing lone `\r` it is the range end.
     */
    fun endOfNthBreak(text: CharSequence, n: Int, start: Int = 0, end: Int = text.length): Int {
        require(n >= 1) { "Break ordinal must be >= 1, was $n" }
        var count = 0
        var i = start
        while (i < end) {
            when (text[i]) {
                '\r' -> {
                    count++
                    if (i + 1 < end && text[i + 1] == '\n') i++
                }
                '\n' -> count++
            }
            i++
            if (count == n) return i - start
        }
        throw IllegalArgumentException("Range has only $count breaks, requested $n")
    }

    fun startsWithLf(text: CharSequence): Boolean = text.isNotEmpty() && text[0] == '\n'

    fun endsWithCr(text: CharSequence): Boolean = text.isNotEmpty() && text[text.length - 1] == '\r'

    fun endsWithBreak(text: CharSequence): Boolean =
        text.isNotEmpty() && text[text.length - 1].let { it == '\n' || it == '\r' }

    /**
     * Whole-document detection from streaming counters; [lf] and [cr] are standalone counts
     * (not part of a CRLF). Same priority rules as the previous chunk engine.
     */
    fun detectLineEnding(crlf: Long, lf: Long, cr: Long): LineEnding = when {
        crlf > 0 && lf == 0L && cr == 0L -> LineEnding.CRLF
        lf > 0 && crlf == 0L && cr == 0L -> LineEnding.LF
        cr > 0 && lf == 0L && crlf == 0L -> LineEnding.CR
        crlf + lf + cr == 0L -> LineEnding.LF
        else -> LineEnding.MIXED
    }

    /**
     * Byte offset within [blockBytes] after decoding [charOffset] chars (UTF-16 code units).
     *
     * Structural, decoder-free for valid content: UTF-16 is `2 * charOffset` (clamped for a
     * malformed odd tail), single-byte charsets are the identity, and UTF-8 walks lead bytes
     * with full semantic validation (continuation ranges per lead, so overlong forms, encoded
     * surrogates, and > U+10FFFF never count as valid). Decoders proved unreliable for byte
     * ATTRIBUTION on Android/ICU, so they are only consulted for malformed UTF-8 runs, each
     * decoded in ISOLATION (the exact decode the block cache performs) to learn its
     * replacement-char count.
     *
     * Residual assumption (narrow, documented): a malformed run decoded in isolation yields the
     * same replacement-char count as within the whole block. Backstops fail loudly if violated:
     * the DecodeCache loader validates per-block char counts and the post-save rebase checks the
     * total length.
     */
    fun charToByteInBlock(blockBytes: ByteArray, charset: Charset, charOffset: Int): Int = when {
        charOffset <= 0 -> 0
        charset == Charsets.UTF_16LE || charset == Charsets.UTF_16BE ->
            // A final block of odd byte length decodes its dangling last byte to one replacement
            minOf(2L * charOffset, blockBytes.size.toLong()).toInt()
        isSingleByte(charset) -> minOf(charOffset, blockBytes.size)
        charset == Charsets.UTF_8 -> utf8CharToByte(blockBytes, charOffset)
        else -> error("Unsupported charset for byte mapping: $charset - vet its block-edge behavior first")
    }

    private fun isSingleByte(charset: Charset): Boolean =
        runCatching { charset.newEncoder().maxBytesPerChar() == 1f }.getOrDefault(false)

    private fun utf8CharToByte(bytes: ByteArray, charOffset: Int): Int {
        var byte = 0
        var chars = 0
        while (chars < charOffset && byte < bytes.size) {
            val seqLen = validUtf8SequenceLength(bytes, byte)
            if (seqLen > 0) {
                chars += if (seqLen == 4) 2 else 1
                byte += seqLen
                continue
            }
            // Malformed run: delimit structurally up to the next valid sequence start
            var runEnd = byte + 1
            while (runEnd < bytes.size &&
                runEnd - byte < MALFORMED_RUN_CAP &&
                validUtf8SequenceLength(bytes, runEnd) == 0
            ) {
                runEnd++
            }
            if (runEnd - byte >= MALFORMED_RUN_CAP) {
                // Never split a maximal subpart at the cap: back off a trailing valid prefix
                for (i in maxOf(byte + 1, runEnd - 3) until runEnd) {
                    if (isValidUtf8Prefix(bytes, i, runEnd)) {
                        runEnd = i
                        break
                    }
                }
            }
            val runChars = decodeIsolatedCharCount(bytes, byte, runEnd)
            if (chars + runChars <= charOffset) {
                chars += runChars
                byte = runEnd
            } else {
                // The offset lands between replacements inside the run: resolve it with a
                // per-char decoder walk over just this isolated slice
                return byte + decoderWalk(bytes, byte, runEnd, charOffset - chars)
            }
        }
        return byte
    }

    /** Length of the fully valid UTF-8 sequence starting at [index], or 0 if malformed/truncated. */
    private fun validUtf8SequenceLength(bytes: ByteArray, index: Int): Int {
        val b0 = bytes[index].toInt() and 0xFF
        return when {
            b0 < 0x80 -> 1
            b0 in 0xC2..0xDF -> if (hasContinuations(bytes, index, 1, 0x80..0xBF)) 2 else 0
            b0 == 0xE0 -> if (hasContinuations(bytes, index, 2, 0xA0..0xBF)) 3 else 0
            b0 == 0xED -> if (hasContinuations(bytes, index, 2, 0x80..0x9F)) 3 else 0
            b0 in 0xE1..0xEF -> if (hasContinuations(bytes, index, 2, 0x80..0xBF)) 3 else 0
            b0 == 0xF0 -> if (hasContinuations(bytes, index, 3, 0x90..0xBF)) 4 else 0
            b0 in 0xF1..0xF3 -> if (hasContinuations(bytes, index, 3, 0x80..0xBF)) 4 else 0
            b0 == 0xF4 -> if (hasContinuations(bytes, index, 3, 0x80..0x8F)) 4 else 0
            else -> 0
        }
    }

    /** [count] continuation bytes follow [index]; the first must be in [firstRange], the rest in 80..BF. */
    private fun hasContinuations(bytes: ByteArray, index: Int, count: Int, firstRange: IntRange): Boolean {
        if (index + count >= bytes.size) return false
        val first = bytes[index + 1].toInt() and 0xFF
        if (first !in firstRange) return false
        for (i in 2..count) {
            val b = bytes[index + i].toInt() and 0xFF
            if (b !in 0x80..0xBF) return false
        }
        return true
    }

    /** Whether bytes `[start, end)` are a PROPER prefix of a potentially valid UTF-8 sequence. */
    private fun isValidUtf8Prefix(bytes: ByteArray, start: Int, end: Int): Boolean {
        val available = end - start
        val b0 = bytes[start].toInt() and 0xFF
        val (needed, firstRange) = when {
            b0 in 0xC2..0xDF -> 2 to (0x80..0xBF)
            b0 == 0xE0 -> 3 to (0xA0..0xBF)
            b0 == 0xED -> 3 to (0x80..0x9F)
            b0 in 0xE1..0xEF -> 3 to (0x80..0xBF)
            b0 == 0xF0 -> 4 to (0x90..0xBF)
            b0 in 0xF1..0xF3 -> 4 to (0x80..0xBF)
            b0 == 0xF4 -> 4 to (0x80..0x8F)
            else -> return false
        }
        if (available >= needed) return false
        if (available >= 2 && (bytes[start + 1].toInt() and 0xFF) !in firstRange) return false
        for (i in 2 until available) {
            if ((bytes[start + i].toInt() and 0xFF) !in 0x80..0xBF) return false
        }
        return true
    }

    private fun decodeIsolatedCharCount(bytes: ByteArray, from: Int, to: Int): Int {
        val decoder = Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPLACE)
            .onUnmappableCharacter(CodingErrorAction.REPLACE)
        val output = CharBuffer.allocate(to - from + 2)
        decoder.decode(ByteBuffer.wrap(bytes, from, to - from), output, true)
        decoder.flush(output)
        return output.position()
    }

    /** Per-char decoder walk over the isolated `[from, to)` slice, returning bytes consumed for [targetChars]. */
    private fun decoderWalk(bytes: ByteArray, from: Int, to: Int, targetChars: Int): Int {
        val decoder = Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPLACE)
            .onUnmappableCharacter(CodingErrorAction.REPLACE)
        val input = ByteBuffer.wrap(bytes, from, to - from)
        val output = CharBuffer.allocate(2)
        var chars = 0
        while (chars < targetChars && input.hasRemaining()) {
            output.clear()
            output.limit(1)
            var result = decoder.decode(input, output, false)
            if (output.position() == 0 && result.isOverflow) {
                // Surrogate pairs are emitted atomically and need both slots
                output.limit(2)
                result = decoder.decode(input, output, false)
            }
            if (output.position() == 0) {
                // Partial or malformed tail only resolves with endOfInput
                output.limit(2)
                decoder.decode(input, output, true)
                decoder.flush(output)
                if (output.position() == 0) break
            }
            chars += output.position()
        }
        return input.position() - from
    }

    private const val MALFORMED_RUN_CAP = 4096
}
