package eu.darken.butler.explorer.core

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * [mode] is persisted under the wire key `type`, and stored payloads rely on that: renaming it
 * silently resets the saved mode of every tab and every default, keeping their density.
 *
 * [showHidden] defaults to true so a payload written before it existed decodes to what its author
 * saw, which is every dot-prefixed entry on screen.
 */
@Serializable
data class ExplorerViewStyle(
    @SerialName("type") val mode: Mode = Mode.LIST,
    @SerialName("density") val density: Density = Density.COMFORTABLE,
    @SerialName("showhidden") val showHidden: Boolean = true,
) {

    @Serializable
    enum class Mode {
        @SerialName("list") LIST,
        @SerialName("grid") GRID,
    }

    @Serializable
    enum class Density {
        @SerialName("compact") COMPACT,
        @SerialName("comfortable") COMFORTABLE,
        @SerialName("detailed") DETAILED,
    }

    companion object {
        fun default(): ExplorerViewStyle = ExplorerViewStyle()
    }
}
