package eu.darken.butler.common.error

import eu.darken.butler.common.SingleLiveEvent

interface ErrorEventSource {
    val errorEvents: SingleLiveEvent<Throwable>
}