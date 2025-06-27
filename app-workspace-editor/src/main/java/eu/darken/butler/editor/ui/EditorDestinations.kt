package eu.darken.butler.editor.ui

import eu.darken.butler.common.navigation.Nav
import eu.darken.butler.common.navigation.NavigationDestination
import kotlinx.serialization.Serializable


@Serializable
data object DestinationEditor : NavigationDestination {
    private fun readResolve(): Any = DestinationEditor
}

@Suppress("UnusedReceiverParameter")
fun Nav.Workspaces.editor(): NavigationDestination = DestinationEditor

@Serializable
data object DestinationEditorSettings : NavigationDestination {
    private fun readResolve(): Any = DestinationEditorSettings
}

@Suppress("UnusedReceiverParameter")
fun Nav.Settings.editor(): NavigationDestination = DestinationEditorSettings