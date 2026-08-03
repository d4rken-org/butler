package eu.darken.butler.explorer.core

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
sealed class ExplorerViewStyle {

    @Serializable
    @SerialName("list")
    data class List(
        @SerialName("density") val density: Density = Density.COMFORTABLE,
    ) : ExplorerViewStyle() {
        @Serializable
        enum class Density {
            @SerialName("compact") COMPACT,
            @SerialName("comfortable") COMFORTABLE,
            @SerialName("detailed") DETAILED,
        }
    }

    @Serializable
    @SerialName("grid")
    data class Grid(
        @SerialName("size") val size: GridSize = GridSize.MEDIUM,
    ) : ExplorerViewStyle() {
        @Serializable
        enum class GridSize {
            @SerialName("small") SMALL,      // 90dp min width
            @SerialName("medium") MEDIUM,    // 120dp min width
            @SerialName("large") LARGE,      // 160dp min width
        }
    }

    companion object {
        fun default(): ExplorerViewStyle = List()
    }
}

/** The style the view-style action switches to. */
fun ExplorerViewStyle.toggled(): ExplorerViewStyle = when (this) {
    is ExplorerViewStyle.List -> ExplorerViewStyle.Grid()
    is ExplorerViewStyle.Grid -> ExplorerViewStyle.List()
}
