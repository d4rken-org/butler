package eu.darken.butler.workspace.core.layout

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
enum class RailButtonPlacement {
    @SerialName("LEADING")
    LEADING,

    @SerialName("TRAILING")
    TRAILING,
}
