package eu.darken.butler.workspace.ui.settings

import eu.darken.butler.common.navigation.Nav
import eu.darken.butler.common.navigation.NavigationDestination
import kotlinx.serialization.Serializable

@Serializable
data object DestinationWorkspaceSettings : NavigationDestination {
    private fun readResolve(): Any = DestinationWorkspaceSettings
}

@Suppress("UnusedReceiverParameter")
fun Nav.Settings.workspaces(): NavigationDestination = DestinationWorkspaceSettings