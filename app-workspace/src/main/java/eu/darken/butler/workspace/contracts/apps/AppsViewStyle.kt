package eu.darken.butler.workspace.contracts.apps

import android.os.Parcelable
import kotlinx.parcelize.Parcelize
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * [mode] is persisted under the wire key `type`, and stored payloads rely on that: renaming it
 * silently resets every saved view style to the default.
 */
@Serializable
@Parcelize
data class AppsViewStyle(
    @SerialName("type") val mode: Mode = Mode.LIST,
    @SerialName("density") val density: Density = Density.COMFORTABLE,
) : Parcelable {

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
        fun default(): AppsViewStyle = AppsViewStyle()
    }
}
