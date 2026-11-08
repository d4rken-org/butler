package eu.darken.butler.common.files.text

import java.nio.charset.Charset

/**
 * Turns a byte range into text, or says it is not text at all.
 *
 * Owns the decision every caller that shows file content has to make the same way: honour a BOM,
 * decode strictly as UTF-8, then GB18030 when the bytes read as Chinese, fall back to ISO-8859-1 for
 * other legacy encodings, and refuse content that only decoded because Latin-1 maps every byte.
 */
object TextDecoder {

    sealed interface Result {
        data class Decoded(val text: String, val charset: Charset) : Result

        /** The bytes are not text. Callers decide what to say about that. */
        data object Binary : Result
    }

    /**
     * [truncated] says [bytes] is a head cut off by a read cap, so an incomplete sequence at its end
     * is dropped instead of decoded. With an explicit [charset] the bytes are decoded as that,
     * malformed input becomes U+FFFD, and the result is never [Result.Binary].
     */
    fun decode(bytes: ByteArray, truncated: Boolean = false, charset: Charset? = null): Result {
        if (charset != null) return decodeAs(bytes, truncated, charset)

        val bom = CharsetDetector.detectBom(bytes)
        if (bom != null && bom.charset.isUtf16()) {
            val body = TextBoundary.trim(bytes.copyOfRange(bom.bomSize, bytes.size), bom.charset, truncated)
            return Result.Decoded(String(body, bom.charset), bom.charset)
        }

        val body = if (bom != null) bytes.copyOfRange(bom.bomSize, bytes.size) else bytes

        // Checked before decoding, and only once a UTF-16 BOM has been ruled out above: UTF-16 text
        // legitimately contains 0x00, everything else that does is binary.
        if (body.any { it == 0.toByte() }) return Result.Binary

        // Strict UTF-8, then GB18030, so multibyte sequences aren't mistaken for raw C1 control bytes;
        // only content that is neither falls back.
        val detected = CharsetDetector.detectWithoutBom(body, truncated) ?: Charsets.ISO_8859_1
        val text = String(TextBoundary.trim(body, detected, truncated), detected)

        // Backstop against the ISO-8859-1 fallback (and null-free binaries) decoding to control-char
        // garbage. Run on the decoded text so C1 controls (0x80..0x9F) are counted too.
        if (looksBinary(text)) return Result.Binary

        return Result.Decoded(text, detected)
    }

    private fun decodeAs(bytes: ByteArray, truncated: Boolean, charset: Charset): Result {
        val bom = CharsetDetector.detectBom(bytes)?.takeIf {
            it.charset == charset || (it.charset.isUtf16() && charset.isUtf16())
        }
        val body = if (bom != null) bytes.copyOfRange(bom.bomSize, bytes.size) else bytes
        return Result.Decoded(String(TextBoundary.trim(body, charset, truncated), charset), charset)
    }

    private fun Charset.isUtf16() = this == Charsets.UTF_16LE || this == Charsets.UTF_16BE

    /**
     * Counts Unicode control characters (C0, DEL and C1 0x80..0x9F) that are not legitimate text
     * controls. Real text has essentially none; a high ratio means the ISO-8859-1 fallback decoded
     * control-char garbage. The minimum count keeps a lone stray control in a short snippet from
     * tripping the ratio. High-byte-only binaries that decode to legible Latin-1 are an accepted
     * miss.
     */
    fun looksBinary(text: String): Boolean {
        if (text.isEmpty()) return false
        var suspicious = 0
        for (c in text) {
            if (c.isISOControl() && c !in ALLOWED_CONTROLS) suspicious++
        }
        return suspicious >= MIN_BINARY_CONTROL_COUNT &&
            suspicious.toDouble() / text.length > BINARY_CONTROL_RATIO
    }

    private const val BINARY_CONTROL_RATIO = 0.10
    private const val MIN_BINARY_CONTROL_COUNT = 4

    /** Control chars that legitimately appear in text/logs (tab, LF, CR, FF, ANSI ESC). */
    private val ALLOWED_CONTROLS = setOf('\t', '\n', '\r', '\u000C', '\u001B')
}
