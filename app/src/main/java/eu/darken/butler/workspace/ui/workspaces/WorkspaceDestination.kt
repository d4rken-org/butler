package eu.darken.butler.workspace.ui.workspaces

import eu.darken.butler.common.navigation.Nav
import eu.darken.butler.common.navigation.NavigationDestination
import kotlinx.serialization.Serializable

@Serializable
data object DestinationWorkspaces : NavigationDestination {
    private fun readResolve(): Any = DestinationWorkspaces
}

fun Nav.Main.workspaces(): NavigationDestination = DestinationWorkspaces