package eu.darken.butler.workspace.ui

import dagger.hilt.android.lifecycle.HiltViewModel
import eu.darken.butler.common.coroutine.DispatcherProvider
import eu.darken.butler.common.debug.logging.logTag
import eu.darken.butler.common.navigation.NavigationController
import eu.darken.butler.common.ui.ViewModel4
import eu.darken.butler.workspace.core.Workspace
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import javax.inject.Inject

@HiltViewModel
class WorkspaceViewModel @Inject constructor(
    dispatchers: DispatcherProvider,
    navCtrl: NavigationController,
) : ViewModel4(dispatchers, logTag("Workspace", "ViewModel"), navCtrl) {

    val state = combine(
        flowOf(Unit),
        flowOf(Unit)
    ) {
        State(
            tabs = emptyList()
        )
    }.asStateFlow()

    data class State(
        val tabs: List<Workspace.Tab> = emptyList()
    )
}