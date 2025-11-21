package eu.darken.butler.common

import kotlin.math.ln
import kotlin.math.pow

fun Int.toOctal(): String {
    return Integer.toOctalString(this)
}

fun Long.formatAsFileSize(): String {
    if (this <= 0) return "0 B"
    val units = arrayOf("B", "KB", "MB", "GB", "TB")
    val digitGroups = (ln(this.toDouble()) / ln(1024.0)).toInt()
    val unitIndex = digitGroups.coerceAtMost(units.size - 1)
    val size = this / 1024.0.pow(unitIndex.toDouble())
    return "%.1f %s".format(size, units[unitIndex])
}