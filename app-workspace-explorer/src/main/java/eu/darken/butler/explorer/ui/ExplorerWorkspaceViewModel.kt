package eu.darken.butler.explorer.ui

import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import eu.darken.butler.common.coroutine.DispatcherProvider
import eu.darken.butler.common.debug.logging.logTag
import eu.darken.butler.common.ui.ViewModel3
import eu.darken.butler.explorer.core.ExplorerWorkspace
import eu.darken.butler.workspace.core.Workspace
import eu.darken.butler.workspace.core.WorkspaceProvider
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf

@HiltViewModel(assistedFactory = ExplorerWorkspaceViewModel.Factory::class)
class ExplorerWorkspaceViewModel @AssistedInject constructor(
    @Assisted private val id: Workspace.Id,
    private val workspaceProvider: WorkspaceProvider,
    dispatchers: DispatcherProvider,
) : ViewModel3(dispatchers, logTag("Workspace", "Explorer", id.shortTag, "Page")) {

    private val workspace = flowOf(id)
        .flatMapLatest { workspaceProvider.get(it) }

    val state = combine(
        workspace,
    ) { workspace ->
        State(
            id = id,
            workspace = workspace as? ExplorerWorkspace,
        )
    }.asStateFlow()

    data class State(
        val id: Workspace.Id,
        val workspace: ExplorerWorkspace? = null,
    )

    @AssistedFactory
    interface Factory {
        fun create(id: Workspace.Id): ExplorerWorkspaceViewModel
    }
}