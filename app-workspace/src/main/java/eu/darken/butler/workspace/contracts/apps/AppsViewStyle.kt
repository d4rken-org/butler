package eu.darken.butler.workspace.contracts.apps

import android.os.Parcelable
import kotlinx.parcelize.Parcelize
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * [mode] keeps the wire key `type` a sealed hierarchy once wrote its discriminator under, so a
 * payload written by an older version still decodes: the mode survives, unknown keys are ignored
 * and [density] falls back to its default.
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
