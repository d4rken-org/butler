package eu.darken.butler.templates.ui

import eu.darken.butler.common.navigation.Nav
import eu.darken.butler.common.navigation.NavigationDestination
import kotlinx.serialization.Serializable


@Serializable
data object DestinationTemplates : NavigationDestination {
    private fun readResolve(): Any = DestinationTemplates
}

@Suppress("UnusedReceiverParameter")
fun Nav.Workspaces.templates(): NavigationDestination = DestinationTemplates