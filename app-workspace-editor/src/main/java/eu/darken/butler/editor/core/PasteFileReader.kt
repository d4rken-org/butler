package eu.darken.butler.editor.core

import eu.darken.butler.common.debug.logging.Logging.Priority.ERROR
import eu.darken.butler.common.debug.logging.Logging.Priority.WARN
import eu.darken.butler.common.debug.logging.asLog
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.debug.logging.logTag
import eu.darken.butler.common.files.APath
import eu.darken.butler.common.files.GatewaySwitch
import eu.darken.butler.common.files.text.CharsetDetector
import kotlinx.coroutines.CancellationException
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import javax.inject.Inject

/**
 * Reads a text file for pasting into the editor.
 *
 * Streams with a hard byte cap (reported sizes are not trusted - a provider returning null or a
 * lying size must not bypass the limit), rejects binary content via null bytes AFTER honoring a
 * UTF-16 BOM (UTF-16 text legitimately contains 0x00), and decodes strictly as UTF-8 with a real
 * ISO-8859-1 fallback for legacy-encoded content. A control-character heuristic on the decoded
 * text then rejects null-free binaries the Latin-1 fallback would otherwise pass through.
 */
class PasteFileReader @Inject constructor(
    private val gatewaySwitch: GatewaySwitch,
) {

    suspend fun read(path: APath<*>): Result<String> = readCatching {
        gatewaySwitch.useRes { decodeBytes(readCapped { gatewaySwitch.openInputStream(path) }) }
    }

    /**
     * Same capped read and decode, but for content Butler only has a stream for (e.g. a `content://`
     * URI handed over by another app) instead of an [APath].
     */
    suspend fun read(streamProvider: () -> InputStream?): Result<String> = readCatching {
        decodeBytes(readCapped { streamProvider() })
    }

    private suspend fun readCatching(block: suspend () -> String): Result<String> = try {
        Result.success(block())
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        log(TAG, ERROR) { "Failed to read file content: ${e.asLog()}" }
        Result.failure(e)
    }

    private suspend fun readCapped(streamProvider: suspend () -> InputStream?): ByteArray {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(64 * 1024)
        val input = streamProvider() ?: throw IOException("Could not open the content for reading")
        input.use {
            while (true) {
                val read = it.read(buf)
                if (read == -1) break
                if (out.size() + read > MAX_PASTE_FILE_SIZE) {
                    throw PasteTooLargeException(MAX_PASTE_FILE_SIZE)
                }
                out.write(buf, 0, read)
            }
        }
        return out.toByteArray()
    }

    internal fun decodeBytes(bytes: ByteArray): String {
        val bom = CharsetDetector.detectBom(bytes)
        if (bom != null && (bom.charset == Charsets.UTF_16LE || bom.charset == Charsets.UTF_16BE)) {
            return String(bytes, bom.bomSize, bytes.size - bom.bomSize, bom.charset)
        }
        val body = if (bom != null) bytes.copyOfRange(bom.bomSize, bytes.size) else bytes
        // Null bytes are a hard binary signal.
        if (body.any { it == 0.toByte() }) {
            throw PasteBinaryException()
        }
        // Decode UTF-8 strictly first so multibyte sequences aren't mistaken for raw C1 control
        // bytes; only genuinely non-UTF-8 content falls back to ISO-8859-1.
        val decoded = try {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(body))
                .toString()
        } catch (e: CharacterCodingException) {
            log(TAG, WARN) { "Strict UTF-8 decode failed, falling back to ISO-8859-1" }
            String(body, Charsets.ISO_8859_1)
        }
        // Backstop against the ISO-8859-1 fallback (and null-free binaries) decoding to control-char
        // garbage: run the check on the decoded text so C1 controls (0x80..0x9F) are also counted.
        if (looksBinary(decoded)) {
            throw PasteBinaryException()
        }
        return decoded
    }

    /**
     * Heuristic binary guard for the decoded text: counts Unicode control characters (C0, DEL and
     * C1 0x80..0x9F) that are not legitimate text controls (tab/LF/CR/FF and the ANSI ESC used in
     * captured logs). Real text has essentially none; a high ratio means the ISO-8859-1 fallback
     * decoded control-char garbage. The minimum-count guard keeps a lone stray control in a short
     * snippet from tripping the ratio. High-byte-only binaries that decode to legible Latin-1 are
     * an accepted miss for a paste convenience.
     */
    internal fun looksBinary(text: String): Boolean {
        if (text.isEmpty()) return false
        var suspicious = 0
        for (c in text) {
            if (c.isISOControl() && c !in ALLOWED_CONTROLS) suspicious++
        }
        return suspicious >= MIN_BINARY_CONTROL_COUNT &&
            suspicious.toDouble() / text.length > BINARY_CONTROL_RATIO
    }

    companion object {
        const val MAX_PASTE_FILE_SIZE = 1024 * 1024L // 1 MB
        private const val BINARY_CONTROL_RATIO = 0.10
        private const val MIN_BINARY_CONTROL_COUNT = 4
        /** Control chars that legitimately appear in text/logs (tab, LF, CR, FF, ANSI ESC). */
        private val ALLOWED_CONTROLS = setOf('\t', '\n', '\r', '\u000C', '\u001B')
        private val TAG = logTag("Editor", "PasteFileReader")
    }
}
