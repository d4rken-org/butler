package eu.darken.butler.common.ui

import android.content.Context
import android.text.format.Formatter
import java.text.DecimalFormatSymbols

/**
 * Parses localized file size strings like "500 MB" or "1.5 GB" into bytes.
 * Supports the device's locale for decimal separators and unit matching.
 */
class SizeParser(private val context: Context) {

    private val locale by lazy { context.resources.configuration.locales[0] }
    private val decimalSeparator by lazy { DecimalFormatSymbols(locale).decimalSeparator }
    private val sizeRegex by lazy {
        Regex(
            """(\p{Nd}+(?:[$decimalSeparator]\p{Nd}+)?)\s*([\p{L}.]+)""",
            RegexOption.IGNORE_CASE
        )
    }

    private val sizeUnitsLocalized by lazy {
        val unitDelimiterRegex = Regex("\\s")
        val sizeSplitter: (Long, String) -> Pair<String, Long> = { size, fallback ->
            val formatted = Formatter.formatShortFileSize(context, size)
            val unit = formatted.split(unitDelimiterRegex).lastOrNull() ?: fallback
            unit.uppercase() to size
        }
        mapOf(
            sizeSplitter(1L, "B"),
            sizeSplitter(1_000L, "kB"),
            sizeSplitter(1_000_000L, "MB"),
            sizeSplitter(1_000_000_000L, "GB"),
            sizeSplitter(1_000_000_000_000L, "TB"),
        )
    }

    private fun normalizeDigits(input: String): String = input.map {
        when {
            Character.isDigit(it) -> Character.getNumericValue(it).toString()
            else -> it.toString()
        }
    }.joinToString("")

    /**
     * Parses a file size string into bytes.
     * @param input The input string (e.g., "500 MB", "1.5 GB")
     * @return The size in bytes, or null if parsing fails
     */
    fun parse(input: String): Long? {
        val match = sizeRegex.matchEntire(input.trim()) ?: return null
        val (value, unit) = match.destructured
        val valueNormalized = normalizeDigits(value)
            .replace(decimalSeparator, '.')
            .toDoubleOrNull() ?: return null
        val factor = sizeUnitsLocalized[unit.uppercase()] ?: return null
        return (valueNormalized * factor).toLong()
    }
}
