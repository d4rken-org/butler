package eu.darken.butler.explorer.ui.settings

import dagger.hilt.android.lifecycle.HiltViewModel
import eu.darken.butler.common.coroutine.DispatcherProvider
import eu.darken.butler.common.datastore.value
import eu.darken.butler.common.debug.logging.logTag
import eu.darken.butler.common.navigation.NavigationController
import eu.darken.butler.common.ui.ViewModel4
import eu.darken.butler.explorer.core.ExplorerSettings
import eu.darken.butler.explorer.core.SortSettings
import kotlinx.coroutines.flow.combine
import javax.inject.Inject

@HiltViewModel
class ExplorerSettingsViewModel
@Inject
constructor(
    dispatcherProvider: DispatcherProvider,
    navigationController: NavigationController,
    private val explorerSettings: ExplorerSettings,
) : ViewModel4(dispatcherProvider, logTag("Explorer", "Settings","Screen","VM"), navigationController) {

    val state = combine(
        explorerSettings.sortSettings.flow,
        explorerSettings.useRegexPatterns.flow,
        explorerSettings.useBackButtonForNavigation.flow,
    ) { sortSettings, useRegexPatterns, useBackButtonForNavigation ->
        State(
            sortSettings = sortSettings,
            useRegexPatterns = useRegexPatterns,
            useBackButtonForNavigation = useBackButtonForNavigation,
        )
    }.asStateFlow()

    fun toggleRegexPatterns(enabled: Boolean) = launch {
        explorerSettings.useRegexPatterns.value(enabled)
    }

    fun toggleBackButtonNavigation(enabled: Boolean) = launch {
        explorerSettings.useBackButtonForNavigation.value(enabled)
    }

    data class State(
        val sortSettings: SortSettings,
        val useRegexPatterns: Boolean,
        val useBackButtonForNavigation: Boolean,
    )
}