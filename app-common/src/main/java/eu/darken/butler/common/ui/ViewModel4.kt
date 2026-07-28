package eu.darken.butler.common.ui

import eu.darken.butler.common.coroutine.DispatcherProvider
import eu.darken.butler.common.debug.logging.Logging.Priority.WARN
import eu.darken.butler.common.debug.logging.asLog
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.flow.SingleEventFlow
import eu.darken.butler.common.navigation.NavEvent
import eu.darken.butler.common.navigation.NavigationDestination
import eu.darken.butler.common.navigation.NavigationEventSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.stateIn

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

    /**
     * Collect a render-state flow in [vmScope] and convert upstream failures into explicit fallback
     * UI state plus an [errorEvents] emission. Cancellation is never converted into UI state.
     *
     * Compose render state must stay collector-safe and never throw into `collectAsState()`.
     */
    protected fun <T> Flow<T>.safeStateIn(
        initialValue: T,
        started: SharingStarted = SharingStarted.WhileSubscribed(5000),
        onError: (Throwable) -> T,
    ): StateFlow<T> = this
        .catch { ex ->
            if (ex is CancellationException) throw ex

            log(tag, WARN) { "Error during state collection: ${ex.asLog()}" }
            errorEvents.emit(ex)
            emit(onError(ex))
        }
        .stateIn(
            scope = vmScope,
            started = started,
            initialValue = initialValue,
        )

    companion object {
        private fun defaultTag(): String = this::class.simpleName ?: "VM4"
    }
}
