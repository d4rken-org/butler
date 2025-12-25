package eu.darken.butler.developer.ui

import eu.darken.butler.common.navigation.Nav
import eu.darken.butler.common.navigation.NavigationDestination
import kotlinx.serialization.Serializable


@Serializable
data object DestinationDeveloper : NavigationDestination {
    private fun readResolve(): Any = DestinationDeveloper
}

@Suppress("UnusedReceiverParameter")
fun Nav.Workspaces.developer(): NavigationDestination = DestinationDeveloper
