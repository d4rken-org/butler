package eu.darken.butler.common

import android.content.Context
import android.icu.text.RelativeDateTimeFormatter
import android.text.format.DateFormat
import androidx.compose.runtime.Composable
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

enum class DurationFormat {
    FULL,
    SHORT,
    COMPACT,
}

@Composable
fun formatRelativeTime(
    instant: Instant,
    reference: Instant = Clock.System.now(),
    hoursThreshold: Duration = 24.hours,
): String {
    val context = LocalContext.current
    val locale = context.resources.configuration.locales[0]
    return remember(instant, reference, hoursThreshold, locale) {
        formatRelativeTime(context, instant, reference, hoursThreshold)
    }
}

fun formatRelativeTime(
    context: Context,
    instant: Instant,
    reference: Instant = Clock.System.now(),
    hoursThreshold: Duration = 24.hours,
): String {
    val locale = context.resources.configuration.locales[0]
    val formatter = RelativeDateTimeFormatter.getInstance(locale)
    val duration = reference - instant

    return when {
        duration.inWholeMinutes < 1 -> formatter.format(
            0.0,
            RelativeDateTimeFormatter.Direction.LAST,
            RelativeDateTimeFormatter.RelativeUnit.MINUTES
        )
        duration.inWholeMinutes < 60 -> formatter.format(
            duration.inWholeMinutes.toDouble(),
            RelativeDateTimeFormatter.Direction.LAST,
            RelativeDateTimeFormatter.RelativeUnit.MINUTES
        )
        duration < hoursThreshold -> formatter.format(
            duration.inWholeHours.toDouble(),
            RelativeDateTimeFormatter.Direction.LAST,
            RelativeDateTimeFormatter.RelativeUnit.HOURS
        )
        else -> formatter.format(
            duration.inWholeDays.toDouble(),
            RelativeDateTimeFormatter.Direction.LAST,
            RelativeDateTimeFormatter.RelativeUnit.DAYS
        )
    }
}

/**
 * The current time, re-read once a minute.
 *
 * For a relative label whose source rarely changes: an unchanged state emits nothing, so nothing
 * recomposes and "1 minute ago" can still say that an hour later. Only what reads this recomposes.
 */
@Composable
fun rememberMinuteTick(): Instant = produceState(Clock.System.now()) {
    while (true) {
        delay(1.minutes)
        value = Clock.System.now()
    }
}.value

@Composable
fun formatDuration(
    duration: Duration,
    format: DurationFormat = DurationFormat.FULL,
    shortStyle: Boolean = false,
): String {
    val context = LocalContext.current
    val actualFormat = if (shortStyle) DurationFormat.SHORT else format
    return remember(duration, actualFormat) { formatDuration(context, duration, actualFormat) }
}

fun formatDuration(
    context: Context,
    duration: Duration,
    format: DurationFormat = DurationFormat.FULL,
    shortStyle: Boolean = false,
): String {
    val actualFormat = if (shortStyle) DurationFormat.SHORT else format

    return when (actualFormat) {
        DurationFormat.COMPACT -> formatDurationCompact(context, duration)
        DurationFormat.SHORT -> formatDurationShort(context, duration)
        DurationFormat.FULL -> formatDurationFull(context, duration)
    }
}

private fun formatDurationCompact(context: Context, duration: Duration): String {
    return when {
        duration.inWholeSeconds < 1 -> {
            val ms = duration.inWholeMilliseconds.toInt()
            context.resources.getQuantityString(
                R.plurals.common_duration_milliseconds_short,
                ms,
                ms
            )
        }
        duration.inWholeSeconds < 60 -> {
            val seconds = duration.inWholeSeconds.toInt()
            context.resources.getQuantityString(
                R.plurals.common_duration_seconds_short,
                seconds,
                seconds
            )
        }
        else -> {
            val minutes = duration.inWholeMinutes.toInt()
            val seconds = (duration.inWholeSeconds % 60).toInt()
            val minutesPart = context.resources.getQuantityString(
                R.plurals.common_duration_minutes_short,
                minutes,
                minutes
            )
            val secondsPart = context.resources.getQuantityString(
                R.plurals.common_duration_seconds_short,
                seconds,
                seconds
            )
            "$minutesPart $secondsPart"
        }
    }
}

private fun formatDurationShort(context: Context, duration: Duration): String {
    val seconds = duration.inWholeSeconds
    val minutes = duration.inWholeMinutes
    val hours = duration.inWholeHours
    val days = duration.inWholeDays

    return when {
        days > 0 -> context.resources.getQuantityString(
            R.plurals.common_duration_days_short,
            days.toInt(),
            days.toInt()
        )
        hours > 0 -> context.resources.getQuantityString(
            R.plurals.common_duration_hours_short,
            hours.toInt(),
            hours.toInt()
        )
        minutes > 0 -> context.resources.getQuantityString(
            R.plurals.common_duration_minutes_short,
            minutes.toInt(),
            minutes.toInt()
        )
        else -> context.resources.getQuantityString(
            R.plurals.common_duration_seconds_short,
            seconds.toInt(),
            seconds.toInt()
        )
    }
}

private fun formatDurationFull(context: Context, duration: Duration): String {
    val seconds = duration.inWholeSeconds
    val minutes = duration.inWholeMinutes
    val hours = duration.inWholeHours
    val days = duration.inWholeDays

    return when {
        days > 0 -> {
            val remainingHours = hours % 24
            val daysPart = context.resources.getQuantityString(
                R.plurals.common_duration_days_full,
                days.toInt(),
                days.toInt()
            )
            if (remainingHours > 0) {
                val hoursPart = context.resources.getQuantityString(
                    R.plurals.common_duration_hours_full,
                    remainingHours.toInt(),
                    remainingHours.toInt()
                )
                "$daysPart $hoursPart"
            } else {
                daysPart
            }
        }
        hours > 0 -> {
            val remainingMinutes = minutes % 60
            val hoursPart = context.resources.getQuantityString(
                R.plurals.common_duration_hours_full,
                hours.toInt(),
                hours.toInt()
            )
            if (remainingMinutes > 0) {
                val minutesPart = context.resources.getQuantityString(
                    R.plurals.common_duration_minutes_full,
                    remainingMinutes.toInt(),
                    remainingMinutes.toInt()
                )
                "$hoursPart $minutesPart"
            } else {
                hoursPart
            }
        }
        minutes > 0 -> {
            val remainingSeconds = seconds % 60
            val minutesPart = context.resources.getQuantityString(
                R.plurals.common_duration_minutes_full,
                minutes.toInt(),
                minutes.toInt()
            )
            if (remainingSeconds > 0) {
                val secondsPart = context.resources.getQuantityString(
                    R.plurals.common_duration_seconds_full,
                    remainingSeconds.toInt(),
                    remainingSeconds.toInt()
                )
                "$minutesPart $secondsPart"
            } else {
                minutesPart
            }
        }
        else -> context.resources.getQuantityString(
            R.plurals.common_duration_seconds_full,
            seconds.toInt(),
            seconds.toInt()
        )
    }
}

enum class DateTimeStyle {
    /** Numeric, two-digit year, no seconds, e.g. "31.12.26 13:49". */
    COMPACT,

    /** Numeric, full year, seconds, e.g. "31.12.2026 13:49:07". */
    FULL,

    /** Textual month, full year, seconds and milliseconds, e.g. "31. Dez. 2026, 13:49:07,123". */
    DETAILED,

    /** Numeric, full year, no time, e.g. "31.12.2026". */
    DATE_NUMERIC,

    /** Textual month, full year, no time, e.g. "31. Dez. 2026". */
    DATE_TEXTUAL,
}

/**
 * Locale-aware timestamp. Field order, digit grouping and separators come from the locale, so the
 * same instant renders as "31.12.26 13:49" in de-DE and "12/31/26 1:49 PM" in en-US.
 *
 * [DateTimeStyle.COMPACT] and [DateTimeStyle.FULL] resolve date and time separately and join them
 * with a plain space, keeping the pair as narrow as possible for list and grid metadata lines.
 * [DateTimeStyle.DETAILED] resolves a single combined skeleton so the locale's own date-time glue
 * applies (e.g. fi-FI inserts "klo"); the fractional-second separator comes from the same lookup
 * and is locale-specific ("." in en, "," in de, an Arabic decimal separator in ar).
 */
fun formatDateTime(
    timestamp: Instant,
    zone: TimeZone,
    locale: Locale,
    is24Hour: Boolean,
    style: DateTimeStyle,
): String {
    val dateSkeleton = when (style) {
        DateTimeStyle.COMPACT -> "yyMMdd"
        DateTimeStyle.FULL, DateTimeStyle.DATE_NUMERIC -> "yMMdd"
        DateTimeStyle.DETAILED, DateTimeStyle.DATE_TEXTUAL -> "yMMMd"
    }
    val timeSkeleton = when (style) {
        DateTimeStyle.COMPACT -> if (is24Hour) "Hm" else "hm"
        DateTimeStyle.FULL -> if (is24Hour) "Hms" else "hms"
        DateTimeStyle.DETAILED -> if (is24Hour) "HmsSSS" else "hmsSSS"
        DateTimeStyle.DATE_NUMERIC, DateTimeStyle.DATE_TEXTUAL -> null
    }
    val pattern = when {
        timeSkeleton == null -> DateFormat.getBestDateTimePattern(locale, dateSkeleton)
        style == DateTimeStyle.DETAILED -> DateFormat.getBestDateTimePattern(locale, dateSkeleton + timeSkeleton)
        else -> DateFormat.getBestDateTimePattern(locale, dateSkeleton) +
                " " +
                DateFormat.getBestDateTimePattern(locale, timeSkeleton)
    }
    val formatter = SimpleDateFormat(pattern, locale).apply { timeZone = zone }
    return formatter.format(Date(timestamp.toEpochMilliseconds()))
}

@Composable
fun formatDateTime(timestamp: Instant, style: DateTimeStyle): String {
    val context = LocalContext.current
    val locale = context.resources.configuration.locales[0]
    val is24Hour = DateFormat.is24HourFormat(context)
    val zone = TimeZone.getDefault()
    return remember(timestamp, locale, is24Hour, zone, style) {
        formatDateTime(
            timestamp = timestamp,
            zone = zone,
            locale = locale,
            is24Hour = is24Hour,
            style = style,
        )
    }
}

@Composable
fun formatSmartTime(
    instant: Instant,
    threshold: Duration = 7.days,
    hoursThreshold: Duration = 4.days,
    absoluteStyle: DateTimeStyle = DateTimeStyle.FULL,
): String {
    val context = LocalContext.current
    val reference = Clock.System.now()
    val isRelative = reference - instant < threshold
    return remember(instant, threshold, hoursThreshold, absoluteStyle, isRelative) {
        formatSmartTime(
            context = context,
            instant = instant,
            reference = reference,
            threshold = threshold,
            hoursThreshold = hoursThreshold,
            absoluteStyle = absoluteStyle,
        )
    }
}

fun formatSmartTime(
    context: Context,
    instant: Instant,
    reference: Instant = Clock.System.now(),
    threshold: Duration = 7.days,
    hoursThreshold: Duration = 4.days,
    absoluteStyle: DateTimeStyle = DateTimeStyle.FULL,
): String {
    val age = reference - instant
    return if (age < threshold) {
        formatRelativeTime(context, instant, reference, hoursThreshold)
    } else {
        formatDateTime(
            timestamp = instant,
            zone = TimeZone.getDefault(),
            locale = context.resources.configuration.locales[0],
            is24Hour = DateFormat.is24HourFormat(context),
            style = absoluteStyle,
        )
    }
}