package eu.darken.butler.common.files.room

import androidx.room.ProvidedTypeConverter
import androidx.room.TypeConverter
import eu.darken.butler.common.files.APath
import eu.darken.butler.common.serialization.SerializationIO
import kotlinx.serialization.PolymorphicSerializer
import kotlinx.serialization.json.Json
import javax.inject.Inject

@ProvidedTypeConverter
class APathConverter @Inject constructor(
    @SerializationIO private val json: Json
) {

    @TypeConverter
    fun fromAPath(path: APath<*>): String = json.encodeToString(PolymorphicSerializer(APath::class), path)

    @TypeConverter
    fun toAPath(string: String): APath<*> = json.decodeFromString(PolymorphicSerializer(APath::class), string)
}
