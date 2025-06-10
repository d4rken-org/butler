package eu.darken.butler.main.ui

import eu.darken.butler.common.navigation.NavTarget
import kotlinx.serialization.Serializable

@Suppress("JavaIoSerializableObjectMustHaveReadResolve")
sealed interface MainDestinations : NavTarget {

    @Serializable
    data object Home : MainDestinations

    @Serializable
    data object Onboarding : MainDestinations

    @Serializable
    data object Settings : MainDestinations {
        @Serializable
        data object General : MainDestinations
    }

}