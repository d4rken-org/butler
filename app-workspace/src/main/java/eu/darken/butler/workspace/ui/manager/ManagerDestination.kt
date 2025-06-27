package eu.darken.butler.workspace.ui.manager

import eu.darken.butler.common.navigation.Nav
import eu.darken.butler.common.navigation.NavigationDestination
import kotlinx.serialization.Serializable

@Serializable
data object DestinationWorkspaceManager : NavigationDestination {
    private fun readResolve(): Any = DestinationWorkspaceManager
}

@Suppress("UnusedReceiverParameter")
fun Nav.workspaceManager(): NavigationDestination = DestinationWorkspaceManager