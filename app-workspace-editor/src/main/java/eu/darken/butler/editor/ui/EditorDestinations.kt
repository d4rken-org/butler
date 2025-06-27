package eu.darken.butler.editor.ui

import eu.darken.butler.common.navigation.Nav
import eu.darken.butler.common.navigation.NavigationDestination
import kotlinx.serialization.Serializable


@Serializable
data object DestinationEditor : NavigationDestination

@Suppress("UnusedReceiverParameter")
fun Nav.Workspaces.editor(): NavigationDestination = DestinationEditor

@Serializable
data object DestinationEditorSettings : NavigationDestination

@Suppress("UnusedReceiverParameter")
fun Nav.Settings.editor(): NavigationDestination = DestinationEditorSettings