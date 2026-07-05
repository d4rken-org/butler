package eu.darken.butler.editor.core.engine.text

/**
 * One span of document content, referencing either the immutable original file or the
 * append-only add buffer. Content is never mutated; edits split pieces and insert new ones.
 *
 * [lineBreakCount] counts universal breaks with the piece treated as isolated text; a CRLF
 * straddling two pieces is detected via [endsWithCr]/[startsWithLf] and corrected during
 * aggregation (see [PieceTable]).
 */
sealed interface Piece {
    val charCount: Long
    val lineBreakCount: Long
    val startsWithLf: Boolean
    val endsWithCr: Boolean
    val endsWithBreak: Boolean

    /**
     * Range of the original file. [byteStart]/[byteLen] are logical (post-BOM) byte offsets,
     * immutable after load; [charStart] is the char offset within the original document.
     */
    data class Original(
        val byteStart: Long,
        val byteLen: Long,
        val charStart: Long,
        override val charCount: Long,
        override val lineBreakCount: Long,
        override val startsWithLf: Boolean,
        override val endsWithCr: Boolean,
        override val endsWithBreak: Boolean,
    ) : Piece

    /** Range of the add buffer, in char offsets. */
    data class Added(
        val addStart: Int,
        val addLen: Int,
        override val lineBreakCount: Long,
        override val startsWithLf: Boolean,
        override val endsWithCr: Boolean,
        override val endsWithBreak: Boolean,
    ) : Piece {
        override val charCount: Long get() = addLen.toLong()
    }
}
