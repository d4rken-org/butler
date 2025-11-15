package eu.darken.butler.workspace.ui

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
enum class WorkspacePanelMode {
    @SerialName("AUTO")
    AUTO,

    @SerialName("SINGLE")
    SINGLE,

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