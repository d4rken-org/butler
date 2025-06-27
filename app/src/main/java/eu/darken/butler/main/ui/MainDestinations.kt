package eu.darken.butler.main.ui

import eu.darken.butler.common.navigation.Nav
import eu.darken.butler.common.navigation.NavigationDestination
import kotlinx.serialization.Serializable


@Serializable
data object DestinationOnboarding : NavigationDestination {
    private fun readResolve(): Any = DestinationOnboarding
}

@Suppress("UnusedReceiverParameter")
fun Nav.Main.onboarding(): NavigationDestination = DestinationOnboarding

@Serializable
data object DestinationSettingsIndex : NavigationDestination {
    private fun readResolve(): Any = DestinationSettingsIndex
}

@Suppress("UnusedReceiverParameter")
fun Nav.Main.settings(): NavigationDestination = DestinationSettingsIndex

@Serializable
data object DestinationUpgrade : NavigationDestination {
    private fun readResolve(): Any = DestinationUpgrade
}

@Suppress("UnusedReceiverParameter")
fun Nav.Main.upgrade(): NavigationDestination = DestinationUpgrade

@Serializable
data object DestinationSetup : NavigationDestination {
    private fun readResolve(): Any = DestinationSetup
}

@Suppress("UnusedReceiverParameter")
fun Nav.Main.destSetup(): NavigationDestination = DestinationSetup
