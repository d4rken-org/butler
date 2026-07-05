package eu.darken.butler.editor.core.engine.text

/**
 * Read-only view of the original (on-disk) document in char coordinates.
 * All offsets are UTF-16 code units; byte offsets are logical (post-BOM).
 *
 * Range-based methods treat the range as isolated text (see [TextMetrics]): a CRLF fully
 * inside the range counts once, a trailing lone `\r` counts as a break at the range end.
 */
interface OriginalDocument {
    val charLength: Long
    val byteLength: Long
    val lineBreakCount: Long

    suspend fun readChars(charStart: Long, charEnd: Long): String

    /** Byte offset of the given char offset; must be called on code-point boundaries only. */
    suspend fun charToByte(charOffset: Long): Long

    suspend fun countLineBreaks(charStart: Long, charEnd: Long): Long

    /**
     * Char offset (document coordinates) just after the [n]th break in `[charStart, charEnd)`,
     * 1-based [n]. CRLF seams internal to the range are joined; range edges are isolated.
     */
    suspend fun findNthLineBreak(charStart: Long, charEnd: Long, n: Long): Long
}
