package eu.darken.butler.workspace.core.layout

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
enum class WorkspacePanelMode {
    @SerialName("AUTO")
    AUTO,

    @SerialName("ADAPTIVE")
    ADAPTIVE,

    @SerialName("SINGLE")
    SINGLE,

    @SerialName("SINGLE_RAIL")
    SINGLE_RAIL,

    @SerialName("DUAL_VERTICAL")
    DUAL_VERTICAL,

    @SerialName("DUAL_HORIZONTAL")
    DUAL_HORIZONTAL,

    @SerialName("TRIPLE_SIDEBAR_LEFT")
    TRIPLE_SIDEBAR_LEFT,

    @SerialName("TRIPLE_SIDEBAR_RIGHT")
    TRIPLE_SIDEBAR_RIGHT,

    @SerialName("QUAD_GRID")
    QUAD_GRID,
}