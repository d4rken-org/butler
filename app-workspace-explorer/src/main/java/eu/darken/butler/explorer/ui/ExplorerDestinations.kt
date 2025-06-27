package eu.darken.butler.explorer.ui

import eu.darken.butler.common.navigation.Nav
import eu.darken.butler.common.navigation.NavigationDestination
import kotlinx.serialization.Serializable


@Serializable
data object DestinationExplorer : NavigationDestination

@Suppress("UnusedReceiverParameter")
fun Nav.Workspaces.explorer(): NavigationDestination = DestinationExplorer

@Serializable
data object DestinationExplorerSettings : NavigationDestination

@Suppress("UnusedReceiverParameter")
fun Nav.Settings.explorer(): NavigationDestination = DestinationExplorerSettings