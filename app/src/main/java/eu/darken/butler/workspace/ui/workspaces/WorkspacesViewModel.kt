package eu.darken.butler.workspace.ui.workspaces

import androidx.lifecycle.SavedStateHandle
import dagger.hilt.android.lifecycle.HiltViewModel
import eu.darken.butler.common.WebpageTool
import eu.darken.butler.common.coroutine.DispatcherProvider
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.debug.logging.logTag
import eu.darken.butler.common.flow.combine
import eu.darken.butler.common.navigation.NavigationController
import eu.darken.butler.common.ui.ViewModel4
import eu.darken.butler.main.core.motd.MotdRepo
import eu.darken.butler.main.core.motd.MotdState
import eu.darken.butler.upgrade.UpgradeRepo
import eu.darken.butler.workspace.core.Workspace
import eu.darken.butler.workspace.core.WorkspaceAction
import eu.darken.butler.workspace.core.WorkspaceRemote
import eu.darken.butler.workspace.core.WorkspaceRepo
import eu.darken.butler.workspace.core.WorkspaceSettings
import eu.darken.butler.workspace.ui.WorkspacePageManager
import eu.darken.butler.workspace.ui.WorkspacePanelMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import javax.inject.Inject
import kotlin.uuid.Uuid


@HiltViewModel
class WorkspacesViewModel @Inject constructor(
    dispatchers: DispatcherProvider,
    private val navCtrl: NavigationController,
    upgradeRepo: UpgradeRepo,
    private val workspaceRepo: WorkspaceRepo,
    workspaceSettings: WorkspaceSettings,
    private val savedStateHandle: SavedStateHandle,
    private val workspacePageManager: WorkspacePageManager,
    private val motdRepo: MotdRepo,
    private val webpageTool: WebpageTool,
) : ViewModel4(dispatchers, logTag("Workspace", "Screen", "VM"), navCtrl) {

    private val hiddenMotdIds = MutableStateFlow<Set<Uuid>>(emptySet())

    init {
        launch {
            val currentWorkspaces = workspaceRepo.state.first()
            if (currentWorkspaces.infos.isEmpty()) {
                log(tag) { "No workspaces found, auto-creating workspace for testing" }
                // FIXME: AUTO-CREATE WORKSPACE FOR TESTING - REMOVE BEFORE MERGE, DO NOT COMMIT
                workspaceRepo.execute(WorkspaceAction.Create(type = Workspace.Type.SEARCHER))
            }
        }

        // Initialize the WorkspaceUIManager with saved state
        workspacePageManager.initializeFromSavedState(savedStateHandle)

        // Persist the entire state object when it changes
        workspacePageManager.state
            .onEach { state ->
                savedStateHandle["workspaceUIState"] = state
            }
            .launchInViewModel()
    }

    val state = combine(
        workspaceRepo.state,
        upgradeRepo.upgradeInfo,
        workspaceSettings.swipeGesturesEnabled.flow,
        workspaceSettings.onDemandWorkspaceCreation.flow,
        workspacePageManager.state,
        kotlinx.coroutines.flow.combine(motdRepo.motd, hiddenMotdIds) { motd, hiddenIds ->
            motd?.takeIf { it.id !in hiddenIds }
        },
    ) { repoState, upgradeInfo, swipeGesturesEnabled, onDemandWorkspaceCreation, uiState, visibleMotd ->
        State(
            state = repoState,
            focusedWorkspace = uiState.focusedWorkspaceId,
            selectedWorkspaces = uiState.selectedWorkspaces,
            isUpgraded = upgradeInfo.isUpgraded,
            swipeGesturesEnabled = swipeGesturesEnabled,
            onDemandWorkspaceCreation = swipeGesturesEnabled && onDemandWorkspaceCreation,
            motd = visibleMotd,
        )
    }.asStateFlow()

    fun executeScreenAction(action: WorkspaceScreenAction) = launch {
        log(tag) { "executeScreenAction($action)" }

        when (action) {
            is WorkspaceScreenAction.Select -> {
                workspacePageManager.setFocusedWorkspace(action.id)
                workspacePageManager.setSelectedWorkspaces(mapOf(0 to action.id))
            }
            is WorkspaceScreenAction.SelectMultiple -> {
                workspacePageManager.setSelectedWorkspaces(action.positions)
            }
            is WorkspaceScreenAction.Focus -> {
                workspacePageManager.setFocusedWorkspace(action.id)
            }
            is WorkspaceScreenAction.ToggleSelection -> {
                workspacePageManager.toggleWorkspaceSelection(action.id, action.position)
            }
            is WorkspaceScreenAction.SetPaneCount -> {
                log(tag) { "Setting pane count to ${action.count}" }
                workspacePageManager.setPaneCount(action.count)
            }
            is WorkspaceScreenAction.CreateOnDemand -> {
                log(tag) { "Creating workspace on-demand" }
                val result =
                    workspaceRepo.execute(WorkspaceAction.Create(type = Workspace.Type.TEMPLATES)) as WorkspaceAction.Create.Result
                log(tag) { "On-demand workspace created: ${result.newId}, focusing it" }
                workspacePageManager.setFocusedWorkspace(result.newId)
                workspacePageManager.setSelectedWorkspaces(mapOf(0 to result.newId))
            }
        }
    }

    fun hideMotd(id: Uuid) = launch {
        log(tag) { "hideMotd($id)" }
        hiddenMotdIds.update { it + id }
    }

    fun dismissMotd(id: Uuid) = launch {
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
        val swipeGesturesEnabled: Boolean = true,
        val onDemandWorkspaceCreation: Boolean = true,
        val motd: MotdState? = null,
    ) {
        val displayMode: WorkspacePanelMode
            get() = state.panelMode

        val focused: Workspace.Id?
            get() = focusedWorkspace

        val current: Workspace.Info?
            get() = tabWorkspaces.firstOrNull { it.id == focused }

        val selected: Map<Int, WorkspacePaneInfo>
            get() = selectedWorkspaces
                .mapNotNull { (position, id) ->
                    tabWorkspaces.find { it.id == id }?.let { position to it.asPaneInfo() }
                }
                .toMap()

        val all: List<Workspace.Info>
            get() = state.infos

        // Filter workspaces by caller relationship
        val tabWorkspaces: List<Workspace.Info>
            get() = state.infos.filter { !it.isSubWorkspace }

        val modalWorkspace: Workspace.Info?
            get() = state.infos.firstOrNull { it.isSubWorkspace }
    }
}
