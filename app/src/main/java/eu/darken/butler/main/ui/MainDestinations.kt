package eu.darken.butler.main.ui

import androidx.navigation3.runtime.NavKey
import kotlinx.serialization.Serializable

@Suppress("JavaIoSerializableObjectMustHaveReadResolve")
sealed interface Destination : NavKey, java.io.Serializable {

    @Serializable
    data object Home : Destination

    @Serializable
    data object Onboarding : Destination

    @Serializable
    data object Settings : Destination {
        @Serializable
        data object General : Destination
    }

}