package eu.darken.butler.common.uix

import androidx.navigation.NavDirections
import eu.darken.butler.common.SingleLiveEvent
import eu.darken.butler.common.coroutine.DispatcherProvider
import eu.darken.butler.common.error.ErrorEventSource
import eu.darken.butler.common.navigation.NavEventSource
import eu.darken.butler.common.navigation.navVia


abstract class ViewModel3(
    dispatcherProvider: DispatcherProvider,
) : ViewModel2(dispatcherProvider), NavEventSource, ErrorEventSource {

    override val navEvents = SingleLiveEvent<NavDirections?>()
    override val errorEvents = SingleLiveEvent<Throwable>()

    fun NavDirections.navigate() {
        navVia(navEvents)
    }

    fun popNavStack() {
        navEvents.postValue(null)
    }
}