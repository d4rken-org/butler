package eu.darken.butler.common.tour

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class TourPreferences(
    @SerialName("completed") val completed: Set<String> = emptySet(),
    @SerialName("dismissed") val dismissed: Set<String> = emptySet(),
)
