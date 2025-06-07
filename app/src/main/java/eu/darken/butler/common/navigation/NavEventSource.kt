package eu.darken.butler.common.navigation


import eu.darken.butler.common.flow.SingleEventFlow

interface NavEvent {

    interface Source {
        val navEvents: SingleEventFlow<NavEvent>
    }
}
