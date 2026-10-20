package eu.darken.butler.workspace.ui.hosted

import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import eu.darken.butler.common.coroutine.DispatcherProvider
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.debug.logging.logTag
import eu.darken.butler.common.ui.ViewModel4
import eu.darken.butler.workspace.contracts.picker.DestinationHostedPicker
import eu.darken.butler.workspace.core.Workspace
import eu.darken.butler.workspace.core.WorkspaceAction
import eu.darken.butler.workspace.core.WorkspaceRemote
import eu.darken.butler.workspace.core.WorkspaceStacks
import eu.darken.butler.workspace.core.hosted.HostedPickerCoordinator
import eu.darken.butler.workspace.core.hosted.HostedPickerCoordinator.Phase
import eu.darken.butler.workspace.ui.WorkspacePageHostEntry
import eu.darken.butler.workspace.ui.floatingbar.WorkspaceBarCollapseStates
import eu.darken.butler.workspace.ui.scroll.WorkspaceScrollPositions
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.flow.take

@HiltViewModel(assistedFactory = HostedPickerViewModel.Factory::class)
class HostedPickerViewModel @AssistedInject constructor(
    @Assisted private val token: String,
    dispatcherProvider: DispatcherProvider,
    private val coordinator: HostedPickerCoordinator,
    private val workspaceRemote: WorkspaceRemote,
    val pageHosts: Map<Workspace.Type, @JvmSuppressWildcards WorkspacePageHostEntry>,
    val scrollPositions: WorkspaceScrollPositions,
    val barCollapseStates: WorkspaceBarCollapseStates,
) : ViewModel4(dispatcherProvider, logTag("Workspace", "HostedPicker", "VM")) {

    sealed interface State {
        data object Loading : State
        data class Showing(val leaf: Workspace.Info) : State
        data object Gone : State
    }

    val state: SharedFlow<State> = combine(
        coordinator.attach(token),
        workspaceRemote.state,
    ) { host, remote ->
        when {
            host == null || host.phase == Phase.FINISHED -> State.Gone
            host.phase != Phase.OPEN -> State.Loading
            // Open per the coordinator but possibly not in this snapshot yet
            else -> WorkspaceStacks(remote.infos).hostedLeafOf(host.pickerId)?.let { State.Showing(it) }
                ?: State.Loading
        }
    }.shareIn(vmScope, SharingStarted.Eagerly, replay = 1)

    init {
        // Dismisses this entry by key: a late dismissal must never pop what navigation put on top
        state
            .filter { it is State.Gone }
            .take(1)
            .onEach { navDismiss(DestinationHostedPicker(token)) }
            .launchInViewModel()
    }

    /** Back reached the destination itself: the picker goes first, the entry follows once it is gone. */
    fun close() {
        log(tag) { "close()" }
        coordinator.close(token)
    }

    /** The error placeholder of [id]: a picker the hosted one opened goes alone, the hosted one ends the request. */
    fun closeWorkspace(id: Workspace.Id) = launch {
        log(tag) { "closeWorkspace($id)" }
        val stacks = WorkspaceStacks(workspaceRemote.peekInfos())
        if (stacks.hostedRootOf(id)?.id == id) {
            coordinator.close(token)
        } else {
            workspaceRemote.execute(WorkspaceAction.Close(id))
        }
    }

    fun showError(error: Throwable) {
        errorEvents.tryEmit(error)
    }

    override fun onCleared() {
        // Removed without the picker ending first (unrelated navigation): nothing can show it anymore
        coordinator.close(token)
        super.onCleared()
    }

    @AssistedFactory
    interface Factory {
        fun create(token: String): HostedPickerViewModel
    }
}
