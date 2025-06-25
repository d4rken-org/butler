package eu.darken.butler.explorer.ui.settings

import dagger.hilt.android.lifecycle.HiltViewModel
import eu.darken.butler.common.coroutine.DispatcherProvider
import eu.darken.butler.common.debug.logging.logTag
import eu.darken.butler.common.ui.ViewModel3
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
    explorerSettings: ExplorerSettings,
) : ViewModel3(dispatcherProvider, logTag("Explorer", "Settings","Screen","VM")) {

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