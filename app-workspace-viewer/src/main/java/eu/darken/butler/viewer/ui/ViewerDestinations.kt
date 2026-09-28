package eu.darken.butler.viewer.ui

import eu.darken.butler.common.navigation.Nav
import eu.darken.butler.common.navigation.NavigationDestination
import kotlinx.serialization.Serializable

@Serializable
data object DestinationViewerSettings : NavigationDestination {
    private fun readResolve(): Any = DestinationViewerSettings
}

@Suppress("UnusedReceiverParameter")
fun Nav.Settings.viewer(): NavigationDestination = DestinationViewerSettings
