package eu.darken.butler.viewer.core

import eu.darken.butler.common.coroutine.DispatcherProvider
import eu.darken.butler.common.debug.logging.Logging.Priority.*
import eu.darken.butler.common.debug.logging.asLog
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.debug.logging.logTag
import eu.darken.butler.common.files.text.CharsetDetector
import eu.darken.butler.common.files.text.TextBoundary
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
        // A missing or wrong archive password is not a verdict on the content.
        e.archivePasswordFailure()?.let { throw it }
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

    /** Trims as the BOM's charset, else as UTF-8 - which leaves a single-byte cut alone (see TextBoundaryTest). */
    private fun trimToBoundary(bytes: ByteArray): ByteArray {
        val charset = CharsetDetector.detectBom(bytes)?.charset ?: Charsets.UTF_8
        return TextBoundary.trimToLine(bytes, charset, truncated = true)
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
