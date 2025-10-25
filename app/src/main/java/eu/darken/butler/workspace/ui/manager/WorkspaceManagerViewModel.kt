package eu.darken.butler.workspace.ui.manager

import dagger.hilt.android.lifecycle.HiltViewModel
import eu.darken.butler.common.ca.CaString
import eu.darken.butler.common.coroutine.DispatcherProvider
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.debug.logging.logTag
import eu.darken.butler.common.navigation.NavigationController
import eu.darken.butler.common.ui.ViewModel4
import eu.darken.butler.workspace.core.Workspace
import eu.darken.butler.workspace.core.WorkspaceAction
import eu.darken.butler.workspace.core.WorkspaceRepo
import eu.darken.butler.workspace.core.WorkspaceSettings
import eu.darken.butler.workspace.ui.WorkspacePageManager
import eu.darken.butler.workspace.ui.manager.preview.WorkspacePreviewManager
import kotlinx.coroutines.flow.combine
import javax.inject.Inject

@HiltViewModel
class WorkspaceManagerViewModel @Inject constructor(
    dispatchers: DispatcherProvider,
    navCtrl: NavigationController,
    private val workspaceRepo: WorkspaceRepo,
    private val workspaceSettings: WorkspaceSettings,
    private val workspacePageManager: WorkspacePageManager,
    private val workspacePreviewManager: WorkspacePreviewManager,
) : ViewModel4(dispatchers, logTag("Workspace", "Manager", "VM"), navCtrl) {

    val state = combine(
        workspaceRepo.state,
        workspaceSettings.showBadgeExplanation.flow,
        workspaceSettings.livePreview.flow,
    ) { repoState, showBadge, livePreview ->
        State(
            workspaces = repoState.infos.map { info ->
                WorkspaceItem(
                    id = info.id,
                    type = info.type,
                    title = info.title,
                    subtitle = info.subtitle,
                )
            },
            useLivePreview = livePreview,
            showBadgeExplanation = showBadge,
            operationsCount = repoState.operationCount,
            attentionCount = repoState.attentionCount,
        )
    }.asStateFlow()

    fun closeWorkspace(id: Workspace.Id) = launch {
        workspaceRepo.execute(WorkspaceAction.Close(id))
    }

    fun reorderWorkspaces(workspaceIds: List<Workspace.Id>) = launch {
        workspaceRepo.execute(WorkspaceAction.Reorder(workspaceIds))
    }

    fun selectWorkspace(id: Workspace.Id) = launch {
        log(tag) { "selectWorkspace($id)" }
        // Emit selection event to notify the parent screen
        workspacePageManager.selectWorkspaceFromManager(id)
        navigateBack()
    }

    fun createWorkspace(type: Workspace.Type) = launch {
        log(tag) { "createWorkspace($type)" }
        workspaceRepo.execute(WorkspaceAction.Create(type))
    }

    fun navigateBack() {
        log(tag) { "navigateBack()" }
        navUp()
    }

    fun dismissBadgeExplanation() = launch {
        workspaceSettings.showBadgeExplanation.update { false }
    }

    fun closeAllWorkspaces() = launch {
        log(tag) { "closeAllWorkspaces()" }
        workspaceRepo.execute(WorkspaceAction.CloseAll)
    }

    fun onScreenAppeared() = launch {
        log(tag) { "onScreenAppeared() - invalidating focused workspace preview" }
        workspacePreviewManager.invalidateFocusedWorkspacePreview()
    }

    data class State(
        val workspaces: List<WorkspaceItem> = emptyList(),
        val showBadgeExplanation: Boolean = true,
        val useLivePreview: Boolean = true,
        val operationsCount: Int = 0,
        val attentionCount: Int = 0,
    ) {
        val workspaceCount: Int = workspaces.size
    }

    data class WorkspaceItem(
        val id: Workspace.Id,
        val type: Workspace.Type,
        val title: CaString,
        val subtitle: CaString?,
    )
}
