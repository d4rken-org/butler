package eu.darken.butler.main.ui

import eu.darken.butler.common.navigation.NavigationDestination
import kotlinx.serialization.Serializable

@Suppress("JavaIoSerializableObjectMustHaveReadResolve")
sealed interface AppNav : NavigationDestination {

    @Serializable
    data object Workspace : AppNav

    @Serializable
    data object Onboarding : AppNav

    @Serializable
    data object Settings : AppNav {
        @Serializable
        data object General : AppNav

        @Serializable
        data object Support : AppNav

        @Serializable
        data object Acknowledgements : AppNav
    }

}