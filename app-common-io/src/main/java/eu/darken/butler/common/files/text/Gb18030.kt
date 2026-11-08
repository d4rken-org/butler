package eu.darken.butler.common.files.text

/**
 * Byte structure of GB18030, read without decoding.
 *
 * | Bytes | Layout                                  | Example            | UTF-16 units |
 * |-------|-----------------------------------------|--------------------|--------------|
 * | 1     | `00..7F`                                | `41` = A           | 1            |
 * | 2     | `81..FE` + `40..7E` or `80..FE`         | `C4 E3` = 你       | 1            |
 * | 4     | `81..FE 30..39 81..FE 30..39`           | `81 30 B2 37` = ʘ  | 1            |
 * | 4     | same, lead `90..E3`                     | `94 39 FC 36` = 😀 | 2            |
 *
 * This is the structure only: a well-formed four-byte sequence can still be unassigned
 * (`85 30 81 30`), which only a strict decoder reports.
 */
object Gb18030 {

    /** Length of the sequence starting at [index], or 0 when it is malformed or runs past [end]. */
    fun sequenceLength(bytes: ByteArray, index: Int, end: Int = bytes.size): Int {
        if (index >= end) return 0
        val b0 = bytes[index].toInt() and 0xFF
        if (b0 <= 0x7F) return 1
        if (!isLead(b0) || index + 1 >= end) return 0

        val b1 = bytes[index + 1].toInt() and 0xFF
        if (b1 in 0x40..0x7E || b1 in 0x80..0xFE) return 2
        if (!isDigit(b1) || index + 3 >= end) return 0

        val b2 = bytes[index + 2].toInt() and 0xFF
        val b3 = bytes[index + 3].toInt() and 0xFF
        return if (isLead(b2) && isDigit(b3)) 4 else 0
    }

    /** UTF-16 units the sequence at [index] decodes to, or 0 when there is no complete sequence. */
    fun utf16Units(bytes: ByteArray, index: Int, end: Int = bytes.size): Int =
        when (sequenceLength(bytes, index, end)) {
            0 -> 0
            4 -> if ((bytes[index].toInt() and 0xFF) in 0x90..0xE3) 2 else 1
            else -> 1
        }

    /**
     * Length of the incomplete sequence that [bytes] ends with at [end], or 0 when the range ends on
     * a sequence boundary. `41 C4 E3 BA` ends with one byte of 好 (`BA C3`), so this returns 1.
     * [start] must be a sequence boundary.
     */
    fun incompleteTailLength(bytes: ByteArray, start: Int = 0, end: Int = bytes.size): Int {
        var index = resyncPoint(bytes, start, end)
        while (index < end) {
            val length = sequenceLength(bytes, index, end)
            if (length > 0) {
                index += length
                continue
            }
            if (isPrefix(bytes, index, end)) return end - index
            index++
        }
        return 0
    }

    /** Whether [index]..[end] is the start of a two- or four-byte sequence that [end] cut off. */
    private fun isPrefix(bytes: ByteArray, index: Int, end: Int): Boolean {
        val remaining = end - index
        if (remaining !in 1..3 || !isLead(bytes[index].toInt() and 0xFF)) return false
        if (remaining == 1) return true
        if (!isDigit(bytes[index + 1].toInt() and 0xFF)) return false
        return remaining == 2 || isLead(bytes[index + 2].toInt() and 0xFF)
    }

    /**
     * The scan has to run forward, because trail bytes overlap the lead range. A byte that can only
     * ever stand alone (space, punctuation, newline) is a boundary, so the scan starts after the last
     * one instead of at [start].
     */
    private fun resyncPoint(bytes: ByteArray, start: Int, end: Int): Int {
        for (index in end - 1 downTo start) {
            val b = bytes[index].toInt() and 0xFF
            if (b <= 0x2F || b in 0x3A..0x3F || b == 0x7F || b == 0xFF) return index + 1
        }
        return start
    }

    private fun isLead(b: Int) = b in 0x81..0xFE

    private fun isDigit(b: Int) = b in 0x30..0x39
}
