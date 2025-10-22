package eu.darken.butler.common

import android.content.Context
import android.icu.text.RelativeDateTimeFormatter
import android.text.format.DateUtils
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Instant


@Composable
fun formatRelativeTime(
    instant: Instant,
    reference: Instant = Clock.System.now()
): String {
    val context = LocalContext.current
    return remember(instant) { formatRelativeTime(context, instant, reference) }
}

fun formatRelativeTime(
    context: Context,
    instant: Instant,
    reference: Instant = Clock.System.now()
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
        duration.inWholeHours < 24 -> formatter.format(
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
fun formatDuration(duration: Duration, shortStyle: Boolean = false): String {
    val context = LocalContext.current
    return remember(duration) { formatDuration(context, duration, shortStyle) }
}

fun formatDuration(context: Context, duration: Duration, shortStyle: Boolean = false): String {
    val seconds = duration.inWholeSeconds
    val minutes = duration.inWholeMinutes
    val hours = duration.inWholeHours
    val days = duration.inWholeDays

    return when {
        days > 0 -> {
            val remainingHours = hours % 24
            if (shortStyle) {
                context.resources.getQuantityString(
                    R.plurals.common_duration_days_short,
                    days.toInt(),
                    days.toInt()
                )
            } else {
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
        }
        hours > 0 -> {
            val remainingMinutes = minutes % 60
            if (shortStyle) {
                context.resources.getQuantityString(
                    R.plurals.common_duration_hours_short,
                    hours.toInt(),
                    hours.toInt()
                )
            } else {
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
        }
        minutes > 0 -> {
            val remainingSeconds = seconds % 60
            if (shortStyle) {
                context.resources.getQuantityString(
                    R.plurals.common_duration_minutes_short,
                    minutes.toInt(),
                    minutes.toInt()
                )
            } else {
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
        }
        else -> {
            if (shortStyle) {
                context.resources.getQuantityString(
                    R.plurals.common_duration_seconds_short,
                    seconds.toInt(),
                    seconds.toInt()
                )
            } else {
                context.resources.getQuantityString(
                    R.plurals.common_duration_seconds_full,
                    seconds.toInt(),
                    seconds.toInt()
                )
            }
        }
    }
}

@Composable
fun formatDate(timestamp: Instant): String {
    return DateUtils.formatDateTime(
        LocalContext.current,
        timestamp.toEpochMilliseconds(),
        DateUtils.FORMAT_SHOW_YEAR or
            DateUtils.FORMAT_SHOW_DATE or
            DateUtils.FORMAT_SHOW_TIME or
            DateUtils.FORMAT_ABBREV_ALL
    )
}