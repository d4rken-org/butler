package eu.darken.butler.debug.ui

import eu.darken.butler.common.navigation.Nav
import eu.darken.butler.common.navigation.NavigationDestination
import kotlinx.serialization.Serializable


@Serializable
data object DestinationDebug : NavigationDestination {
    private fun readResolve(): Any = DestinationDebug
}

@Suppress("UnusedReceiverParameter")
fun Nav.Workspaces.debug(): NavigationDestination = DestinationDebug
