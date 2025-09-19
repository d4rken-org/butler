package eu.darken.butler.main.ui.settings

import eu.darken.butler.common.navigation.Nav
import eu.darken.butler.common.navigation.NavigationDestination
import kotlinx.serialization.Serializable

@Serializable
data object DestinationShortcutsSettings : NavigationDestination {
    private fun readResolve(): Any = DestinationShortcutsSettings
}

@Suppress("UnusedReceiverParameter")
fun Nav.Settings.shortcuts(): NavigationDestination = DestinationShortcutsSettings