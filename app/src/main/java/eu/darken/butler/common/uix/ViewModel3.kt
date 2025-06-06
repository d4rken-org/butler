package eu.darken.butler.common.uix

import eu.darken.butler.common.SingleLiveEvent
import eu.darken.butler.common.coroutine.DispatcherProvider
import eu.darken.butler.common.error.ErrorEventSource


abstract class ViewModel3(
    dispatcherProvider: DispatcherProvider,
) : ViewModel2(dispatcherProvider), ErrorEventSource {

    override val errorEvents = SingleLiveEvent<Throwable>()

}