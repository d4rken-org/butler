package eu.darken.butler.common.navigation

import androidx.navigation3.runtime.NavBackStack
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.debug.logging.logTag
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class NavigationController @Inject constructor() {
    private var _backStack: NavBackStack? = null

    private val backStack: NavBackStack
        get() = _backStack ?: error("NavigationController not initialized")

    fun setup(backStack: NavBackStack) {
        log(TAG) { "setup()" }
        _backStack = backStack
    }

    fun up(): Boolean {
        val removed = backStack.removeLastOrNull()
        log(TAG) { "up() to ${backStack.last()} (removed $removed)" }
        return removed != null
    }

    fun goTo(destination: NavTarget, popUpTo: NavTarget? = null, inclusive: Boolean = false) {
        log(TAG) { "goTo($destination, popUpTo=$popUpTo, inclusive=$inclusive)" }
        
        if (popUpTo != null) {
            while (backStack.isNotEmpty() && backStack.last() != popUpTo) {
                val removed = backStack.removeLastOrNull()
                log(TAG) { "Popping $removed while looking for $popUpTo" }
            }

            if (inclusive && backStack.isNotEmpty() && backStack.last() == popUpTo) {
                val removed = backStack.removeLastOrNull()
                log(TAG) { "Popping $removed (inclusive)" }
            }
        }
        
        backStack.add(destination)
    }

    fun replace(destination: NavTarget) {
        backStack.removeLastOrNull()
        backStack.add(destination)
    }

    companion object {
        private val TAG = logTag("Navigation", "Controller")
    }
}