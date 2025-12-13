package eu.darken.butler.apps.core

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
sealed class AppsViewStyle {

    @Serializable
    @SerialName("list")
    data class List(
        @SerialName("density") val density: Density = Density.COMFORTABLE,
    ) : AppsViewStyle() {
        @Serializable
        enum class Density {
            @SerialName("compact") COMPACT,
            @SerialName("comfortable") COMFORTABLE,
        }
    }

    @Serializable
    @SerialName("grid")
    data class Grid(
        @SerialName("size") val size: GridSize = GridSize.MEDIUM,
    ) : AppsViewStyle() {
        @Serializable
        enum class GridSize {
            @SerialName("small") SMALL,
            @SerialName("medium") MEDIUM,
            @SerialName("large") LARGE,
        }
    }

    companion object {
        fun default(): AppsViewStyle = List()
    }
}
