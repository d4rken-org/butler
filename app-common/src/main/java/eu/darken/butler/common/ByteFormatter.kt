package eu.darken.butler.common

import android.content.Context
import android.text.format.Formatter
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import java.text.DecimalFormatSymbols
import java.util.Locale
import kotlin.math.roundToInt

object ByteFormatter {
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
    fun formatFileSize(bytes: Long): String {
        return formatFileSize(LocalContext.current, bytes)
    }
}