package eu.darken.butler.common.files.room

import androidx.room.ProvidedTypeConverter
import androidx.room.TypeConverter
import eu.darken.butler.common.files.APathLookup
import eu.darken.butler.common.serialization.SerializationIO
import kotlinx.serialization.PolymorphicSerializer
import kotlinx.serialization.json.Json
import javax.inject.Inject

@ProvidedTypeConverter
class APathLookupConverter @Inject constructor(@SerializationIO private val json: Json) {

    @TypeConverter
    fun fromAPathLookup(lookup: APathLookup<*>): String =
        json.encodeToString(PolymorphicSerializer(APathLookup::class), lookup)

    @TypeConverter
    fun toAPathLookup(string: String): APathLookup<*> =
        json.decodeFromString(PolymorphicSerializer(APathLookup::class), string)
}
