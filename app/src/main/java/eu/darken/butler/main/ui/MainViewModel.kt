package eu.darken.butler.main.ui

import dagger.hilt.android.lifecycle.HiltViewModel
import eu.darken.butler.common.coroutine.DispatcherProvider
import eu.darken.butler.common.datastore.value
import eu.darken.butler.common.debug.logging.Logging.Priority.*
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.debug.logging.logTag
import eu.darken.butler.common.navigation.NavigationController
import eu.darken.butler.common.theming.themeState
import eu.darken.butler.common.ui.ViewModel4
import eu.darken.butler.main.core.GeneralSettings
import eu.darken.butler.upgrade.UpgradeRepo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.onEach
import javax.inject.Inject


@HiltViewModel
class MainViewModel @Inject constructor(
    dispatcherProvider: DispatcherProvider,
    navCtrl: NavigationController,
    private val upgradeRepo: UpgradeRepo,
    private val generalSettings: GeneralSettings,
) : ViewModel4(dispatcherProvider, logTag("Main","Screen","VM"), navCtrl) {

    val themeState = generalSettings.themeState.asStateFlow()

    private val showExitConfirmationFlow = MutableStateFlow(false)
    private val dontAskAgainFlow = MutableStateFlow(false)

    val state = combine(
        generalSettings.isOnboardingCompleted.flow,
        generalSettings.isConfirmExitEnabled.flow,
        showExitConfirmationFlow,
        dontAskAgainFlow,
        flowOf(Unit),
    ) { onBoardingComplete, confirmExitEnabled, showExitConfirmation, dontAskAgain, _ ->
        State(
            startScreen = when {
                !onBoardingComplete -> State.StartScreen.ONBOARDING
                else -> State.StartScreen.HOME
            },
            confirmExitEnabled = confirmExitEnabled,
            showExitConfirmation = showExitConfirmation,
            dontAskAgain = dontAskAgain
        )
    }
        .onEach { log(VERBOSE) { "New state: $it" } }
        .asStateFlow()

    fun checkUpgrades() = launch {
        log(tag) { "checkUpgrades()" }
        upgradeRepo.refresh()
    }

    val confirmExitEnabled = generalSettings.isConfirmExitEnabled.flow.asStateFlow()

    fun updateConfirmExitEnabled(enabled: Boolean) = launch {
        log(tag) { "updateConfirmExitEnabled($enabled)" }
        generalSettings.isConfirmExitEnabled.value(enabled)
    }

    data class State(
        val startScreen: StartScreen = StartScreen.ONBOARDING,
        val confirmExitEnabled: Boolean = true,
        val showExitConfirmation: Boolean = false,
        val dontAskAgain: Boolean = false,
    ) {
        enum class StartScreen {
            ONBOARDING,
            HOME,
            ;
        }
    }

    fun setShowExitConfirmation(show: Boolean) = launch {
        log(tag) { "setShowExitConfirmation($show)" }
        showExitConfirmationFlow.value = show
    }

    fun setDontAskAgain(dontAsk: Boolean) = launch {
        log(tag) { "setDontAskAgain($dontAsk)" }
        dontAskAgainFlow.value = dontAsk
    }

    fun isConfirmExitEnabled(callback: (Boolean) -> Unit) = launch {
        log(tag) { "isConfirmExitEnabled()" }
        val onboarded = generalSettings.isOnboardingCompleted.flow.first()
        val exitConfirm = generalSettings.isConfirmExitEnabled.flow.first()
        val isEnabled = onboarded && exitConfirm
        log(tag) { "isConfirmExitEnabled(): onboarded=$onboarded, exitConfirm=$exitConfirm, result=$isEnabled" }
        callback(isEnabled)
    }
}
