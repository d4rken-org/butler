package eu.darken.butler.history.ui

import eu.darken.butler.common.navigation.Nav
import eu.darken.butler.common.navigation.NavigationDestination
import kotlinx.serialization.Serializable

@Serializable
data object DestinationHistory : NavigationDestination {
    private fun readResolve(): Any = DestinationHistory
}

@Suppress("UnusedReceiverParameter")
fun Nav.Workspaces.history(): NavigationDestination = DestinationHistory

@Serializable
data object DestinationHistorySettings : NavigationDestination {
    private fun readResolve(): Any = DestinationHistorySettings
}

@Suppress("UnusedReceiverParameter")
fun Nav.Settings.history(): NavigationDestination = DestinationHistorySettings
