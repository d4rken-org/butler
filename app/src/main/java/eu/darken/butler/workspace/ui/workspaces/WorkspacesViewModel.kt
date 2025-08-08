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
import eu.darken.butler.workspace.core.WorkspaceEvent
import eu.darken.butler.workspace.core.WorkspaceRemote
import eu.darken.butler.workspace.core.WorkspaceRepo
import eu.darken.butler.workspace.core.WorkspaceSettings
import eu.darken.butler.workspace.ui.manager.workspaceManager
import eu.darken.butler.workspace.ui.WorkspacePanelMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
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
    private val motdRepo: MotdRepo,
    private val webpageTool: WebpageTool,
) : ViewModel4(dispatchers, logTag("Workspace", "Screen", "VM"), navCtrl) {

    private val hiddenMotdIds = MutableStateFlow<Set<UUID>>(emptySet())

    private val focusedWorkspaceId = MutableStateFlow(
        savedStateHandle.get<String>("focusedWorkspaceId")
            ?.let { Workspace.Id(UUID.fromString(it)) }
    )

    private val selectedWorkspaces = MutableStateFlow(
        savedStateHandle.get<Map<Int, String>>("selectedWorkspaces")
            ?.mapValues { Workspace.Id(UUID.fromString(it.value)) }
            ?: emptyMap()
    )

    private val currentPaneCount = MutableStateFlow(
        savedStateHandle.get<Int>("currentPaneCount") ?: 1
    )

    init {
        // Handle workspace events for pane assignment
        workspaceRepo.events
            .onEach { event ->
                log(tag) { "Workspace event received: $event" }
                when (event) {
                    is WorkspaceEvent.Created -> {
                        handleWorkspaceCreated(event.workspaceId, event.replacedId)
                    }
                    is WorkspaceEvent.Closed -> {
                        handleWorkspaceClosed(event.workspaceId)
                    }
                    is WorkspaceEvent.Reordered -> {
                        log(tag) { "Workspaces reordered: ${event.workspaceIds}" }
                    }
                    WorkspaceEvent.AllClosed -> {
                        log(tag) { "All workspaces closed" }
                        focusedWorkspaceId.value = null
                        selectedWorkspaces.value = emptyMap()
                    }
                }
            }
            .launchInViewModel()

        // Clean up stale workspace IDs that no longer exist
        workspaceRepo.state
            .onEach { repoState ->
                val validWorkspaceIds = repoState.infos.map { it.id }.toSet()

                val currentFocusedId = focusedWorkspaceId.value
                val currentSelectedIds = selectedWorkspaces.value

                val cleanedFocusedId = currentFocusedId?.takeIf { it in validWorkspaceIds }
                val cleanedSelectedIds = currentSelectedIds.filterValues { it in validWorkspaceIds }

                // Update state if cleanup removed any IDs
                if (cleanedFocusedId != currentFocusedId) {
                    focusedWorkspaceId.value = cleanedFocusedId
                    savedStateHandle["focusedWorkspaceId"] = cleanedFocusedId?.id?.toString()
                }
                if (cleanedSelectedIds != currentSelectedIds) {
                    selectedWorkspaces.value = cleanedSelectedIds
                    savedStateHandle["selectedWorkspaces"] = cleanedSelectedIds.mapValues { (_, wsId) -> wsId.id.toString() }
                }
            }
            .launchInViewModel()
    }

    val state = combine(
        workspaceRepo.state,
        upgradeRepo.upgradeInfo,
        workspaceSettings.isButtonActionsFlipped.flow,
        workspaceSettings.swipeGesturesEnabled.flow,
        focusedWorkspaceId,
        selectedWorkspaces,
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

    private fun handleWorkspaceCreated(workspaceId: Workspace.Id, replacedId: Workspace.Id?) {
        log(tag) { "handleWorkspaceCreated: workspaceId=$workspaceId, replacedId=$replacedId" }

        if (replacedId != null) {
            // This is a replacement
            val currentSelections = selectedWorkspaces.value
            val replacedPaneIndex = currentSelections.entries.find { it.value == replacedId }?.key

            if (replacedPaneIndex != null) {
                log(tag) { "Replacing workspace $replacedId at pane $replacedPaneIndex with $workspaceId" }
                selectedWorkspaces.value = currentSelections + (replacedPaneIndex to workspaceId)
                savedStateHandle["selectedWorkspaces"] = selectedWorkspaces.value.mapValues { (_, wsId) -> wsId.id.toString() }

                // Transfer focus if the replaced workspace was focused
                if (focusedWorkspaceId.value == replacedId) {
                    log(tag) { "Transferring focus from $replacedId to $workspaceId" }
                    focusedWorkspaceId.value = workspaceId
                    savedStateHandle["focusedWorkspaceId"] = workspaceId.id.toString()
                }
            } else {
                log(tag) { "Replaced workspace $replacedId was not in any pane, treating as new workspace" }
                assignToEmptyPane(workspaceId)
            }
        } else {
            // New workspace, not a replacement
            if (!selectedWorkspaces.value.containsValue(workspaceId)) {
                log(tag) { "New workspace $workspaceId, assigning to empty pane" }
                assignToEmptyPane(workspaceId)

                // Auto-focus if no workspace is focused
                if (focusedWorkspaceId.value == null) {
                    log(tag) { "No focused workspace, setting focus to $workspaceId" }
                    focusedWorkspaceId.value = workspaceId
                    savedStateHandle["focusedWorkspaceId"] = workspaceId.id.toString()
                }
            } else {
                log(tag) { "Workspace $workspaceId already assigned to a pane" }
            }
        }
    }

    private suspend fun handleWorkspaceClosed(workspaceId: Workspace.Id) {
        log(tag) { "handleWorkspaceClosed: workspaceId=$workspaceId" }

        val wasSelected = selectedWorkspaces.value.values.contains(workspaceId)
        val wasFocused = focusedWorkspaceId.value == workspaceId

        if (wasSelected) {
            // Remove from selection
            val position = selectedWorkspaces.value.entries.find { it.value == workspaceId }?.key
            if (position != null) {
                selectedWorkspaces.value = selectedWorkspaces.value - position
                savedStateHandle["selectedWorkspaces"] = selectedWorkspaces.value.mapValues { (_, wsId) -> wsId.id.toString() }
            }

            // Select next workspace if this was focused
            if (wasFocused) {
                val workspaces = workspaceRepo.state.first().infos
                if (workspaces.isNotEmpty()) {
                    val newSelected = workspaces.firstOrNull()
                    newSelected?.let {
                        focusedWorkspaceId.value = it.id
                        savedStateHandle["focusedWorkspaceId"] = it.id.toString()
                        if (selectedWorkspaces.value.isEmpty()) {
                            selectedWorkspaces.value = mapOf(0 to it.id)
                            savedStateHandle["selectedWorkspaces"] = selectedWorkspaces.value.mapValues { (_, wsId) -> wsId.id.toString() }
                        }
                    }
                } else {
                    focusedWorkspaceId.value = null
                    savedStateHandle["focusedWorkspaceId"] = null
                }
            }
        }
    }

    private fun assignToEmptyPane(workspaceId: Workspace.Id) {
        val paneCount = currentPaneCount.value
        val currentSelections = selectedWorkspaces.value
        log(tag) { "assignToEmptyPane: Pane count=$paneCount, workspace=$workspaceId" }

        if (paneCount > 1) {
            // Find first empty pane
            val emptyPaneIndex = (0 until paneCount).firstOrNull { paneIndex ->
                !currentSelections.containsKey(paneIndex)
            }
            log(tag) { "assignToEmptyPane: Empty pane index=$emptyPaneIndex" }

            if (emptyPaneIndex != null) {
                // Assign to empty pane
                selectedWorkspaces.value = currentSelections + (emptyPaneIndex to workspaceId)
                log(tag) { "assignToEmptyPane: Assigned to empty pane $emptyPaneIndex" }
                // Persist the selection
                savedStateHandle["selectedWorkspaces"] = selectedWorkspaces.value.mapValues { (_, wsId) -> wsId.id.toString() }
            } else {
                // All panes full, don't select the new workspace
                log(tag) { "assignToEmptyPane: All panes full, workspace created but not selected" }
            }
        } else {
            // Single pane mode
            if (currentSelections.isEmpty()) {
                // No workspace selected, assign to pane 0
                selectedWorkspaces.value = mapOf(0 to workspaceId)
                log(tag) { "assignToEmptyPane: Single pane mode, assigned to pane 0" }
                savedStateHandle["selectedWorkspaces"] = selectedWorkspaces.value.mapValues { (_, wsId) -> wsId.id.toString() }
            } else {
                // Pane 0 already occupied, don't select the new workspace
                log(tag) { "assignToEmptyPane: Single pane mode, pane 0 occupied, workspace created but not selected" }
            }
        }
    }

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
            else -> workspaceRepo.execute(action)
        }
    }

    fun executeScreenAction(action: WorkspaceScreenAction) = launch {
        log(tag) { "executeScreenAction($action)" }

        when (action) {
            is WorkspaceScreenAction.Select -> {
                focusedWorkspaceId.value = action.id
                selectedWorkspaces.value = mapOf(0 to action.id)
            }
            is WorkspaceScreenAction.SelectMultiple -> {
                selectedWorkspaces.value = action.positions
                // Auto-focus first selected if current focus not in selection
                if (focusedWorkspaceId.value == null || !action.positions.values.contains(focusedWorkspaceId.value)) {
                    focusedWorkspaceId.value = action.positions.values.firstOrNull()
                }
            }
            is WorkspaceScreenAction.Focus -> {
                if (selectedWorkspaces.value.values.contains(action.id)) {
                    focusedWorkspaceId.value = action.id
                }
            }
            is WorkspaceScreenAction.ToggleSelection -> {
                val current = selectedWorkspaces.value
                val existingPosition = current.entries.find { it.value == action.id }?.key

                selectedWorkspaces.value = if (existingPosition != null) {
                    // Remove from selection
                    current - existingPosition
                } else {
                    // Add to selection
                    val position = action.position ?: current.keys.maxOrNull()?.plus(1) ?: 0
                    current + (position to action.id)
                }

                // Update focus if needed
                if (selectedWorkspaces.value.isEmpty()) {
                    focusedWorkspaceId.value = null
                } else if (!selectedWorkspaces.value.values.contains(focusedWorkspaceId.value)) {
                    focusedWorkspaceId.value = selectedWorkspaces.value.values.first()
                }
            }
            is WorkspaceScreenAction.SetPaneCount -> {
                log(tag) { "Setting pane count to ${action.count}" }
                currentPaneCount.value = action.count
                savedStateHandle["currentPaneCount"] = action.count
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
