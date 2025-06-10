package eu.darken.butler.main.ui.settings

import dagger.hilt.android.lifecycle.HiltViewModel
import eu.darken.butler.common.coroutine.DispatcherProvider
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.debug.logging.logTag
import eu.darken.butler.common.navigation.NavigationController
import eu.darken.butler.common.uix.ViewModel3
import kotlinx.coroutines.flow.flowOf
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    dispatcherProvider: DispatcherProvider,
    private val navCtrl: NavigationController,
) : ViewModel3(dispatcherProvider, logTag("Onboarding", "ViewModel")) {

    val state = flowOf(State()).asStateFlow()

    fun navigateUp() {
        log(TAG) { "navigateUp()" }
        navCtrl.up()
    }

    data class State(
        val versionText: String = "v0.0.0-rc0",
    )

    companion object {
        private val TAG = logTag("Settings", "ViewModel")
    }
}