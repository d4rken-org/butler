package eu.darken.butler.searcher.ui.search

import android.text.format.DateUtils
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import kotlin.time.Instant

@Composable
fun formatRelativeTime(instant: Instant): String {
    return remember(instant) {
        DateUtils.getRelativeTimeSpanString(
            instant.toEpochMilliseconds(),
            System.currentTimeMillis(),
            DateUtils.MINUTE_IN_MILLIS,
            DateUtils.FORMAT_ABBREV_RELATIVE
        ).toString()
    }
}