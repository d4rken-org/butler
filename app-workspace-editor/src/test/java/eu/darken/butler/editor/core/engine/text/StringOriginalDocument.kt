package eu.darken.butler.editor.core.engine.text

/** Trivial in-memory [OriginalDocument] with a fake 1-byte-per-char byte layer. */
class StringOriginalDocument(private val text: String) : OriginalDocument {

    override val charLength: Long = text.length.toLong()
    override val byteLength: Long = text.length.toLong()
    override val lineBreakCount: Long = TextMetrics.countBreaks(text).toLong()

    override suspend fun readChars(charStart: Long, charEnd: Long): String =
        text.substring(charStart.toInt(), charEnd.toInt())

    override suspend fun charToByte(charOffset: Long): Long = charOffset

    override suspend fun countLineBreaks(charStart: Long, charEnd: Long): Long =
        TextMetrics.countBreaks(text, charStart.toInt(), charEnd.toInt()).toLong()

    override suspend fun findNthLineBreak(charStart: Long, charEnd: Long, n: Long): Long =
        charStart + TextMetrics.endOfNthBreak(text, n.toInt(), charStart.toInt(), charEnd.toInt())
}
