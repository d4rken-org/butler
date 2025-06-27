package eu.darken.butler.main.ui.settings

import eu.darken.butler.common.navigation.Nav
import eu.darken.butler.common.navigation.NavigationDestination
import kotlinx.serialization.Serializable


@Serializable
data object DestinationSettingsGeneral : NavigationDestination

@Suppress("UnusedReceiverParameter")
fun Nav.Settings.general(): NavigationDestination = DestinationSettingsGeneral

@Serializable
data object DestinationSettingsSupport : NavigationDestination

@Suppress("UnusedReceiverParameter")
fun Nav.Settings.support(): NavigationDestination = DestinationSettingsSupport

@Serializable
data object DestinationSettingsAcknowledgements : NavigationDestination

@Suppress("UnusedReceiverParameter")
fun Nav.Settings.acks(): NavigationDestination = DestinationSettingsAcknowledgements

