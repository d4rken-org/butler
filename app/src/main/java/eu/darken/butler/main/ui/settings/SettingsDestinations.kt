package eu.darken.butler.main.ui.settings

import eu.darken.butler.common.navigation.Nav
import eu.darken.butler.common.navigation.NavigationDestination
import kotlinx.serialization.Serializable


@Serializable
data object DestinationSettingsGeneral : NavigationDestination {
    private fun readResolve(): Any = DestinationSettingsGeneral
}

@Suppress("UnusedReceiverParameter")
fun Nav.Settings.general(): NavigationDestination = DestinationSettingsGeneral

@Serializable
data object DestinationSettingsSupport : NavigationDestination {
    private fun readResolve(): Any = DestinationSettingsSupport
}

@Suppress("UnusedReceiverParameter")
fun Nav.Settings.support(): NavigationDestination = DestinationSettingsSupport

@Serializable
data object DestinationSettingsAcknowledgements : NavigationDestination {
    private fun readResolve(): Any = DestinationSettingsAcknowledgements
}

@Suppress("UnusedReceiverParameter")
fun Nav.Settings.acks(): NavigationDestination = DestinationSettingsAcknowledgements

@Serializable
data object DestinationUpgradeStatus : NavigationDestination {
    private fun readResolve(): Any = DestinationUpgradeStatus
}

@Suppress("UnusedReceiverParameter")
fun Nav.Settings.upgradeStatus(): NavigationDestination = DestinationUpgradeStatus

@Serializable
data object DestinationSettingsPreviews : NavigationDestination {
    private fun readResolve(): Any = DestinationSettingsPreviews
}

@Suppress("UnusedReceiverParameter")
fun Nav.Settings.previews(): NavigationDestination = DestinationSettingsPreviews

@Serializable
data object DestinationSettingsClipboard : NavigationDestination {
    private fun readResolve(): Any = DestinationSettingsClipboard
}

@Suppress("UnusedReceiverParameter")
fun Nav.Settings.clipboard(): NavigationDestination = DestinationSettingsClipboard
