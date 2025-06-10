package eu.darken.butler.main.ui.settings.general

import dagger.hilt.android.lifecycle.HiltViewModel
import eu.darken.butler.common.coroutine.DispatcherProvider
import eu.darken.butler.common.debug.logging.logTag
import eu.darken.butler.common.navigation.NavigationController
import eu.darken.butler.common.theming.ThemeState
import eu.darken.butler.common.theming.themeState
import eu.darken.butler.common.uix.ViewModel4
import eu.darken.butler.main.core.GeneralSettings
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import javax.inject.Inject

@HiltViewModel
class GeneralSettingsViewModel @Inject constructor(
    dispatcherProvider: DispatcherProvider,
    navCtrl: NavigationController,
    private val generalSettings: GeneralSettings,
) : ViewModel4(dispatcherProvider, logTag("Settings", "General", "ViewModel"), navCtrl) {

    val state = combine(
        generalSettings.themeState,
        flowOf(Unit)
    ) { themeState, _ ->
        State(
            themeState = themeState
        )
    }.asStateFlow()


    data class State(
        val themeState: ThemeState = ThemeState()
    )
}