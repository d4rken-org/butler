package eu.darken.rar

import java.io.IOException
import java.io.InputStream

/**
 * The current volume's stream with exact-length reads. Exceptions from the stream and
 * [cancellation] pass through unchanged; running out of data is a [RarCorruptException].
 */
internal class ArchiveInput(
    stream: InputStream,
    private val cancellation: () -> Unit,
) {
    private var stream: InputStream? = stream
    private val scratch = ByteArray(SCRATCH_SIZE)

    private fun current(): InputStream = stream ?: throw IOException("No archive volume is open")

    /** Reads at most [length] bytes; -1 at end of stream. */
    fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        cancellation()
        if (length == 0) return 0
        val n = current().read(buffer, offset, length)
        if (n == 0 || n > length) throw IOException("Input stream violated the read contract (returned $n of $length)")
        return if (n < 0) -1 else n
    }

    /** Reads until [length] bytes or the end of the stream; returns the count. */
    fun readUpTo(buffer: ByteArray, offset: Int, length: Int): Int {
        var done = 0
        while (done < length) {
            val n = read(buffer, offset + done, length - done)
            if (n < 0) break
            done += n
        }
        return done
    }

    fun readFully(buffer: ByteArray, offset: Int, length: Int) {
        if (readUpTo(buffer, offset, length) != length) throw unexpectedEnd()
    }

    fun skipFully(count: Long) {
        var remaining = count
        while (remaining > 0) {
            cancellation()
            val skipped = current().skip(remaining)
            if (skipped > 0) {
                remaining -= minOf(skipped, remaining)
                continue
            }
            // skip() may return 0 at the end of the stream or forever; reading tells the two apart.
            val n = read(scratch, 0, minOf(remaining, scratch.size.toLong()).toInt())
            if (n < 0) throw unexpectedEnd()
            remaining -= n
        }
    }

    /** Closes the current volume's stream; [attach] continues with the next one. */
    fun close() {
        val open = stream ?: return
        stream = null
        open.close()
    }

    fun attach(next: InputStream) {
        check(stream == null) { "The previous volume is still open" }
        stream = next
    }

    companion object {
        private const val SCRATCH_SIZE = 64 * 1024

        fun unexpectedEnd(): RarCorruptException = RarCorruptException("Unexpected end of archive")
    }
}
