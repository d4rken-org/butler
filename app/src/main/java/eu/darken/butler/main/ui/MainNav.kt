package eu.darken.butler.main.ui

import eu.darken.butler.common.navigation.NavigationDestination
import kotlinx.serialization.Serializable

object AppNav {
    @Suppress("JavaIoSerializableObjectMustHaveReadResolve")
    sealed interface Main : NavigationDestination {

        @Serializable
        data object Workspace : Main

        @Serializable
        data object WorkspaceManager : Main

        @Serializable
        data object Onboarding : Main

        @Serializable
        data object Settings : Main

        @Serializable
        data object Upgrade : Main

        @Serializable
        data object Setup : Main
    }

    sealed interface Settings : NavigationDestination {
        @Serializable
        data object General : Settings

        @Serializable
        data object Explorer : Settings

        @Serializable
        data object Search : Settings

        @Serializable
        data object Editor : Settings

        @Serializable
        data object Support : Settings

        @Serializable
        data object Acknowledgements : Settings

        @Serializable
        data object Workspace : Settings
    }
}
