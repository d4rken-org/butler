package eu.darken.butler.common.room

import androidx.room.TypeConverter
import kotlin.uuid.Uuid

class UuidConverter {

    @TypeConverter
    fun fromUuid(uuid: Uuid): String = uuid.toString()

    @TypeConverter
    fun toUuid(string: String): Uuid = Uuid.parse(string)

}
