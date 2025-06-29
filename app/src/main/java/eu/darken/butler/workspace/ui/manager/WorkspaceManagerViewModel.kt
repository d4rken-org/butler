package eu.darken.butler.workspace.ui.manager

import dagger.hilt.android.lifecycle.HiltViewModel
import eu.darken.butler.common.ca.CaString
import eu.darken.butler.common.coroutine.DispatcherProvider
import eu.darken.butler.common.datastore.value
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.debug.logging.logTag
import eu.darken.butler.common.navigation.Nav
import eu.darken.butler.common.navigation.NavigationController
import eu.darken.butler.common.navigation.settings
import eu.darken.butler.common.ui.ViewModel4
import eu.darken.butler.workspace.core.Workspace
import eu.darken.butler.workspace.core.WorkspaceAction
import eu.darken.butler.workspace.core.WorkspaceRepo
import eu.darken.butler.workspace.core.WorkspaceSettings
import kotlinx.coroutines.flow.combine
import javax.inject.Inject

@HiltViewModel
class WorkspaceManagerViewModel @Inject constructor(
    dispatchers: DispatcherProvider,
    navCtrl: NavigationController,
    private val workspaceRepo: WorkspaceRepo,
    private val workspaceSettings: WorkspaceSettings,
) : ViewModel4(dispatchers, logTag("Workspace", "Manager", "VM"), navCtrl) {

    val state = combine(
        workspaceRepo.state,
        workspaceSettings.isButtonActionsFlipped.flow,
        workspaceSettings.showBadgeExplanation.flow,
        workspaceSettings.showButtonBehaviorExplanation.flow,
    ) { repoState, isFlipped, showBadge, showBehavior ->
        State(
            workspaces = repoState.workspaceInfos.map { info ->
                WorkspaceItem(
                    id = info.id,
                    type = info.type,
                    title = info.title,
                    subtitle = getSubtitleForWorkspace(info.type),
                )
            },
            isButtonFlipped = isFlipped,
            showBadgeExplanation = showBadge,
            showButtonBehaviorExplanation = showBehavior,
            operationsCount = repoState.operationCount,
            attentionCount = repoState.attentionCount,
        )
    }.asStateFlow()

    private fun getSubtitleForWorkspace(type: Workspace.Type): String {
        return when (type) {
            Workspace.Type.TEMPLATES -> "Workspace templates"
            Workspace.Type.EXPLORER -> "File explorer"
            Workspace.Type.SEARCHER -> "File search"
            Workspace.Type.EDITOR -> "Text editor"
        }
    }

    fun closeWorkspace(id: Workspace.Id) = launch {
        workspaceRepo.execute(WorkspaceAction.Close(id))
    }

    fun reorderWorkspaces(workspaceIds: List<Workspace.Id>) = launch {
        workspaceRepo.execute(WorkspaceAction.Reorder(workspaceIds))
    }

    fun selectWorkspace(id: Workspace.Id) = launch {
        log(tag) { "selectWorkspace($id)" }
        workspaceRepo.execute(WorkspaceAction.Select(id))
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

    fun navigateToSettings() {
        log(tag) { "navigateToSettings()" }
        navTo(Nav.Main.settings())
    }

    fun toggleButtonFlipped() = launch {
        val current = workspaceSettings.isButtonActionsFlipped.value()
        workspaceSettings.isButtonActionsFlipped.update { !current }
    }

    fun dismissBadgeExplanation() = launch {
        workspaceSettings.showBadgeExplanation.update { false }
    }

    fun dismissButtonBehaviorExplanation() = launch {
        workspaceSettings.showButtonBehaviorExplanation.update { false }
    }

    fun closeAllWorkspaces() = launch {
        log(tag) { "closeAllWorkspaces()" }
        workspaceRepo.execute(WorkspaceAction.CloseAll)
    }

    data class State(
        val workspaces: List<WorkspaceItem> = emptyList(),
        val isButtonFlipped: Boolean = false,
        val showBadgeExplanation: Boolean = true,
        val showButtonBehaviorExplanation: Boolean = true,
        val operationsCount: Int = 0,
        val attentionCount: Int = 0,
    ) {
        val workspaceCount: Int = workspaces.size
    }

    data class WorkspaceItem(
        val id: Workspace.Id,
        val type: Workspace.Type,
        val title: CaString,
        val subtitle: String,
    )
}
