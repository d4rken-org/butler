package eu.darken.butler.common.uix

import eu.darken.butler.common.coroutine.DispatcherProvider
import eu.darken.butler.common.debug.logging.asLog
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.flow.SingleEventFlow
import eu.darken.butler.common.navigation.NavEvent
import kotlinx.coroutines.CoroutineExceptionHandler


abstract class ViewModel3(
    dispatcherProvider: DispatcherProvider,
    override val tag: String = defaultTag(),
) : ViewModel2(dispatcherProvider), NavEvent.Source {

    override val navEvents = SingleEventFlow<NavEvent>()
    val errorEvents = SingleEventFlow<Throwable>()

    override var launchErrorHandler: CoroutineExceptionHandler? = CoroutineExceptionHandler { _, ex ->
        log(tag) { "Error during launch: ${ex.asLog()}" }
        errorEvents.emitBlocking(ex)
    }

//    fun NavEvent.navigate() {
//        navEvents.emitBlocking(this@navigate)
//    }
//
//    fun popNavStack() {
//        navEvents.emitBlocking(null)
//    }

    companion object {
        private fun defaultTag(): String = this::class.simpleName ?: "VM3"
    }
}