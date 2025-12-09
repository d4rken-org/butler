package eu.darken.butler.main.ui.onboarding

import dagger.hilt.android.lifecycle.HiltViewModel
import eu.darken.butler.common.BuildConfigWrap
import eu.darken.butler.common.ButlerLinks
import eu.darken.butler.common.WebpageTool
import eu.darken.butler.common.coroutine.DispatcherProvider
import eu.darken.butler.common.datastore.value
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.debug.logging.logTag
import eu.darken.butler.common.navigation.Nav
import eu.darken.butler.common.navigation.NavigationController
import eu.darken.butler.common.ui.ViewModel4
import eu.darken.butler.main.core.GeneralSettings
import eu.darken.butler.main.core.motd.MotdSettings
import eu.darken.butler.workspace.ui.workspaces.workspaces
import kotlinx.coroutines.flow.combine
import javax.inject.Inject

@HiltViewModel
class OnboardingViewModel @Inject constructor(
    dispatchers: DispatcherProvider,
    navCtrl: NavigationController,
    private val generalSettings: GeneralSettings,
    private val motdSettings: MotdSettings,
    private val webpageTool: WebpageTool,
) : ViewModel4(dispatchers, logTag("Onboarding","Screen","VM"), navCtrl) {

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
        navTo(
            Nav.Main.workspaces(),
            popUpTo = Nav.Main.workspaces(),
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

    fun reportIssue() = launch {
        log(tag) { "reportIssue()" }
        webpageTool.open(ButlerLinks.ISSUES)
    }

    data class State(
        val startPage: Page = Page.WELCOME,
        val isUpdateCheckEnabled: Boolean = false,
        val isMotdCheckEnabled: Boolean = false,
        val isBeta: Boolean = BuildConfigWrap.BUILD_TYPE != BuildConfigWrap.BuildType.RELEASE,
    ) {

        enum class Page {
            WELCOME,
            WORKSPACES,
            BETA,
            PRIVACY,
            ;
        }
    }
}
