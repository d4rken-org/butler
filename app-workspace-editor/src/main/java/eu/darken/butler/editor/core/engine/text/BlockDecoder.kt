package eu.darken.butler.editor.core.engine.text

import eu.darken.butler.common.files.text.Gb18030
import eu.darken.butler.common.files.text.TextCharsets
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

/**
 * Decodes one block's byte range in isolation with a fresh REPLACE decoder. [BlockIndexBuilder] and
 * [BlockOriginalDocument] both decode through here, so a block's char count at index time is the
 * count the cache sees later.
 *
 * [Decoded.hadUndecodable] comes from a second, strict decode of the same range, run only when the
 * text contains U+FFFD: a file may hold a literal U+FFFD (`EF BF BD` in UTF-8, `84 31 A4 37` in
 * GB18030), which must not mark the block. GB18030 is also flagged when the range is not
 * structurally valid ([Gb18030.sequenceLength]), whatever the decoder made of it: the byte mapping
 * walks that structure, and platform decoders differ on bytes like `80`.
 */
object BlockDecoder {

    class Decoded(
        val text: String,
        /** At least one run of bytes was malformed or unmappable and became U+FFFD. */
        val hadUndecodable: Boolean,
    )

    fun decode(bytes: ByteArray, charset: Charset, length: Int = bytes.size): Decoded {
        val decoder = charset.newDecoder()
            .onMalformedInput(CodingErrorAction.REPLACE)
            .onUnmappableCharacter(CodingErrorAction.REPLACE)
        val charBuffer = CharBuffer.allocate(length + 2)
        decoder.decode(ByteBuffer.wrap(bytes, 0, length), charBuffer, true)
        decoder.flush(charBuffer)
        charBuffer.flip()
        val text = charBuffer.toString()

        val hadUndecodable = (charset == TextCharsets.GB18030 && !isStructurallyValidGb18030(bytes, length)) ||
            (text.indexOf(REPLACEMENT) >= 0 && !decodesStrictly(bytes, charset, length))
        return Decoded(text, hadUndecodable)
    }

    internal fun isStructurallyValidGb18030(bytes: ByteArray, length: Int = bytes.size): Boolean {
        var index = 0
        while (index < length) {
            val sequence = Gb18030.sequenceLength(bytes, index, length)
            if (sequence == 0) return false
            index += sequence
        }
        return true
    }

    private fun decodesStrictly(bytes: ByteArray, charset: Charset, length: Int): Boolean = try {
        charset.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes, 0, length))
        true
    } catch (e: CharacterCodingException) {
        false
    }

    private const val REPLACEMENT = '�'
}
