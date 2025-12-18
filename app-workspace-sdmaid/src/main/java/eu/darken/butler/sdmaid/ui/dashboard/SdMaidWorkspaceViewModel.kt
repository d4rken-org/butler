package eu.darken.butler.sdmaid.ui.dashboard

import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import eu.darken.butler.common.coroutine.DispatcherProvider
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.debug.logging.logTag
import eu.darken.butler.common.pkgs.SDMaidTool
import eu.darken.butler.common.ui.ViewModel4
import eu.darken.butler.sdmaid.core.SdMaidWorkspace
import eu.darken.butler.sdmaid.core.arguments.SdMaidArguments
import eu.darken.butler.workspace.core.Workspace
import eu.darken.butler.workspace.core.WorkspaceProvider
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map

@HiltViewModel(assistedFactory = SdMaidWorkspaceViewModel.Factory::class)
class SdMaidWorkspaceViewModel @AssistedInject constructor(
    @Assisted private val id: Workspace.Id,
    dispatchers: DispatcherProvider,
    workspaceProvider: WorkspaceProvider,
    private val sdMaidTool: SDMaidTool,
) : ViewModel4(dispatchers, logTag("SDMaid", "Workspace", id.shortTag, "Page")) {

    private val workspaceSource: Flow<SdMaidWorkspace?> =
        workspaceProvider.retrieve(id)
            .map { workspace: Workspace<out Workspace.Arguments>? -> workspace as? SdMaidWorkspace }

    private suspend fun getWorkspace(): SdMaidWorkspace = workspaceSource.filterNotNull().first()

    data class State(
        val connectionState: SdMaidWorkspace.ConnectionState = SdMaidWorkspace.ConnectionState.Checking,
        val currentTool: SdMaidArguments.ToolType? = null,
    )

    val state: Flow<State> = workspaceSource.filterNotNull().flatMapLatest { workspace ->
        workspace.state.map { workspaceState ->
            State(
                connectionState = workspaceState.connectionState,
                currentTool = workspaceState.currentTool,
            )
        }
    }

    fun openInstallPage() = launch {
        log(tag) { "Opening install page for SD Maid SE" }
        sdMaidTool.openInstallPage()
    }

    fun launchSdMaid() = launch {
        log(tag) { "Launching SD Maid SE" }
        sdMaidTool.launch()
    }

    fun selectTool(tool: SdMaidArguments.ToolType?) = launch {
        log(tag) { "selectTool($tool)" }
        getWorkspace().selectTool(tool)
    }

    fun retry() = launch {
        log(tag) { "retry()" }
        getWorkspace().retry()
    }

    @AssistedFactory
    interface Factory {
        fun create(id: Workspace.Id): SdMaidWorkspaceViewModel
    }
}
