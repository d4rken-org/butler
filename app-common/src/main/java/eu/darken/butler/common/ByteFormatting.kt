package eu.darken.butler.common

import android.content.Context
import android.text.format.Formatter
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import java.text.DecimalFormatSymbols
import java.util.Locale
import kotlin.math.roundToInt

fun stripSizeUnit(formattedSize: String): Double? {
    val ds = DecimalFormatSymbols(Locale.getDefault()).decimalSeparator
    val match = Regex("^(\\d+(?:$ds\\d+)?)\\s*?.+\$").matchEntire(formattedSize) ?: return null
    val (value) = match.destructured
    return value.toDoubleOrNull()
}

fun formatSizeWithUnit(
    context: Context,
    size: Long,
    shortFormat: Boolean = true,
): Pair<String, Int> {
    val formatted = formatFileSize(context, size, shortFormat)
    val quantity = stripSizeUnit(formatted)?.roundToInt() ?: size.toInt()
    return formatted to quantity
}

fun formatFileSize(
    context: Context,
    bytes: Long,
    shortFormat: Boolean = true,
): String = if (shortFormat) {
    Formatter.formatShortFileSize(context, bytes)
} else {
    Formatter.formatFileSize(context, bytes)
}

@Composable
fun formatFileSize(bytes: Long, shortFormat: Boolean = true): String {
    return formatFileSize(context = LocalContext.current, bytes = bytes, shortFormat = shortFormat)
}

fun formatByteSpeed(
    context: Context,
    bytesPerSecond: Long,
    shortFormat: Boolean = true,
): String = context.resources.getQuantityString(
    R.plurals.general_progress_bytes_per_second,
    bytesPerSecond.toInt(),
    formatFileSize(context, bytesPerSecond, shortFormat)
)

@Composable
fun formatByteSpeed(bytesPerSecond: Long, shortFormat: Boolean = true): String = formatByteSpeed(
    context = LocalContext.current,
    bytesPerSecond = bytesPerSecond,
    shortFormat = shortFormat
)

fun formatItemSpeed(
    context: Context,
    itemsPerSecond: Double,
): String = context.resources.getQuantityString(
    R.plurals.general_progress_items_per_second,
    itemsPerSecond.toInt(),
    if (itemsPerSecond >= 1.0) {
        itemsPerSecond.toInt()
    } else {
        String.format(Locale.getDefault(), "%.1f", itemsPerSecond)
    }
)

@Composable
fun formatItemSpeed(itemsPerSecond: Double): String = formatItemSpeed(
    context = LocalContext.current,
    itemsPerSecond = itemsPerSecond
)