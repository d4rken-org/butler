package eu.darken.butler.workspace.ui.workspaces

import androidx.lifecycle.SavedStateHandle
import dagger.hilt.android.lifecycle.HiltViewModel
import eu.darken.butler.common.WebpageTool
import eu.darken.butler.common.coroutine.DispatcherProvider
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.debug.logging.logTag
import eu.darken.butler.common.flow.combine
import eu.darken.butler.common.navigation.Nav
import eu.darken.butler.common.navigation.NavigationController
import eu.darken.butler.common.navigation.upgrade
import eu.darken.butler.common.ui.ViewModel4
import eu.darken.butler.main.core.motd.MotdRepo
import eu.darken.butler.main.core.motd.MotdState
import eu.darken.butler.upgrade.UpgradeRepo
import eu.darken.butler.workspace.core.Workspace
import eu.darken.butler.workspace.core.WorkspaceAction
import eu.darken.butler.workspace.core.WorkspaceRemote
import eu.darken.butler.workspace.core.WorkspaceRepo
import eu.darken.butler.workspace.core.WorkspaceSettings
import eu.darken.butler.workspace.ui.WorkspacePanelMode
import eu.darken.butler.workspace.ui.WorkspaceUIManager
import eu.darken.butler.workspace.ui.manager.workspaceManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import java.util.UUID
import javax.inject.Inject


@HiltViewModel
class WorkspacesViewModel @Inject constructor(
    dispatchers: DispatcherProvider,
    private val navCtrl: NavigationController,
    upgradeRepo: UpgradeRepo,
    private val workspaceRepo: WorkspaceRepo,
    workspaceSettings: WorkspaceSettings,
    private val savedStateHandle: SavedStateHandle,
    private val workspaceUIManager: WorkspaceUIManager,
    private val motdRepo: MotdRepo,
    private val webpageTool: WebpageTool,
) : ViewModel4(dispatchers, logTag("Workspace", "Screen", "VM"), navCtrl) {

    private val hiddenMotdIds = MutableStateFlow<Set<UUID>>(emptySet())

    init {
        // Initialize the WorkspaceUIManager with saved state
        workspaceUIManager.initializeFromSavedState(savedStateHandle)

        // Persist state changes back to SavedStateHandle
        workspaceUIManager.focusedWorkspaceId
            .onEach {
                savedStateHandle["focusedWorkspaceId"] = it?.id?.toString()
            }
            .launchInViewModel()

        workspaceUIManager.selectedWorkspaces
            .onEach {
                savedStateHandle["selectedWorkspaces"] = it.mapValues { (_, wsId) -> wsId.id.toString() }
            }
            .launchInViewModel()

        workspaceUIManager.currentPaneCount
            .onEach {
                savedStateHandle["currentPaneCount"] = it
            }
            .launchInViewModel()
    }

    val state = combine(
        workspaceRepo.state,
        upgradeRepo.upgradeInfo,
        workspaceSettings.isButtonActionsFlipped.flow,
        workspaceSettings.swipeGesturesEnabled.flow,
        workspaceUIManager.focusedWorkspaceId,
        workspaceUIManager.selectedWorkspaces,
        kotlinx.coroutines.flow.combine(motdRepo.motd, hiddenMotdIds) { motd, hiddenIds ->
            motd?.takeIf { it.id !in hiddenIds }
        },
    ) { repoState, upgradeInfo, isButtonFlipped, swipeGesturesEnabled, focusedId, selectedIds, visibleMotd ->
        State(
            state = repoState,
            focusedWorkspace = focusedId,
            selectedWorkspaces = selectedIds,
            isUpgraded = upgradeInfo.isUpgraded,
            isButtonActionsFlipped = isButtonFlipped,
            swipeGesturesEnabled = swipeGesturesEnabled,
            motd = visibleMotd,
        )
    }.asStateFlow()


    fun executeAction(
        action: WorkspaceAction,
    ) = launch {
        log(tag) { "modifyTab($action)" }

        when (action) {
            is WorkspaceAction.Create -> {
                log(tag) { "Create action: type=${action.type}, replace=${action.replace}" }
                val result = workspaceRepo.execute(action) as WorkspaceAction.Create.Result
                log(tag) { "Workspace created with ID: ${result.newId}, pane assignment will be handled reactively" }
            }
            is WorkspaceAction.Close -> {
                log(tag) { "Close action: id=${action.id}" }
                workspaceRepo.execute(action)
                log(tag) { "Workspace closed, selection handling will be done reactively" }
            }
            is WorkspaceAction.Reorder -> {
                log(tag) { "Reorder action: workspaceIds=${action.workspaceIds}" }
                workspaceRepo.execute(action)
                log(tag) { "Workspaces reordered" }
            }
            else -> workspaceRepo.execute(action)
        }
    }

    fun executeScreenAction(action: WorkspaceScreenAction) = launch {
        log(tag) { "executeScreenAction($action)" }

        when (action) {
            is WorkspaceScreenAction.Select -> {
                workspaceUIManager.setFocusedWorkspace(action.id)
                workspaceUIManager.setSelectedWorkspaces(mapOf(0 to action.id))
            }
            is WorkspaceScreenAction.SelectMultiple -> {
                workspaceUIManager.setSelectedWorkspaces(action.positions)
            }
            is WorkspaceScreenAction.Focus -> {
                workspaceUIManager.setFocusedWorkspace(action.id)
            }
            is WorkspaceScreenAction.ToggleSelection -> {
                workspaceUIManager.toggleWorkspaceSelection(action.id, action.position)
            }
            is WorkspaceScreenAction.SetPaneCount -> {
                log(tag) { "Setting pane count to ${action.count}" }
                workspaceUIManager.setPaneCount(action.count)
            }
        }
    }

    fun openWorkspaceManager() = launch {
        log(tag) { "openWorkspaceManager()" }
        navCtrl.goTo(Nav.workspaceManager())
    }

    fun upgradeButler() = launch {
        log(tag) { "upgradeButler()" }
        navCtrl.goTo(Nav.Main.upgrade())
    }

    fun hideMotd(id: UUID) = launch {
        log(tag) { "hideMotd($id)" }
        hiddenMotdIds.update { it + id }
    }

    fun dismissMotd(id: UUID) = launch {
        log(tag) { "dismissMotd($id)" }
        motdRepo.dismiss(id)
    }

    fun openMotdLink(url: String) = launch {
        log(tag) { "openMotdLink($url)" }
        webpageTool.open(url)
    }

    data class State(
        private val state: WorkspaceRemote.State,
        val focusedWorkspace: Workspace.Id?,
        val selectedWorkspaces: Map<Int, Workspace.Id>,
        val isUpgraded: Boolean,
        val isButtonActionsFlipped: Boolean = false,
        val swipeGesturesEnabled: Boolean = true,
        val motd: MotdState? = null,
    ) {
        val displayMode: WorkspacePanelMode
            get() = state.panelMode

        val focused: Workspace.Id?
            get() = focusedWorkspace

        val current: Workspace.Info?
            get() = state.infos.firstOrNull { it.id == focused }

        val selected: Map<Int, Workspace.Info>
            get() = selectedWorkspaces.mapNotNull { (position, id) ->
                state.infos.find { it.id == id }?.let { position to it }
            }.toMap()

        val all: List<Workspace.Info>
            get() = state.infos
    }
}
