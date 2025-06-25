package eu.darken.butler.explorer.ui

import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import eu.darken.butler.common.coroutine.DispatcherProvider
import eu.darken.butler.common.debug.logging.logTag
import eu.darken.butler.common.ui.ViewModel3
import eu.darken.butler.workspace.core.Workspace
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf

@HiltViewModel(assistedFactory = ExplorerWorkspaceViewModel.Factory::class)
class ExplorerWorkspaceViewModel @AssistedInject constructor(
    @Assisted private val id: Workspace.Id,
    dispatchers: DispatcherProvider,
) : ViewModel3(dispatchers, logTag("Workspace", "Explorer", id.shortTag, "Page")) {

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
        fun create(id: Workspace.Id): ExplorerWorkspaceViewModel
    }
}