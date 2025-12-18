package eu.darken.butler.explorer.core

import eu.darken.butler.common.files.APath
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
sealed interface DefaultStartLocation {
    @Serializable
    @SerialName("home")
    data object Home : DefaultStartLocation

    @Serializable
    @SerialName("device")
    data object Device : DefaultStartLocation

    @Serializable
    @SerialName("directory")
    data class Directory(val path: APath<*>) : DefaultStartLocation
}
