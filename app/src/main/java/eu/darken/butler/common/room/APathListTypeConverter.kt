package eu.darken.butler.common.room

import androidx.room.TypeConverter
import com.squareup.moshi.Moshi
import com.squareup.moshi.adapter
import eu.darken.butler.common.files.APath
import javax.inject.Inject


class APathListTypeConverter @Inject constructor(
    moshi: Moshi,
) {
    private val adapter = moshi.adapter<List<APath>>()

    @TypeConverter
    fun from(value: List<APath>): String = adapter.toJson(value)

    @TypeConverter
    fun to(value: String): List<APath> = adapter.fromJson(value)!!
}