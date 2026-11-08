package eu.darken.butler.common.files.text

import java.nio.charset.Charset

/**
 * Cuts a truncated buffer back to a character boundary, BEFORE it is decoded.
 *
 * A read cap lands on an arbitrary byte, and a sequence split across it does not merely lose one
 * character: a strict decode fails and the whole buffer falls back to a wrong charset. A buffer that
 * is not [truncated] ends at the real end of the file, so a broken sequence there is the file's own
 * and stays visible.
 */
object TextBoundary {

    fun trim(bytes: ByteArray, charset: Charset, truncated: Boolean): ByteArray {
        if (!truncated) return bytes
        return when (charset) {
            Charsets.UTF_16LE, Charsets.UTF_16BE -> trimUtf16(bytes, charset)
            Charsets.UTF_8 -> trimPartialUtf8(bytes)
            TextCharsets.GB18030 -> {
                val tail = Gb18030.incompleteTailLength(bytes)
                if (tail > 0) bytes.copyOf(bytes.size - tail) else bytes
            }

            else -> bytes
        }
    }

    /**
     * Like [trim], but cuts at the last line break where one exists, which also spares the reader
     * half a line of data that is not there. UTF-16 carries 0x0A inside a code unit, so it is only
     * trimmed. In UTF-8 and GB18030 0x0A never occurs inside a sequence, so the break is a boundary.
     */
    fun trimToLine(bytes: ByteArray, charset: Charset, truncated: Boolean): ByteArray {
        if (!truncated || charset == Charsets.UTF_16LE || charset == Charsets.UTF_16BE) {
            return trim(bytes, charset, truncated)
        }

        val lastBreak = bytes.lastIndexOf(NEWLINE)
        if (lastBreak >= 0) return bytes.copyOf(lastBreak)

        return trim(bytes, charset, truncated = true)
    }

    /**
     * Drops a trailing incomplete UTF-8 sequence, and only that: the scan is bounded by the longest
     * sequence there is and requires a real lead byte at the end of it, so a single-byte encoding -
     * where every high byte looks like a continuation - is left alone. Without that bound, a Latin-1
     * line of `£` (0xA3) would trim away to nothing.
     */
    private fun trimPartialUtf8(bytes: ByteArray): ByteArray {
        var continuations = 0
        while (continuations < MAX_UTF8_SEQUENCE - 1) {
            val index = bytes.size - 1 - continuations
            if (index < 0 || (bytes[index].toInt() and 0xC0) != 0x80) break
            continuations++
        }
        val leadIndex = bytes.size - 1 - continuations
        if (leadIndex < 0) return bytes

        val lead = bytes[leadIndex].toInt() and 0xFF
        val expected = when {
            lead and 0x80 == 0x00 -> 1
            lead and 0xE0 == 0xC0 -> 2
            lead and 0xF0 == 0xE0 -> 3
            lead and 0xF8 == 0xF0 -> 4
            // Not a lead byte at all, so this is not UTF-8 and any cut in it is already safe.
            else -> return bytes
        }
        return if (continuations + 1 < expected) bytes.copyOf(leadIndex) else bytes
    }

    /**
     * UTF-16 units are fixed width, which makes an even length enough - except for a cut between the
     * halves of a surrogate pair, which would decode to a replacement character.
     */
    private fun trimUtf16(bytes: ByteArray, charset: Charset): ByteArray {
        val even = if (bytes.size % 2 == 0) bytes else bytes.copyOf(bytes.size - 1)
        if (even.size < 2) return even

        val first = even[even.size - 2].toInt() and 0xFF
        val second = even[even.size - 1].toInt() and 0xFF
        val unit = if (charset == Charsets.UTF_16LE) (second shl 8) or first else (first shl 8) or second
        return if (unit in 0xD800..0xDBFF) even.copyOf(even.size - 2) else even
    }

    private const val NEWLINE = '\n'.code.toByte()
    private const val MAX_UTF8_SEQUENCE = 4
}
