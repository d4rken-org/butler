package eu.darken.butler.common.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect

@Composable
fun NavigationEventHandler(vararg sources: NavigationEventSource) {
    val navController = LocalNavigationController.current ?: return

    sources.forEach { source ->
        val navEvents = source.navEvents
        LaunchedEffect(navEvents) {
            navEvents.collect { event ->
                when (event) {
                    is NavEvent.GoTo -> navController.goTo(
                        destination = event.destination,
                        popUpTo = event.popUpTo,
                        inclusive = event.inclusive,
                    )
                    NavEvent.Up -> navController.up()
                }
            }
        }
    }
}
