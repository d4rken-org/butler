package eu.darken.butler.explorer.ui

import eu.darken.butler.common.navigation.Nav
import eu.darken.butler.common.navigation.NavigationDestination
import kotlinx.serialization.Serializable


@Serializable
data object DestinationExplorer : NavigationDestination {
    private fun readResolve(): Any = DestinationExplorer
}

@Suppress("UnusedReceiverParameter")
fun Nav.Workspaces.explorer(): NavigationDestination = DestinationExplorer

@Serializable
data object DestinationExplorerSettings : NavigationDestination {
    private fun readResolve(): Any = DestinationExplorerSettings
}

@Suppress("UnusedReceiverParameter")
fun Nav.Settings.explorer(): NavigationDestination = DestinationExplorerSettings