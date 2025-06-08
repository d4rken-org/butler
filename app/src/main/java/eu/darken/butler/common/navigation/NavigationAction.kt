package eu.darken.butler.common.navigation


import eu.darken.butler.common.flow.SingleEventFlow
import eu.darken.butler.main.ui.Destination

sealed interface NavigationAction {

    data class NavigateTo(val destination: Destination) : NavigationAction
    data object NavigateUp : NavigationAction

    interface Source {
        val navEvents: SingleEventFlow<NavigationAction>
    }
}
