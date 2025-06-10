package eu.darken.butler.common.uix

import eu.darken.butler.common.coroutine.DispatcherProvider
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.navigation.NavigationController
import eu.darken.butler.common.navigation.NavigationDestination


abstract class ViewModel4(
    dispatcherProvider: DispatcherProvider,
    override val tag: String = defaultTag(),
    private val navCtrl: NavigationController,
) : ViewModel3(dispatcherProvider,tag) {

    fun goTo(destination: NavigationDestination?) {
        log(tag) { "goTo($destination)" }
        if (destination == null) {
            navCtrl.up()
        } else {
            navCtrl.goTo(destination)
        }
    }

    companion object {
        private fun defaultTag(): String = this::class.simpleName ?: "VM3"
    }
}