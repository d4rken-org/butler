package eu.darken.butler.searcher.ui

import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import eu.darken.butler.common.coroutine.DispatcherProvider
import eu.darken.butler.common.debug.logging.logTag
import eu.darken.butler.common.navigation.NavigationController
import eu.darken.butler.common.ui.ViewModel4
import eu.darken.butler.workspace.core.Workspace
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf

@HiltViewModel(assistedFactory = SearcherWorkspaceViewModel.Factory::class)
class SearcherWorkspaceViewModel @AssistedInject constructor(
    @Assisted private val id: Workspace.Id,
    dispatchers: DispatcherProvider,
    navCtrl: NavigationController,
) : ViewModel4(dispatchers, logTag("Workspace", "Searcher", id.shortTag, "Page"), navCtrl) {

    val state = combine(
        flowOf(Unit),
        flowOf(Unit),
    ) { _, _ ->
        State(
            id = id,
        )
    }.asStateFlow()

    data class State(
        val id: Workspace.Id,
    )

    @AssistedFactory
    interface Factory {
        fun create(id: Workspace.Id): SearcherWorkspaceViewModel
    }
}
