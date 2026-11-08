package eu.darken.butler.editor.core.engine.text

import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

/**
 * Decodes one block's byte range in isolation with a fresh decoder. [BlockIndexBuilder] and
 * [BlockOriginalDocument] both decode through here, so a block's char count at index time is the
 * count the cache sees later, whatever the platform decoder does with malformed input.
 *
 * Each malformed or unmappable run reported by the decoder becomes one U+FFFD.
 */
object BlockDecoder {

    class Decoded(
        val text: String,
        /** At least one run of bytes was malformed or unmappable and became U+FFFD. */
        val hadUndecodable: Boolean,
    )

    fun decode(bytes: ByteArray, charset: Charset, length: Int = bytes.size): Decoded {
        val decoder = charset.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        val input = ByteBuffer.wrap(bytes, 0, length)
        var output = CharBuffer.allocate(length + 2)
        var hadUndecodable = false

        while (true) {
            val result = decoder.decode(input, output, true)
            when {
                result.isUnderflow -> break
                result.isOverflow -> output = output.grown()
                else -> {
                    hadUndecodable = true
                    if (!output.hasRemaining()) output = output.grown()
                    output.put(REPLACEMENT)
                    input.position(input.position() + result.length())
                }
            }
        }
        while (decoder.flush(output).isOverflow) output = output.grown()

        output.flip()
        return Decoded(output.toString(), hadUndecodable)
    }

    private fun CharBuffer.grown(): CharBuffer {
        flip()
        return CharBuffer.allocate(capacity() * 2 + 2).put(this)
    }

    private const val REPLACEMENT = '�'
}
