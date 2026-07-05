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
     * Byte offset within [blockBytes] after decoding [charOffset] chars.
     *
     * Walks the bytes with a decoder instead of re-encoding chars, because malformed input
     * decoded as U+FFFD has a source byte length unrelated to the replacement char.
     * [charOffset] must be a code-point boundary (piece splits are snapped before calling).
     */
    fun charToByteInBlock(blockBytes: ByteArray, charset: Charset, charOffset: Int): Int {
        if (charOffset <= 0) return 0
        val decoder = charset.newDecoder()
            .onMalformedInput(CodingErrorAction.REPLACE)
            .onUnmappableCharacter(CodingErrorAction.REPLACE)
        val input = ByteBuffer.wrap(blockBytes)
        val output = CharBuffer.allocate(2)
        var chars = 0
        while (chars < charOffset && input.hasRemaining()) {
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
        return input.position()
    }
}
