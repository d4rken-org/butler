package eu.darken.butler.apps.core.engine

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class SortSettings(
    @SerialName("mode") val mode: Mode = Mode.NAME,
    @SerialName("reversed") val reversed: Boolean = false,
) {
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
