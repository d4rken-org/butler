package eu.darken.butler.main.ui.onboarding

import androidx.lifecycle.SavedStateHandle
import dagger.hilt.android.lifecycle.HiltViewModel
import eu.darken.butler.common.BuildConfigWrap
import eu.darken.butler.common.ButlerLinks
import eu.darken.butler.common.WebpageTool
import eu.darken.butler.common.coroutine.DispatcherProvider
import eu.darken.butler.common.datastore.value
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.debug.logging.logTag
import eu.darken.butler.common.navigation.NavigationController
import eu.darken.butler.common.uix.ViewModel3
import eu.darken.butler.main.core.GeneralSettings
import eu.darken.butler.main.core.motd.MotdSettings
import eu.darken.butler.main.ui.MainDestinations
import kotlinx.coroutines.flow.combine
import javax.inject.Inject

@HiltViewModel
class OnboardingViewModel @Inject constructor(
    dispatcherProvider: DispatcherProvider,
    @Suppress("unused") private val handle: SavedStateHandle,
    private val generalSettings: GeneralSettings,
    private val motdSettings: MotdSettings,
    private val webpageTool: WebpageTool,
    private val navigationController: NavigationController,
) : ViewModel3(dispatcherProvider, logTag("Onboarding", "ViewModel")) {

    val state = combine(
        generalSettings.isOnboardingCompleted.flow,
        generalSettings.isUpdateCheckEnabled.flow,
        motdSettings.isMotdEnabled.flow,
    ) { isCompleted, isUpdateCheckEnabled, isMotdCheckEnabled ->
        State(
            isUpdateCheckEnabled = isUpdateCheckEnabled,
            isMotdCheckEnabled = isMotdCheckEnabled,
        )
    }.asStateFlow()

    fun completeOnboarding() = launch {
        log(tag) { "completeOnboarding()" }
        generalSettings.isOnboardingCompleted.value(true)
        navigationController.goTo(
            MainDestinations.Home,
            popUpTo = MainDestinations.Home,
            inclusive = true
        )
    }

    fun setUpdateCheckEnabled(enabled: Boolean) = launch {
        log(tag) { "setUpdateCheckEnabled($enabled)" }
        generalSettings.isUpdateCheckEnabled.value(enabled)
    }

    fun setMotdCheckEnabled(enabled: Boolean) = launch {
        log(tag) { "setMotdCheckEnabled($enabled)" }
        motdSettings.isMotdEnabled.value(enabled)
    }

    fun readPrivacyPolicy() = launch {
        log(tag) { "readPrivacyPolicy()" }
        webpageTool.open(ButlerLinks.PRIVACY_POLICY)
    }

    data class State(
        val startPage: Page = Page.WELCOME,
        val isUpdateCheckEnabled: Boolean = false,
        val isMotdCheckEnabled: Boolean = false,
        val isBeta: Boolean = BuildConfigWrap.BUILD_TYPE != BuildConfigWrap.BuildType.RELEASE,
    ) {

        enum class Page {
            WELCOME,
            BETA,
            PRIVACY,
            ;
        }
    }
}