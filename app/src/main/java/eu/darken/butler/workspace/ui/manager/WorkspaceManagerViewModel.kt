package eu.darken.butler.workspace.ui.manager

import dagger.hilt.android.lifecycle.HiltViewModel
import eu.darken.butler.common.ca.CaString
import eu.darken.butler.common.coroutine.DispatcherProvider
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.debug.logging.logTag
import eu.darken.butler.common.navigation.NavigationController
import eu.darken.butler.common.ui.ViewModel4
import eu.darken.butler.workspace.core.Workspace
import eu.darken.butler.workspace.core.WorkspaceRepo
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject

@HiltViewModel
class WorkspaceManagerViewModel @Inject constructor(
    dispatchers: DispatcherProvider,
    navCtrl: NavigationController,
    private val workspaceRepo: WorkspaceRepo,
) : ViewModel4(dispatchers, logTag("Workspace", "Manager", "VM"), navCtrl) {

    val state = workspaceRepo.state
        .map { repoState ->
            State(
                workspaces = repoState.workspaceInfos.map { info ->
                    WorkspaceItem(
                        id = info.id,
                        type = info.type,
                        title = info.title,
                        subtitle = getSubtitleForWorkspace(info.type),
                    )
                },
                workspaceCount = repoState.workspaceInfos.size
            )
        }
        .asStateFlow()

    private fun getSubtitleForWorkspace(type: Workspace.Type): String {
        return when (type) {
            Workspace.Type.TEMPLATES -> "Workspace templates"
            Workspace.Type.EXPLORER -> "File explorer"
            Workspace.Type.SEARCHER -> "File search"
            Workspace.Type.EDITOR -> "Text editor"
        }
    }

    fun closeWorkspace(id: Workspace.Id) = launch {
        workspaceRepo.delete(id)
    }

    fun reorderWorkspaces(workspaceIds: List<Workspace.Id>) = launch {
        workspaceRepo.reorder(workspaceIds)
    }

    fun selectWorkspace(id: Workspace.Id) = launch {
        log(tag) { "selectWorkspace($id)" }
        workspaceRepo.selectWorkspace(id)
        navigateBack()
    }

    fun createWorkspace(type: Workspace.Type) = launch {
        log(tag) { "createWorkspace($type)" }
        workspaceRepo.create(type)
    }

    fun navigateBack() {
        log(tag) { "navigateBack()" }
        navUp()
    }

    data class State(
        val workspaces: List<WorkspaceItem> = emptyList(),
        val workspaceCount: Int = 0,
    )

    data class WorkspaceItem(
        val id: Workspace.Id,
        val type: Workspace.Type,
        val title: CaString,
        val subtitle: String,
    )
}
