package eu.darken.butler.common

import android.content.Context
import android.icu.text.RelativeDateTimeFormatter
import android.text.format.DateFormat
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
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
    return remember(instant, hoursThreshold) { formatRelativeTime(context, instant, reference, hoursThreshold) }
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

@Composable
fun formatDate(timestamp: Instant): String {
    val context = LocalContext.current
    return remember(timestamp) { formatDate(context, timestamp) }
}

fun formatDate(context: Context, timestamp: Instant): String {
    // Use Android's DateFormat to respect user's 12/24 hour preference
    val timeFormat = if (DateFormat.is24HourFormat(context)) {
        "HH:mm:ss"
    } else {
        "h:mm:ss a"
    }
    // Full format: abbreviated month, day, year, time with seconds
    val dateFormat = SimpleDateFormat("MMM d, yyyy, $timeFormat", context.resources.configuration.locales[0])
    return dateFormat.format(Date(timestamp.toEpochMilliseconds()))
}

/**
 * Compact, locale-aware timestamp. Same calendar year renders day + time, other years render
 * day + year without a time. The same-year test uses the ISO year so locale calendars with their
 * own eras (Buddhist, Japanese imperial) can't split one Gregorian year in two.
 */
fun formatDateCompact(
    timestamp: Instant,
    now: Instant,
    zone: TimeZone,
    locale: Locale,
    is24Hour: Boolean,
): String {
    val zoneId = zone.toZoneId()
    val isoYear = { instant: Instant ->
        java.time.Instant.ofEpochMilli(instant.toEpochMilliseconds()).atZone(zoneId).year
    }
    val skeleton = when {
        isoYear(timestamp) != isoYear(now) -> "yMMMd"
        is24Hour -> "MMMdHm"
        else -> "MMMdhm"
    }
    val pattern = DateFormat.getBestDateTimePattern(locale, skeleton)
    val formatter = SimpleDateFormat(pattern, locale).apply { timeZone = zone }
    return formatter.format(Date(timestamp.toEpochMilliseconds()))
}

@Composable
fun formatDateCompact(timestamp: Instant): String {
    val context = LocalContext.current
    val locale = context.resources.configuration.locales[0]
    val is24Hour = DateFormat.is24HourFormat(context)
    val zone = TimeZone.getDefault()
    return remember(timestamp, locale, is24Hour, zone) {
        formatDateCompact(
            timestamp = timestamp,
            now = Clock.System.now(),
            zone = zone,
            locale = locale,
            is24Hour = is24Hour,
        )
    }
}

@Composable
fun formatSmartTime(
    instant: Instant,
    threshold: Duration = 7.days,
    hoursThreshold: Duration = 4.days,
): String {
    val context = LocalContext.current
    val reference = Clock.System.now()
    val isRelative = reference - instant < threshold
    return remember(instant, threshold, hoursThreshold, isRelative) {
        formatSmartTime(
            context = context,
            instant = instant,
            reference = reference,
            threshold = threshold,
            hoursThreshold = hoursThreshold,
        )
    }
}

fun formatSmartTime(
    context: Context,
    instant: Instant,
    reference: Instant = Clock.System.now(),
    threshold: Duration = 7.days,
    hoursThreshold: Duration = 4.days,
): String {
    val age = reference - instant
    return if (age < threshold) {
        formatRelativeTime(context, instant, reference, hoursThreshold)
    } else {
        formatDate(context, instant)
    }
}