package eu.darken.butler.editor.core.engine.text

import kotlinx.coroutines.ensureActive
import okio.BufferedSource
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest
import kotlin.coroutines.coroutineContext

/**
 * Single streaming scan of the original file producing a [BlockIndex] plus whole-file
 * line-ending detection. Cancellable per block via `ensureActive()`.
 *
 * Block edges are snapped to code-point boundaries by explicit byte inspection (UTF-8
 * lead/continuation scan-back, UTF-16 evenness plus a trailing-high-surrogate carry), and each
 * snapped range is decoded IN ISOLATION with a fresh decoder - the exact decode the block cache
 * performs later. This must not rely on decoder underflow leaving partial sequences unconsumed:
 * Android's ICU-backed decoders consume partial sequences into internal state at buffer ends,
 * which silently skews byte attribution (caught by the cache loader's count validation on
 * device). `endOfInput=true` per range is safe because non-EOF ranges contain only complete
 * sequences for valid input, and malformed bytes decode identically in both passes.
 */
class BlockIndexBuilder(
    private val blockSize: Int = DEFAULT_BLOCK_SIZE,
) {

    init {
        require(blockSize >= 4) { "Block size too small: $blockSize" }
    }

    /** [source] must be positioned after any BOM; all offsets in the index are post-BOM. */
    suspend fun build(
        source: BufferedSource,
        charset: Charset,
        onProgress: ((bytesIndexed: Long) -> Unit)? = null,
    ): BlockIndex {
        val isUtf16 = charset == Charsets.UTF_16 || charset == Charsets.UTF_16LE || charset == Charsets.UTF_16BE
        val isUtf8 = charset == Charsets.UTF_8

        val blocks = mutableListOf<BlockIndex.Block>()
        val digests = mutableListOf<Long>()
        val sha256 = MessageDigest.getInstance("SHA-256")
        var carry = ByteArray(0)
        var bytesAttributed = 0L
        var charsAttributed = 0L
        var crlf = 0L
        var lf = 0L
        var cr = 0L
        var pendingCr = false

        while (true) {
            coroutineContext.ensureActive()

            val input = ByteArray(carry.size + blockSize)
            carry.copyInto(input)
            var filled = carry.size
            while (filled < input.size) {
                val read = source.read(input, filled, input.size - filled)
                if (read == -1) break
                filled += read
            }
            val eof = filled < input.size || source.exhausted()

            if (filled == 0) break

            var take = filled
            if (!eof) {
                take -= when {
                    isUtf16 -> trailingPartialUtf16(input, take, charset)
                    isUtf8 -> trailingPartialUtf8(input, take)
                    else -> 0
                }
            }

            val text = decodeRange(input, take, charset)
            carry = if (take < filled) input.copyOfRange(take, filled) else ByteArray(0)

            if (text.isEmpty()) {
                if (eof) break else continue
            }

            var i = 0
            if (pendingCr) {
                if (text[0] == '\n') {
                    crlf++
                    i = 1
                } else {
                    cr++
                }
                pendingCr = false
            }
            while (i < text.length) {
                when (text[i]) {
                    '\r' -> when {
                        i + 1 >= text.length -> pendingCr = true
                        text[i + 1] == '\n' -> {
                            crlf++
                            i++
                        }
                        else -> cr++
                    }
                    '\n' -> lf++
                }
                i++
            }

            blocks += BlockIndex.Block(
                byteStart = bytesAttributed,
                byteLen = take,
                charStart = charsAttributed,
                charCount = text.length,
                lineBreakCount = TextMetrics.countBreaks(text),
                startsWithLf = TextMetrics.startsWithLf(text),
                endsWithCr = TextMetrics.endsWithCr(text),
                endsWithBreak = TextMetrics.endsWithBreak(text),
            )
            sha256.update(input, 0, take)
            digests += truncateDigest(sha256.digest())
            bytesAttributed += take
            charsAttributed += text.length
            onProgress?.invoke(bytesAttributed)

            if (eof) break
        }
        if (pendingCr) cr++

        return BlockIndex(blocks, TextMetrics.detectLineEnding(crlf, lf, cr), digests.toLongArray())
    }

    private fun decodeRange(bytes: ByteArray, length: Int, charset: Charset): String {
        val decoder = charset.newDecoder()
            .onMalformedInput(CodingErrorAction.REPLACE)
            .onUnmappableCharacter(CodingErrorAction.REPLACE)
        val charBuffer = CharBuffer.allocate(length + 2)
        decoder.decode(ByteBuffer.wrap(bytes, 0, length), charBuffer, true)
        decoder.flush(charBuffer)
        charBuffer.flip()
        return charBuffer.toString()
    }

    /**
     * Byte count to carry so a UTF-16 range doesn't end mid-code-point: an odd trailing byte
     * plus a trailing high-surrogate unit (its pair completes in the next block).
     */
    private fun trailingPartialUtf16(bytes: ByteArray, length: Int, charset: Charset): Int {
        val odd = length % 2
        val end = length - odd
        if (end < 2) return odd
        val unit = if (charset == Charsets.UTF_16LE) {
            ((bytes[end - 1].toInt() and 0xFF) shl 8) or (bytes[end - 2].toInt() and 0xFF)
        } else {
            ((bytes[end - 2].toInt() and 0xFF) shl 8) or (bytes[end - 1].toInt() and 0xFF)
        }
        return odd + if (unit in 0xD800..0xDBFF) 2 else 0
    }

    /** Byte count of an incomplete UTF-8 sequence ending at [length], 0 if the tail is complete. */
    private fun trailingPartialUtf8(bytes: ByteArray, length: Int): Int {
        var index = length - 1
        var scanned = 0
        while (index >= 0 && scanned < 4) {
            val byte = bytes[index].toInt() and 0xFF
            if (byte and 0x80 == 0) return 0
            if (byte and 0xC0 == 0xC0) {
                val sequenceLength = when {
                    byte and 0xE0 == 0xC0 -> 2
                    byte and 0xF0 == 0xE0 -> 3
                    byte and 0xF8 == 0xF0 -> 4
                    // Invalid lead byte: malformed input, decode as-is (REPLACE handles it)
                    else -> return 0
                }
                return if (index + sequenceLength > length) length - index else 0
            }
            // Continuation byte, keep scanning back
            index--
            scanned++
        }
        // Four or more continuation bytes: malformed, decode as-is
        return 0
    }

    companion object {
        const val DEFAULT_BLOCK_SIZE = 64 * 1024

        /** First 8 bytes of a digest as a big-endian Long - the per-block staleness fingerprint. */
        fun truncateDigest(digest: ByteArray): Long {
            var value = 0L
            for (i in 0 until 8) {
                value = (value shl 8) or (digest[i].toLong() and 0xFF)
            }
            return value
        }
    }
}
