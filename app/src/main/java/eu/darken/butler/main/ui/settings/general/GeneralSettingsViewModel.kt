package eu.darken.butler.main.ui.settings.general

import android.annotation.SuppressLint
import dagger.hilt.android.lifecycle.HiltViewModel
import eu.darken.butler.common.coroutine.DispatcherProvider
import eu.darken.butler.common.datastore.value
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.debug.logging.logTag
import eu.darken.butler.common.hasApiLevel
import eu.darken.butler.common.locale.LocaleManager
import eu.darken.butler.common.navigation.NavigationController
import eu.darken.butler.common.theming.ThemeState
import eu.darken.butler.common.theming.themeState
import eu.darken.butler.common.ui.ViewModel4
import eu.darken.butler.main.core.GeneralSettings
import javax.inject.Inject
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf

@HiltViewModel
class GeneralSettingsViewModel
@Inject
constructor(
    dispatcherProvider: DispatcherProvider,
    navCtrl: NavigationController,
    private val generalSettings: GeneralSettings,
    private val localeManager: LocaleManager,
) : ViewModel4(dispatcherProvider, logTag("Settings", "General", "ViewModel"), navCtrl) {

    val state =
        combine(
            generalSettings.themeState,
            flowOf(hasApiLevel(33)),
            generalSettings.usePreviews.flow,
        ) { themeState, languageSwitcher, usePreviews ->
            State(
                themeState = themeState,
                filePreviews = usePreviews,
                showLanguageSwitcher = languageSwitcher,
            )
        }
            .asStateFlow()

    fun showLanguagePicker() = launch {
        log(tag) { "showLanguagPicker()" }
        if (hasApiLevel(33)) {
            @SuppressLint("NewApi") localeManager.showLanguagePicker()
        } else {
            throw IllegalStateException("This should not be clickable below API 33...")
        }
    }

    fun updateFilePreviews(enabled: Boolean) = launch {
        log(tag) { "updateFilePreviews($enabled)" }
        generalSettings.usePreviews.value(enabled)
    }

    data class State(
        val themeState: ThemeState = ThemeState(),
        val filePreviews: Boolean,
        val showLanguageSwitcher: Boolean = false,
    )
}
