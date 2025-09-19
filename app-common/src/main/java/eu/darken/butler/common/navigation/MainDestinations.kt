package eu.darken.butler.common.navigation

import eu.darken.butler.setup.core.SetupModule
import kotlinx.serialization.Serializable


@Serializable
data object DestinationOnboarding : NavigationDestination {
    private fun readResolve(): Any = DestinationOnboarding
}

@Suppress("UnusedReceiverParameter")
fun Nav.Main.onboarding(): NavigationDestination = DestinationOnboarding

@Serializable
data object DestinationSettingsIndex : NavigationDestination {
    private fun readResolve(): Any = DestinationSettingsIndex
}

@Suppress("UnusedReceiverParameter")
fun Nav.Main.settings(): NavigationDestination = DestinationSettingsIndex

@Serializable
data object DestinationUpgrade : NavigationDestination {
    private fun readResolve(): Any = DestinationUpgrade
}

@Suppress("UnusedReceiverParameter")
fun Nav.Main.upgrade(): NavigationDestination = DestinationUpgrade

@Serializable
data class DestinationSetup(
    val typeFilter: Set<SetupModule.Type>? = null,
    val requiredTypes: Set<SetupModule.Type>? = null,
    val isOnboarding: Boolean = false,
    val showCompleted: Boolean = false,
    val autoCloseWhenComplete: Boolean = true,
) : NavigationDestination

@Suppress("UnusedReceiverParameter")
fun Nav.Main.destSetup(
    typeFilter: Set<SetupModule.Type>? = null,
    requiredTypes: Set<SetupModule.Type>? = null,
    isOnboarding: Boolean = false,
    showCompleted: Boolean = false,
    autoCloseWhenComplete: Boolean = true,
): NavigationDestination = DestinationSetup(
    typeFilter = typeFilter,
    requiredTypes = requiredTypes,
    isOnboarding = isOnboarding,
    showCompleted = showCompleted,
    autoCloseWhenComplete = autoCloseWhenComplete,
)

