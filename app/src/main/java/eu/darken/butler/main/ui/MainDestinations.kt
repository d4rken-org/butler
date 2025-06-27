package eu.darken.butler.main.ui

import eu.darken.butler.common.navigation.Nav
import eu.darken.butler.common.navigation.NavigationDestination
import kotlinx.serialization.Serializable


@Serializable
data object DestinationOnboarding : NavigationDestination

@Suppress("UnusedReceiverParameter")
fun Nav.Main.onboarding(): NavigationDestination = DestinationOnboarding

@Serializable
data object DestinationSettingsIndex : NavigationDestination

@Suppress("UnusedReceiverParameter")
fun Nav.Main.settings(): NavigationDestination = DestinationSettingsIndex

@Serializable
data object DestinationUpgrade : NavigationDestination

@Suppress("UnusedReceiverParameter")
fun Nav.Main.upgrade(): NavigationDestination = DestinationUpgrade

@Serializable
data object DestinationSetup : NavigationDestination

@Suppress("UnusedReceiverParameter")
fun Nav.Main.destSetup(): NavigationDestination = DestinationSetup
