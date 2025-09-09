package eu.darken.butler.common.room

import androidx.room.TypeConverter
import kotlin.time.Instant

class InstantConverter {
    @TypeConverter
    fun fromValue(value: Long?): Instant? = value?.let { Instant.fromEpochMilliseconds(it) }

    @TypeConverter
    fun toValue(instant: Instant?): Long? = instant?.toEpochMilliseconds()
}