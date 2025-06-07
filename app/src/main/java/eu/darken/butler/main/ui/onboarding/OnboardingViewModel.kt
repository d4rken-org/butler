package eu.darken.butler.main.ui.onboarding

import androidx.lifecycle.SavedStateHandle
import dagger.hilt.android.lifecycle.HiltViewModel
import eu.darken.butler.common.coroutine.DispatcherProvider
import eu.darken.butler.common.datastore.value
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.debug.logging.logTag
import eu.darken.butler.common.uix.ViewModel3
import eu.darken.butler.main.core.GeneralSettings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import javax.inject.Inject

@HiltViewModel
class OnboardingViewModel @Inject constructor(
    dispatcherProvider: DispatcherProvider,
    @Suppress("unused") private val handle: SavedStateHandle,
    private val generalSettings: GeneralSettings,
) : ViewModel3(dispatcherProvider = dispatcherProvider) {

    private val _currentPage = MutableStateFlow(State.Page.WELCOME)
    val currentPage = _currentPage.asStateFlow()

    val state = combine(
        currentPage,
        generalSettings.isOnboardingCompleted.flow,
    ) { page, isCompleted ->
        State(
            currentPage = page,
        )
    }.asStateFlow()

    fun nextPage() {
        val currentPage = _currentPage.value
        val nextPage = State.Page.entries[_currentPage.value.ordinal + 1]
        log(TAG) { "nextPage(): ${currentPage} -> ${nextPage}" }
        _currentPage.value = nextPage
    }

    fun previousPage() {
//        if (_currentPage.value > 0) {
//            log(TAG) { "previousPage(): ${_currentPage.value} -> ${_currentPage.value - 1}" }
//            _currentPage.value = _currentPage.value - 1
//        }
    }

    fun completeOnboarding() = launch {
        log(TAG) { "completeOnboarding()" }
        generalSettings.isOnboardingCompleted.value(true)
    }

    data class State(
        val currentPage: Page,
    ) {

        enum class Page {
            WELCOME,
            PRIVACY,
            ;
        }
    }

    companion object {
        private val TAG = logTag("Onboarding", "ViewModel")
    }
}