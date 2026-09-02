package eu.darken.butler.editor.core

import eu.darken.butler.common.debug.logging.Logging.Priority.ERROR
import eu.darken.butler.common.debug.logging.asLog
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.debug.logging.logTag
import eu.darken.butler.common.files.APath
import eu.darken.butler.common.files.GatewaySwitch
import eu.darken.butler.common.files.text.TextDecoder
import kotlinx.coroutines.CancellationException
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
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

    internal fun decodeBytes(bytes: ByteArray): String = when (val result = TextDecoder.decode(bytes)) {
        is TextDecoder.Result.Decoded -> result.text
        TextDecoder.Result.Binary -> throw PasteBinaryException()
    }

    companion object {
        const val MAX_PASTE_FILE_SIZE = 1024 * 1024L // 1 MB
        private val TAG = logTag("Editor", "PasteFileReader")
    }
}
