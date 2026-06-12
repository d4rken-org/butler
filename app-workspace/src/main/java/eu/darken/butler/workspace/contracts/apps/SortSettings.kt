package eu.darken.butler.workspace.contracts.apps

import android.os.Parcelable
import kotlinx.parcelize.Parcelize
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
@Parcelize
data class SortSettings(
    @SerialName("mode") val mode: Mode = Mode.NAME,
    @SerialName("reversed") val reversed: Boolean = false,
) : Parcelable {
    @Serializable
    enum class Mode {
        @SerialName("NAME") NAME,
        @SerialName("SIZE") SIZE,
        @SerialName("INSTALL_DATE") INSTALL_DATE,
        @SerialName("UPDATE_DATE") UPDATE_DATE,
        @SerialName("PACKAGE_NAME") PACKAGE_NAME,
        ;
    }
}
