package eu.darken.butler.common.room

import androidx.room.TypeConverter
import kotlin.time.Instant

class InstantConverter {
    @TypeConverter
    fun fromInstant(instant: Instant?): Long? = instant?.toEpochMilliseconds()
    
    @TypeConverter
    fun toInstant(millis: Long?): Instant? = millis?.let { Instant.fromEpochMilliseconds(it) }
}