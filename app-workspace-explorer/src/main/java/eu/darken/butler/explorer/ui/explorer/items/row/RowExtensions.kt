package eu.darken.butler.explorer.ui.explorer.items.row

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.time.Instant

// TODO: This would use a proper date formatter in a real implementation
fun formatDate(timestamp: Instant): String {
    return SimpleDateFormat("MMM dd, yyyy", Locale.getDefault()).format(Date(timestamp.toEpochMilliseconds()))
}