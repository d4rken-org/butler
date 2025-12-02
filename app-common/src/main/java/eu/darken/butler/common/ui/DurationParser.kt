package eu.darken.butler.common.ui

import android.content.Context
import java.text.DecimalFormatSymbols
import kotlin.math.roundToLong
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours

/**
 * Parses localized duration strings like "30 days" or "24 hours" into [Duration].
 * Supports the device's locale for unit matching and Unicode digits.
 */
class DurationParser(private val context: Context) {

    private val locale by lazy { context.resources.configuration.locales[0] }
    private val decimalSeparator by lazy { DecimalFormatSymbols(locale).decimalSeparator }
    private val durationRegex by lazy {
        Regex(
            """\s*(\p{Nd}+(?:[$decimalSeparator]\p{Nd}+)?)\s+([^\d\s]+)""",
            RegexOption.IGNORE_CASE
        )
    }

    /**
     * Parses a duration string into a [Duration].
     * @param input The input string (e.g., "30 days", "24 hours")
     * @return The parsed duration, or null if parsing fails
     */
    fun parse(input: String): Duration? {
        val normalizedInput = input.trim()
        val match = durationRegex.matchEntire(normalizedInput) ?: return null
        val (valueRaw, unitRaw) = match.destructured

        val value = normalizeDigits(valueRaw)
            .replace(decimalSeparator, '.')
            .toDoubleOrNull()
            ?.roundToLong() ?: return null

        val unit = unitRaw.lowercase()

        val localizedUnits = buildLocalizedUnitsMap(value.toInt())

        return localizedUnits
            .firstOrNull { (label, _) -> label.contains(unit) || unit.contains(label) }
            ?.second
            ?.invoke(value)
    }

    private fun normalizeDigits(input: String): String = input.map {
        when {
            Character.isDigit(it) -> Character.getNumericValue(it).toString()
            else -> it.toString()
        }
    }.joinToString("")

    private fun buildLocalizedUnitsMap(count: Int): List<Pair<String, (Long) -> Duration>> {
        val resources = context.resources
        return listOf(
            resources.getQuantityString(
                eu.darken.butler.common.R.plurals.common_duration_hours_full,
                count,
                count
            ).replace(count.toString(), "").trim().lowercase() to { v: Long -> v.hours },
            resources.getQuantityString(
                eu.darken.butler.common.R.plurals.common_duration_days_full,
                count,
                count
            ).replace(count.toString(), "").trim().lowercase() to { v: Long -> v.days },
        )
    }
}
