package eu.darken.butler.common.navigation

import eu.darken.butler.common.flow.SingleEventFlow

interface NavigationEventSource {
    val navEvents: SingleEventFlow<NavEvent>
}
