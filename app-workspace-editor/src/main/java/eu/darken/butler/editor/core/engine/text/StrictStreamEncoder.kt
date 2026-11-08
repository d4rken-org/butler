package eu.darken.butler.editor.core.engine.text

import eu.darken.butler.editor.core.engine.UnmappableCharactersException
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

/**
 * One REPORT encoder over a whole char stream that arrives in slices. Input the encoder leaves
 * unconsumed at a slice end (a high surrogate waiting for its low half) is carried into the next
 * slice, so a pair split by any seam still encodes as one character: `"x\uD83D"` then `"\uDE00"`
 * writes the bytes of `"x😀"`.
 *
 * Unmappable characters and malformed input (lone surrogates) throw [UnmappableCharactersException].
 * [onBytes] must consume every remaining byte of the buffer it receives.
 */
internal class StrictStreamEncoder(
    private val charset: Charset,
    bufferSize: Int,
    private val onBytes: (ByteBuffer) -> Unit,
) {
    private val encoder = charset.newEncoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
    private val out = ByteBuffer.allocate(bufferSize)
    private var carry = ""

    fun write(chars: CharSequence) {
        if (chars.isEmpty()) return
        val input = CharBuffer.wrap(if (carry.isEmpty()) chars else StringBuilder(carry).append(chars))
        encode(input, endOfInput = false)
        carry = input.toString()
    }

    fun finish() {
        encode(CharBuffer.wrap(carry), endOfInput = true)
        carry = ""
        while (true) {
            val result = encoder.flush(out)
            drain()
            if (!result.isOverflow) break
        }
    }

    private fun encode(input: CharBuffer, endOfInput: Boolean) {
        while (true) {
            val result = encoder.encode(input, out, endOfInput)
            if (result.isError) throw UnmappableCharactersException(charset)
            drain()
            if (result.isUnderflow) return
        }
    }

    private fun drain() {
        out.flip()
        if (out.hasRemaining()) onBytes(out)
        out.clear()
    }
}
