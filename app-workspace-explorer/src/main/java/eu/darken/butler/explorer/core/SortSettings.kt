package eu.darken.butler.explorer.core

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class SortSettings(
    @Json(name = "mode") val mode: Mode = Mode.NAME,
    @Json(name = "reversed") val reversed: Boolean = true,
) {
    @JsonClass(generateAdapter = false)
    enum class Mode {
        @Json(name = "NAME") NAME,
        @Json(name = "MODIFIED_AT") MODIFIED_AT,
        @Json(name = "CREATED_AT") CREATED_AT,
        @Json(name = "SIZE") SIZE,
        ;
    }
}