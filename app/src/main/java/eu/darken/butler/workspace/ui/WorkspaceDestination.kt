package eu.darken.butler.workspace.ui

import eu.darken.butler.common.navigation.Nav
import eu.darken.butler.common.navigation.NavigationDestination
import kotlinx.serialization.Serializable

@Serializable
data object DestinationWorkspaces : NavigationDestination

fun Nav.Main.workspaces(): NavigationDestination = DestinationWorkspaces