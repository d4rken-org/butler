package eu.darken.butler.common.uix

import eu.darken.butler.common.SingleLiveEvent
import eu.darken.butler.common.coroutine.DispatcherProvider
import eu.darken.butler.common.error.ErrorEventSource
import eu.darken.butler.common.navigation.NavEventSource


abstract class ViewModel3(
    dispatcherProvider: DispatcherProvider,
) : ViewModel2(dispatcherProvider), ErrorEventSource {

    override val errorEvents = SingleLiveEvent<Throwable>()

}