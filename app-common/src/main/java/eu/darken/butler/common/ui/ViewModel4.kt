package eu.darken.butler.common.ui

import eu.darken.butler.common.coroutine.DispatcherProvider
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.flow.SingleEventFlow
import eu.darken.butler.common.navigation.NavEvent
import eu.darken.butler.common.navigation.NavigationDestination
import eu.darken.butler.common.navigation.NavigationEventSource

abstract class ViewModel4(
    dispatcherProvider: DispatcherProvider,
    override val tag: String = defaultTag(),
) : ViewModel3(dispatcherProvider, tag), NavigationEventSource {

    override val navEvents = SingleEventFlow<NavEvent>()

    fun navTo(
        destination: NavigationDestination,
        popUpTo: NavigationDestination? = null,
        inclusive: Boolean = false,
    ) {
        log(tag) { "navTo($destination)" }
        navEvents.tryEmit(NavEvent.GoTo(destination, popUpTo, inclusive))
    }

    fun navToSingleTop(destination: NavigationDestination) {
        log(tag) { "navToSingleTop($destination)" }
        navEvents.tryEmit(NavEvent.GoToSingleTop(destination))
    }

    fun navUp() {
        log(tag) { "navUp()" }
        navEvents.tryEmit(NavEvent.Up)
    }

    companion object {
        private fun defaultTag(): String = this::class.simpleName ?: "VM4"
    }
}
