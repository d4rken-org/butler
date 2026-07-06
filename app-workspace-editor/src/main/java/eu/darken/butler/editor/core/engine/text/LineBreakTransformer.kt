package eu.darken.butler.editor.core.engine.text

/**
 * Streaming line-break normalizer for whole-document conversion: rewrites every break
 * ("\r\n", lone '\r', lone '\n') in a sequence of text chunks to [target], carrying a chunk-final
 * '\r' across chunk boundaries so a CRLF split between chunks (or pieces) converts exactly once.
 * Callers must invoke [flushTrailing] after the last chunk - a document-final bare '\r' is a
 * break too.
 */
internal class LineBreakTransformer(private val target: String) {

    private var pendingCr = false

    fun transform(chunk: String): String {
        if (chunk.isEmpty()) return chunk
        val sb = StringBuilder(chunk.length + target.length)
        var i = 0
        if (pendingCr) {
            pendingCr = false
            sb.append(target)
            if (chunk[0] == '\n') i = 1
        }
        while (i < chunk.length) {
            when (chunk[i]) {
                '\r' -> when {
                    i == chunk.lastIndex -> pendingCr = true
                    chunk[i + 1] == '\n' -> {
                        sb.append(target)
                        i++
                    }
                    else -> sb.append(target)
                }
                '\n' -> sb.append(target)
                else -> sb.append(chunk[i])
            }
            i++
        }
        return sb.toString()
    }

    /** Returns the converted break for a document-final bare '\r', or null if none is pending. */
    fun flushTrailing(): String? = if (pendingCr) {
        pendingCr = false
        target
    } else {
        null
    }
}
