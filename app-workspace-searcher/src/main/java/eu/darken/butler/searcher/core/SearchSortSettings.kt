package eu.darken.butler.searcher.core

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class SearchSortSettings(
    @SerialName("mode") val mode: Mode = Mode.NAME,
    @SerialName("reversed") val reversed: Boolean = false,
) {
    @Serializable
    enum class Mode {
        @SerialName("NAME") NAME,
        @SerialName("MODIFIED_AT") MODIFIED_AT,
        @SerialName("CREATED_AT") CREATED_AT,
        @SerialName("SIZE") SIZE,
        @SerialName("PATH") PATH,
        ;
    }
}
