package eu.darken.butler.searcher.core

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
sealed class SearcherViewStyle {

    @Serializable
    @SerialName("list")
    data class List(
        @SerialName("density") val density: Density = Density.COMFORTABLE,
    ) : SearcherViewStyle() {
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
    ) : SearcherViewStyle() {
        @Serializable
        enum class GridSize {
            @SerialName("small") SMALL,
            @SerialName("medium") MEDIUM,
            @SerialName("large") LARGE,
        }
    }

    companion object {
        fun default(): SearcherViewStyle = List()
    }
}
