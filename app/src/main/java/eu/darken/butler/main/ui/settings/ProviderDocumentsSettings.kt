package eu.darken.butler.main.ui.settings

import eu.darken.butler.common.navigation.Nav
import eu.darken.butler.common.navigation.NavigationDestination
import kotlinx.serialization.Serializable

@Serializable
data object ProviderDocumentsSettings : NavigationDestination {
    private fun readResolve(): Any = ProviderDocumentsSettings
}

@Suppress("UnusedReceiverParameter")
fun Nav.Settings.providerDocuments(): NavigationDestination = ProviderDocumentsSettings
