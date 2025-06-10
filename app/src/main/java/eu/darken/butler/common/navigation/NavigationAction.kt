package eu.darken.butler.common.navigation


import eu.darken.butler.common.flow.SingleEventFlow
import eu.darken.butler.main.ui.MainDestinations

sealed interface NavigationAction {

    data class NavigateTo(val destination: MainDestinations) : NavigationAction
    data object NavigateUp : NavigationAction

    interface Source {
        val navEvents: SingleEventFlow<NavigationAction>
    }
}
