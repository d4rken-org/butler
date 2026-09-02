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

private const val ZERO_WIDTH_SPACE = '\u200B'
private val SOFT_BREAK_AFTER = setOf('-', '_', '.', '+')

/** Code points a break opportunity needs on either side of it to be worth offering. */
private const val SOFT_BREAK_MIN_EDGE = 5

/**
 * Adds zero-width spaces after separator characters so a long token can wrap at its boundaries.
 *
 * Without them a name that is a single unbroken token gets filled to the line's edge and split
 * there:
 *
 *     termux-app_v0.118.3+github-debug_universal.ap
 *     k
 *
 * Line filling is greedy, so it takes the last opportunity that fits. An opportunity with almost
 * nothing on one side of it produces a line with almost nothing on it, hence [SOFT_BREAK_MIN_EDGE]
 * — a trailing extension and a leading dot are the two that show up in file names:
 *
 *     termux-app_v0.118.3+github-debug_        .AVeryLongNameWithoutOtherSeparators
 *     universal.apk                            (no opportunity at all, better than "." alone)
 *
 * Display only: the inserted characters are invisible but real, so the result must not be fed back
 * into anything that compares, resolves or stores paths.
 */
fun String.withSoftBreaks(): String {
    val builder = StringBuilder(length)
    forEachIndexed { index, char ->
        builder.append(char)
        if (char !in SOFT_BREAK_AFTER) return@forEachIndexed
        val split = index + 1
        val fitsBefore = codePointCount(0, split) >= SOFT_BREAK_MIN_EDGE
        val fitsAfter = codePointCount(split, length) >= SOFT_BREAK_MIN_EDGE
        if (fitsBefore && fitsAfter) builder.append(ZERO_WIDTH_SPACE)
    }
    return builder.toString()
}