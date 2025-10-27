package eu.darken.butler.common

import android.text.SpannableString
import android.text.style.ForegroundColorSpan
import androidx.annotation.ColorInt

fun colorString(@ColorInt color: Int, string: String): SpannableString {
    val colored = SpannableString(string)
    colored.setSpan(ForegroundColorSpan(color), 0, string.length, 0)
    return colored
}

/**
 * Checks if a character is problematic/invisible and should be highlighted in filenames.
 *
 * Detects:
 * - Traditional whitespace (spaces, tabs, non-breaking spaces)
 * - Zero-width characters (invisible spacing/joining)
 * - Bidirectional control characters (security risk - filename spoofing)
 * - Control characters (unprintable)
 * - Format characters (soft hyphen, etc.)
 *
 * Used to highlight leading/trailing problematic characters in file/folder names.
 */
fun Char.isProblematicInvisible(): Boolean {
    return when {
        // Traditional whitespace (spaces, tabs, etc.)
        this.isWhitespace() -> true

        // Zero-width characters
        this in setOf(
            '\u200B', // Zero-Width Space
            '\u200C', // Zero-Width Non-Joiner
            '\u200D', // Zero-Width Joiner
            '\u2060', // Word Joiner
            '\uFEFF', // Zero-Width No-Break Space (BOM)
        ) -> true

        // Bidirectional control characters (SECURITY RISK!)
        this in '\u200E'..'\u200F' -> true  // LRM, RLM
        this in '\u202A'..'\u202E' -> true  // LRE, RLE, PDF, LRO, RLO
        this in '\u2066'..'\u2069' -> true  // LRI, RLI, FSI, PDI

        // Format characters
        this == '\u00AD' -> true  // Soft Hyphen

        // Control characters
        this in '\u0000'..'\u001F' -> true  // C0 controls
        this in '\u007F'..'\u009F' -> true  // DEL + C1 controls

        else -> false
    }
}