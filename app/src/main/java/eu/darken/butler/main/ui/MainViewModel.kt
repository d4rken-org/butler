package eu.darken.butler.main.ui

import androidx.lifecycle.SavedStateHandle
import dagger.hilt.android.lifecycle.HiltViewModel
import eu.darken.butler.common.coroutine.DispatcherProvider
import eu.darken.butler.common.debug.logging.Logging.Priority.*
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.debug.logging.logTag
import eu.darken.butler.common.theming.themeState
import eu.darken.butler.common.uix.ViewModel3
import eu.darken.butler.common.upgrade.UpgradeRepo
import eu.darken.butler.main.core.GeneralSettings
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.onEach
import javax.inject.Inject


@HiltViewModel
class MainViewModel @Inject constructor(
    dispatcherProvider: DispatcherProvider,
    @Suppress("unused") private val handle: SavedStateHandle,
    private val upgradeRepo: UpgradeRepo,
    private val generalSettings: GeneralSettings,
) : ViewModel3(dispatcherProvider = dispatcherProvider) {

    val themeState = generalSettings.themeState.asStateFlow()

    val state = combine(
        generalSettings.isOnboardingCompleted.flow,
        flowOf(Unit),
    ) { onBoardingComplete, _ ->
        State(
            startScreen = when {
                !onBoardingComplete -> State.StartScreen.ONBOARDING
                else -> State.StartScreen.HOME
            }
        )
    }
        .onEach { log(VERBOSE) { "New state: $it" } }
        .asStateFlow()

    fun checkUpgrades() = launch {
        log(TAG) { "checkUpgrades()" }
        upgradeRepo.refresh()
    }

    data class State(
        val startScreen: StartScreen = StartScreen.ONBOARDING,
    ) {
        enum class StartScreen {
            ONBOARDING,
            HOME,
            ;
        }
    }

    companion object {
        private val TAG = logTag("MainActivity", "ViewModel")
    }
}