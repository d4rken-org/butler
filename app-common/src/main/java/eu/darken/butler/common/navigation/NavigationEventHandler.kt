package eu.darken.butler.common.navigation

import android.app.Activity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
import eu.darken.butler.common.debug.logging.Logging.Priority.*
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.debug.logging.logTag

@Composable
fun NavigationEventHandler(vararg sources: NavigationEventSource) {
    val navController = LocalNavigationController.current
    if (navController == null) {
        log(TAG, WARN) { "NavigationController is unavailable" }
        return
    }
    val context = LocalContext.current
    val activity = context as? Activity

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
                    is NavEvent.GoToSingleTop -> navController.goToSingleTop(event.destination)
                    is NavEvent.Up -> navController.up()
                    is NavEvent.Finish -> {
                        log(TAG) { "Finish event received, closing activity" }
                        activity?.finish()
                    }
                }
            }
        }
    }
}

private val TAG = logTag("Navigation", "EventHandler")
