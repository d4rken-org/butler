package eu.darken.butler.explorer.ui.explorer.items.row

import android.text.format.DateUtils
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import kotlin.time.Instant

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