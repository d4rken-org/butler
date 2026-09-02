package eu.darken.butler.viewer.core

import eu.darken.butler.common.coroutine.DispatcherProvider
import eu.darken.butler.common.debug.logging.Logging.Priority.*
import eu.darken.butler.common.debug.logging.asLog
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.debug.logging.logTag
import eu.darken.butler.common.files.text.CharsetDetector
import eu.darken.butler.common.files.text.TextDecoder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.charset.Charset
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Reads the head of a text file for the viewer's preview.
 *
 * Three separate bounds, because one is not enough: [MAX_PREVIEW_BYTES] on what is read, since the
 * viewer holds the whole preview in memory and the Explorer will hand it a file of any size;
 * [MAX_LINES] and [MAX_LINE_CHARS] on what is handed to the UI, because a megabyte is also a million
 * bare newlines, or one minified line a million characters wide, and neither is something Compose
 * can lay out. Everything it cannot serve - a read failure, or content that turns out to be binary -
 * resolves to null and falls back to the unsupported placeholder.
 */
@Singleton
class TextPreviewLoader @Inject constructor(
    private val contentReader: ViewerContentReader,
    private val dispatcherProvider: DispatcherProvider,
) {

    /**
     * Whether [source] really holds text, read from its head only. The counterpart to [ImageProbe]:
     * a name says `.txt` but the bytes decide, and announcing text for a binary would render
     * mojibake instead of the unsupported placeholder.
     */
    suspend fun probe(source: ViewerSource): Boolean = try {
        withContext(dispatcherProvider.IO) {
            val head = contentReader.readInput(source) { it.readCapped(PROBE_BYTES) }
            // Trimmed exactly like the preview is. Without it the probe's own cut decides the
            // verdict: a file of four-byte emoji ends the head mid-sequence, the strict decode
            // fails, and the Latin-1 fallback's C1 controls report perfectly good text as binary.
            TextDecoder.decode(trimToBoundary(head)) is TextDecoder.Result.Decoded
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        log(TAG, WARN) { "Cannot probe $source as text: ${e.asLog()}" }
        false
    }

    suspend fun preview(source: ViewerSource): TextPreview? = try {
        withContext(dispatcherProvider.IO) { read(source) }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        log(TAG, WARN) { "Cannot read $source as text: ${e.asLog()}" }
        null
    }

    private suspend fun read(source: ViewerSource): TextPreview? {
        val bytes = contentReader.readInput(source) { it.readCapped(MAX_PREVIEW_BYTES) }
        // readCapped stops one byte past the cap, so a full buffer is the signal that there is more
        // - the reported size is not consulted, a provider is free to lie about it.
        val cutByBytes = bytes.size > MAX_PREVIEW_BYTES
        val body = if (cutByBytes) trimToBoundary(bytes.copyOf(MAX_PREVIEW_BYTES)) else bytes

        val decoded = when (val result = TextDecoder.decode(body)) {
            is TextDecoder.Result.Decoded -> result
            TextDecoder.Result.Binary -> {
                log(TAG, INFO) { "$source is named as text but reads as binary" }
                return null
            }
        }

        // One past the limit, so a file that ends exactly on it is not reported as cut.
        val all = decoded.text.lineSequence().take(MAX_LINES + 1).toList()
        val cutByLines = all.size > MAX_LINES
        var cutByWidth = false
        val lines = all.take(MAX_LINES).map { line ->
            if (line.length <= MAX_LINE_CHARS) line else line.take(MAX_LINE_CHARS).also { cutByWidth = true }
        }

        return TextPreview(
            lines = lines,
            charset = decoded.charset,
            // Outermost bound first: reading stopped before the line bounds ever saw the rest, so
            // naming a line limit for a file cut at the byte cap would name the wrong one.
            truncation = when {
                cutByBytes -> TextPreview.Truncation.Bytes(MAX_PREVIEW_BYTES.toLong())
                cutByLines -> TextPreview.Truncation.Lines(MAX_LINES)
                cutByWidth -> TextPreview.Truncation.LineWidth(MAX_LINE_CHARS)
                else -> null
            },
        )
    }

    /**
     * Cuts a truncated buffer back to a character boundary, BEFORE it is decoded.
     *
     * The cap lands on an arbitrary byte, and a UTF-8 sequence split across it does not merely lose
     * one character: the strict decode fails, the whole buffer falls back to ISO-8859-1, and the
     * mojibake that produces trips the binary guard - so a perfectly good file would have no preview
     * at all. Cutting at a line break also spares the reader half a line of data that is not there.
     */
    private fun trimToBoundary(bytes: ByteArray): ByteArray {
        val bom = CharsetDetector.detectBom(bytes)
        if (bom?.charset == Charsets.UTF_16LE || bom?.charset == Charsets.UTF_16BE) {
            return trimUtf16(bytes, bom.charset)
        }

        val lastBreak = bytes.lastIndexOf(NEWLINE)
        if (lastBreak >= 0) return bytes.copyOf(lastBreak)

        return trimPartialUtf8(bytes)
    }

    /**
     * A single line longer than the whole cap. Drops a trailing incomplete UTF-8 sequence, and only
     * that: the scan is bounded by the longest sequence there is and requires a real lead byte at
     * the end of it, so a single-byte encoding - where every high byte looks like a continuation -
     * is left alone. Without that bound, a Latin-1 line of `£` (0xA3) would trim away to nothing.
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
     * UTF-16 carries 0x0A inside a code unit, so the newline scan would cut mid-character here. Its
     * units are fixed width instead, which makes an even length enough - except for a cut between
     * the halves of a surrogate pair, which would decode to a replacement character.
     */
    private fun trimUtf16(bytes: ByteArray, charset: Charset): ByteArray {
        val even = if (bytes.size % 2 == 0) bytes else bytes.copyOf(bytes.size - 1)
        if (even.size < 2) return even

        val first = even[even.size - 2].toInt() and 0xFF
        val second = even[even.size - 1].toInt() and 0xFF
        val unit = if (charset == Charsets.UTF_16LE) (second shl 8) or first else (first shl 8) or second
        return if (unit in 0xD800..0xDBFF) even.copyOf(even.size - 2) else even
    }

    /** Reads one byte past [cap], so the caller can tell "exactly full" from "there is more". */
    private fun InputStream.readCapped(cap: Int): ByteArray {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(64 * 1024)
        val limit = cap + 1
        while (out.size() < limit) {
            val read = read(buf, 0, minOf(buf.size, limit - out.size()))
            if (read == -1) break
            out.write(buf, 0, read)
        }
        return out.toByteArray()
    }

    companion object {
        private val TAG = logTag("Viewer", "TextPreviewLoader")

        const val MAX_PREVIEW_BYTES = 1024 * 1024

        /** A megabyte of bare newlines is a million of them, and every one would be a list entry. */
        const val MAX_LINES = 50_000

        /** Minified JSON is one line; handing Compose a megabyte of it to shape is not viable. */
        const val MAX_LINE_CHARS = 2_000

        /**
         * How much of the head [probe] decides on. Large enough that a text file's first lines
         * cannot all be legitimate control characters, small enough to stay off the critical path
         * of classifying a multi-hundred-MB file.
         */
        private const val PROBE_BYTES = 64 * 1024

        private const val NEWLINE = '\n'.code.toByte()
        private const val MAX_UTF8_SEQUENCE = 4
    }
}

/** What the viewer shows for a text file, and what it had to leave out to stay bounded. */
data class TextPreview(
    /** Already bounded in both count and width - the UI renders these as they are. */
    val lines: List<String>,
    val charset: Charset,
    /** Null when this is the whole file. */
    val truncation: Truncation? = null,
) {
    val isTruncated: Boolean get() = truncation != null

    /**
     * Which bound cut the preview, and where it sits. The UI names the one that actually applied: a
     * 42 kB minified file cut at the line width has not been "limited to the first 1 MB", and
     * saying so sends the reader looking for a megabyte that was never there.
     */
    sealed interface Truncation {
        data class Bytes(val limit: Long) : Truncation
        data class Lines(val limit: Int) : Truncation
        data class LineWidth(val limit: Int) : Truncation
    }
}
