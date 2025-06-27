package eu.darken.butler.workspace.ui.settings

import eu.darken.butler.common.navigation.Nav
import eu.darken.butler.common.navigation.NavigationDestination
import kotlinx.serialization.Serializable

@Serializable
data object DestinationWorkspaceSettings : NavigationDestination

@Suppress("UnusedReceiverParameter")
fun Nav.Settings.workspaces(): NavigationDestination = DestinationWorkspaceSettings