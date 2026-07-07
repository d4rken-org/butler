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
 * Charset detection: BOM first, then UTF-8 validation, defaulting to UTF-8.
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

    fun isValidUtf8(bytes: ByteArray): Boolean = try {
        Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
        true
    } catch (e: CharacterCodingException) {
        false
    }
}
