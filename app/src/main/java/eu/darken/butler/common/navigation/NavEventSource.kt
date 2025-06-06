package eu.darken.butler.common.navigation


import eu.darken.butler.common.SingleLiveEvent

interface NavEventSource {
    val navEvents: SingleLiveEvent<Any>
}