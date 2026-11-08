package eu.darken.butler.common.files.text

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

class CharsetDetection(
    val charset: Charset,
    val bomBytes: ByteArray?,
) {
    val hasBom: Boolean get() = bomBytes != null
    val bomSize: Int get() = bomBytes?.size ?: 0

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is CharsetDetection) return false
        if (charset != other.charset) return false
        if (bomBytes != null) {
            if (other.bomBytes == null) return false
            if (!bomBytes.contentEquals(other.bomBytes)) return false
        } else if (other.bomBytes != null) return false
        return true
    }

    override fun hashCode(): Int = 31 * charset.hashCode() + (bomBytes?.contentHashCode() ?: 0)

    override fun toString(): String = "CharsetDetection(charset=$charset, bomSize=$bomSize)"
}

/**
 * Charset detection: BOM first, then UTF-8 validation, defaulting to UTF-8. [detectEncoding] adds
 * GB18030 and leaves the fallback to the caller.
 * Extracted from FileDataSource so the block index and the save path share one implementation.
 * UTF-16 BOMs resolve to UTF_16LE/UTF_16BE so encoders never emit their own BOM.
 */
object CharsetDetector {

    fun detect(sample: ByteArray): CharsetDetection =
        detectBom(sample) ?: CharsetDetection(Charsets.UTF_8, bomBytes = null)

    fun detectBom(sample: ByteArray): CharsetDetection? = when {
        sample.size >= 3 &&
            sample[0] == 0xEF.toByte() &&
            sample[1] == 0xBB.toByte() &&
            sample[2] == 0xBF.toByte() -> CharsetDetection(Charsets.UTF_8, sample.copyOfRange(0, 3))

        sample.size >= 2 &&
            sample[0] == 0xFF.toByte() &&
            sample[1] == 0xFE.toByte() -> CharsetDetection(Charsets.UTF_16LE, sample.copyOfRange(0, 2))

        sample.size >= 2 &&
            sample[0] == 0xFE.toByte() &&
            sample[1] == 0xFF.toByte() -> CharsetDetection(Charsets.UTF_16BE, sample.copyOfRange(0, 2))

        else -> null
    }

    fun isValidUtf8(bytes: ByteArray): Boolean = decodesStrictly(bytes, Charsets.UTF_8)

    /**
     * Which charset [sample] is written in: a BOM, else strictly valid UTF-8, else GB18030 when the
     * sample reads as Chinese text, else null and the caller picks its own fallback. A [truncated]
     * sample may end inside a sequence, which does not count against its charset.
     */
    fun detectEncoding(sample: ByteArray, truncated: Boolean): CharsetDetection? =
        detectBom(sample) ?: detectWithoutBom(sample, truncated)?.let { CharsetDetection(it, bomBytes = null) }

    /** [detectEncoding] for content whose BOM, if any, is already stripped. */
    fun detectWithoutBom(sample: ByteArray, truncated: Boolean): Charset? = when {
        isValidUtf8(TextBoundary.trim(sample, Charsets.UTF_8, truncated)) -> Charsets.UTF_8
        looksLikeGb18030(TextBoundary.trim(sample, TextCharsets.GB18030, truncated)) -> TextCharsets.GB18030
        else -> null
    }

    /**
     * Latin-1 text decodes as GB18030 surprisingly often, since most pairs of high bytes are a valid
     * double-byte sequence: "Grüße aus Köln, schöne Straße." has four of them. Chinese text is made
     * of GB2312 pairs (both bytes `A1..FE`), and of many different hanzi (lead `B0..F7`). A run of
     * Latin-1 `£` (`A3 A3`, a full-width `#`) is GB2312 but never hanzi.
     */
    private fun looksLikeGb18030(bytes: ByteArray): Boolean {
        var multiByte = 0
        var gb2312 = 0
        val hanzi = IntArray(MIN_DISTINCT_HANZI)
        var distinctHanzi = 0

        var index = 0
        while (index < bytes.size) {
            val length = Gb18030.sequenceLength(bytes, index)
            if (length == 0) return false
            if (length > 1) multiByte++
            if (length == 2) {
                val lead = bytes[index].toInt() and 0xFF
                val trail = bytes[index + 1].toInt() and 0xFF
                if (lead in 0xA1..0xF7 && trail in 0xA1..0xFE) {
                    gb2312++
                    val pair = (lead shl 8) or trail
                    if (lead >= 0xB0 && distinctHanzi < MIN_DISTINCT_HANZI && pair !in hanzi) {
                        hanzi[distinctHanzi++] = pair
                    }
                }
            }
            index += length
        }

        return gb2312 >= MIN_GB2312_PAIRS &&
            distinctHanzi >= MIN_DISTINCT_HANZI &&
            gb2312 * 10 >= multiByte * 9 &&
            decodesStrictly(bytes, TextCharsets.GB18030)
    }

    private fun decodesStrictly(bytes: ByteArray, charset: Charset): Boolean = try {
        charset.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
        true
    } catch (e: CharacterCodingException) {
        false
    }

    private const val MIN_GB2312_PAIRS = 8
    private const val MIN_DISTINCT_HANZI = 4
}
