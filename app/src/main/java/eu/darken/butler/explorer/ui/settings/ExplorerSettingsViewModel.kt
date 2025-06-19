package eu.darken.butler.explorer.ui.settings

import dagger.hilt.android.lifecycle.HiltViewModel
import eu.darken.butler.common.coroutine.DispatcherProvider
import eu.darken.butler.common.debug.logging.logTag
import eu.darken.butler.common.navigation.NavigationController
import eu.darken.butler.common.ui.ViewModel4
import eu.darken.butler.explorer.core.ExplorerSettings
import eu.darken.butler.explorer.core.SortSettings
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import javax.inject.Inject

@HiltViewModel
class ExplorerSettingsViewModel
@Inject
constructor(
    dispatcherProvider: DispatcherProvider,
    navCtrl: NavigationController,
    private val explorerSettings: ExplorerSettings,
) : ViewModel4(dispatcherProvider, logTag("Explorer", "Settings","Screen","VM"), navCtrl) {

    val state = combine(
        explorerSettings.sortSettings.flow,
        flowOf(Unit),
    ) { sortSettings, _ ->
        State(
            sortSettings = sortSettings,
        )
    }.asStateFlow()


    data class State(
        val sortSettings: SortSettings,
    )
}
