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
 * ISO-8859-1 fallback for legacy-encoded content.
 */
class PasteFileReader @Inject constructor(
    private val gatewaySwitch: GatewaySwitch,
) {

    suspend fun read(path: APath<*>): Result<String> = try {
        val content = gatewaySwitch.useRes { decodeBytes(readCapped(path)) }
        Result.success(content)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        log(TAG, ERROR) { "Failed to read file content: ${e.asLog()}" }
        Result.failure(e)
    }

    private suspend fun readCapped(path: APath<*>): ByteArray {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(64 * 1024)
        gatewaySwitch.openInputStream(path).use { input ->
            while (true) {
                val read = input.read(buf)
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
        if (body.any { it == 0.toByte() }) {
            throw PasteBinaryException()
        }
        return try {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(body))
                .toString()
        } catch (e: CharacterCodingException) {
            log(TAG, WARN) { "Strict UTF-8 decode failed, falling back to ISO-8859-1" }
            String(body, Charsets.ISO_8859_1)
        }
    }

    companion object {
        const val MAX_PASTE_FILE_SIZE = 1024 * 1024L // 1 MB
        private val TAG = logTag("Editor", "PasteFileReader")
    }
}
