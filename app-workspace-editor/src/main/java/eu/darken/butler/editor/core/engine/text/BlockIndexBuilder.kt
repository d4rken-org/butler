package eu.darken.butler.editor.core.engine.text

import eu.darken.butler.common.debug.logging.Logging.Priority.WARN
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.debug.logging.logTag
import kotlinx.coroutines.ensureActive
import okio.BufferedSource
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import kotlin.coroutines.coroutineContext

/**
 * Single streaming scan of the original file producing a [BlockIndex] plus whole-file
 * line-ending detection. Cancellable per block via `ensureActive()`.
 *
 * Block edges snap to code-point boundaries two ways: bytes the decoder leaves unconsumed
 * (partial sequence) are carried into the next block, and a decoded trailing high surrogate
 * (UTF-16 decoders emit code units independently) is carried at the char layer together
 * with its bytes. `endOfInput=true` only at real EOF, so a file ending mid-sequence decodes
 * to a replacement char with consistent counts.
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
        val decoder = charset.newDecoder()
            .onMalformedInput(CodingErrorAction.REPLACE)
            .onUnmappableCharacter(CodingErrorAction.REPLACE)
        val isUtf16 = charset == Charsets.UTF_16 || charset == Charsets.UTF_16LE || charset == Charsets.UTF_16BE

        val blocks = mutableListOf<BlockIndex.Block>()
        var byteCarry = ByteArray(0)
        var charCarry: Char? = null
        var bytesAttributed = 0L
        var charsAttributed = 0L
        var crlf = 0L
        var lf = 0L
        var cr = 0L
        var pendingCr = false

        while (true) {
            coroutineContext.ensureActive()

            val input = ByteArray(byteCarry.size + blockSize)
            byteCarry.copyInto(input)
            var filled = byteCarry.size
            while (filled < input.size) {
                val read = source.read(input, filled, input.size - filled)
                if (read == -1) break
                filled += read
            }
            val eof = filled < input.size || source.exhausted()

            if (filled == 0 && charCarry == null) break

            val byteBuffer = ByteBuffer.wrap(input, 0, filled)
            val charBuffer = CharBuffer.allocate(filled + 2)
            decoder.decode(byteBuffer, charBuffer, eof)
            if (eof) decoder.flush(charBuffer)
            charBuffer.flip()

            var text = buildString(charBuffer.remaining() + 1) {
                charCarry?.let { append(it) }
                append(charBuffer)
            }
            val carriedInBytes = if (charCarry != null) 2 else 0
            charCarry = null

            val consumed = byteBuffer.position()
            byteCarry = if (byteBuffer.hasRemaining()) input.copyOfRange(byteBuffer.position(), filled) else ByteArray(0)

            var carriedOutBytes = 0
            if (!eof && text.isNotEmpty() && text.last().isHighSurrogate()) {
                if (isUtf16) {
                    charCarry = text.last()
                    text = text.dropLast(1)
                    carriedOutBytes = 2
                } else {
                    // UTF-8 decoders emit pairs atomically; should not happen
                    log(TAG, WARN) { "Block ends in high surrogate for non-UTF-16 charset $charset" }
                }
            }

            val blockByteLen = carriedInBytes + consumed - carriedOutBytes

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
                byteLen = blockByteLen,
                charStart = charsAttributed,
                charCount = text.length,
                lineBreakCount = TextMetrics.countBreaks(text),
                startsWithLf = TextMetrics.startsWithLf(text),
                endsWithCr = TextMetrics.endsWithCr(text),
                endsWithBreak = TextMetrics.endsWithBreak(text),
            )
            bytesAttributed += blockByteLen
            charsAttributed += text.length
            onProgress?.invoke(bytesAttributed)

            if (eof) break
        }
        if (pendingCr) cr++

        return BlockIndex(blocks, TextMetrics.detectLineEnding(crlf, lf, cr))
    }

    companion object {
        const val DEFAULT_BLOCK_SIZE = 64 * 1024
        private val TAG = logTag("Editor", "Engine", "BlockIndexBuilder")
    }
}
