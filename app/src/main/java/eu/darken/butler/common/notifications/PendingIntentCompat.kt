package eu.darken.butler.common.notifications

import android.app.PendingIntent
import eu.darken.butler.common.hasApiLevel

object PendingIntentCompat {
    val FLAG_IMMUTABLE: Int = if (hasApiLevel(31)) {
        PendingIntent.FLAG_IMMUTABLE
    } else {
        0
    }

    val FLAG_MUTABLE: Int = if (hasApiLevel(31)) {
        @Suppress("NewApi")
        PendingIntent.FLAG_MUTABLE
    } else {
        0
    }
}