package eu.darken.butler.workspace.ui.workspaces

import androidx.lifecycle.SavedStateHandle
import dagger.hilt.android.lifecycle.HiltViewModel
import eu.darken.butler.common.WebpageTool
import eu.darken.butler.common.coroutine.DispatcherProvider
import eu.darken.butler.common.debug.logging.Logging.Priority.*
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.debug.logging.logTag
import eu.darken.butler.common.flow.combine
import eu.darken.butler.common.navigation.NavigationController
import eu.darken.butler.common.ui.ViewModel4
import eu.darken.butler.explorer.core.arguments.ExplorerArguments
import eu.darken.butler.main.core.motd.MotdRepo
import eu.darken.butler.main.core.motd.MotdState
import eu.darken.butler.upgrade.UpgradeRepo
import eu.darken.butler.workspace.core.PendingWorkspaceConfirmation
import eu.darken.butler.workspace.core.Workspace
import eu.darken.butler.workspace.core.WorkspaceAction
import eu.darken.butler.workspace.core.WorkspaceEvent
import eu.darken.butler.workspace.core.WorkspaceRemote
import eu.darken.butler.workspace.core.WorkspaceRepo
import eu.darken.butler.workspace.core.WorkspaceSettings
import eu.darken.butler.workspace.core.layout.WorkspacePanelMode
import eu.darken.butler.workspace.ui.WorkspacePageManager
import eu.darken.butler.workspace.ui.dialogs.WorkspaceManagerDialogState
import eu.darken.butler.workspace.ui.feedback.BannerState
import kotlinx.coroutines.delay
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

    private val _managerDialogStates = MutableStateFlow<Map<Workspace.Id, WorkspaceManagerDialogState.Targeted>>(
        emptyMap()
    )
    val managerDialogStates = _managerDialogStates.asStateFlow()

    private val _bannerStates = MutableStateFlow<Map<Workspace.Id, BannerState>>(emptyMap())
    val bannerStates = _bannerStates.asStateFlow()

    init {
        launch {
            val currentWorkspaces = workspaceRepo.state.first()
            if (currentWorkspaces.infos.isEmpty()) {
                log(tag) { "No workspaces found, attempting to restore session" }

                // Try to restore previous session
                val restoredIds = workspaceRepo.restoreSession()

                if (restoredIds.isEmpty()) {
                    // No session to restore or restoration failed, create default workspace
                    log(tag) { "No session restored, creating default workspace" }
                    workspaceRepo.execute(
                        WorkspaceAction.Create(
                            type = Workspace.Type.EXPLORER,
                            arguments = ExplorerArguments.Default()
                        )
                    )
                } else {
                    log(tag, INFO) { "Restored ${restoredIds.size} workspaces from session" }
                }
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

        // Observe pending confirmations and show dialogs
        workspaceRepo.pendingConfirmations
            .onEach { confirmations ->
                log(tag) { "Pending confirmations updated: ${confirmations.size}" }

                // Map confirmations to dialog states
                val newDialogStates = confirmations.mapNotNull { (confirmationId, confirmation) ->
                    val targetWorkspaceId = confirmation.sourceWorkspaceId
                        ?: workspacePageManager.state.value.focusedWorkspaceId
                        ?: run {
                            log(tag, WARN) { "No target workspace for confirmation $confirmationId" }
                            return@mapNotNull null
                        }

                    val dialogState = when (val data = confirmation.data) {
                        is PendingWorkspaceConfirmation.ConfirmationData.BatchWorkspaceCreation -> {
                            WorkspaceManagerDialogState.OpenInNewTabsConfirmation(
                                confirmationId = confirmationId,
                                targetWorkspaceId = targetWorkspaceId,
                                totalCount = data.totalCount,
                            )
                        }
                        is PendingWorkspaceConfirmation.ConfirmationData.WorkspaceCloseConfirmation -> {
                            WorkspaceManagerDialogState.WorkspaceCloseConfirmation(
                                confirmationId = confirmationId,
                                targetWorkspaceId = targetWorkspaceId,
                                workspaceTitle = data.workspaceTitle,
                            )
                        }
                        // Future: map other confirmation types to appropriate dialogs
                    }

                    targetWorkspaceId to dialogState
                }.toMap()

                _managerDialogStates.update { newDialogStates }
            }
            .launchInViewModel()

        // Observe workspace events for banner feedback
        workspaceRepo.events
            .onEach { event ->
                when (event) {
                    is WorkspaceEvent.BatchCreationCompleted -> {
                        log(tag, INFO) { "BatchCreationCompleted: $event" }

                        val targetWorkspaceId = event.sourceWorkspaceId
                            ?: workspacePageManager.state.value.focusedWorkspaceId

                        if (targetWorkspaceId != null) {
                            val bannerState = if (event.failureCount == 0 && event.skippedCount == 0) {
                                BannerState.Success(event.successCount)
                            } else {
                                BannerState.Partial(event.successCount, event.failureCount, event.skippedCount)
                            }

                            _bannerStates.update { states ->
                                states + (targetWorkspaceId to bannerState)
                            }

                            // Auto-clear banner after 3 seconds
                            launch {
                                delay(3000)
                                _bannerStates.update { it - targetWorkspaceId }
                            }
                        }
                    }
                    else -> {} // Ignore other events
                }
            }
            .launchInViewModel()

        log(tag) { "WorkspacesViewModel initialization complete" }
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
            currentPaneCount = uiState.currentPaneCount,
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

    fun dismissManagerDialog(workspaceId: Workspace.Id) = launch {
        log(tag) { "dismissManagerDialog($workspaceId)" }
        val dialogState = _managerDialogStates.value[workspaceId]

        // For confirmation dialogs, resolve as cancelled
        if (dialogState is WorkspaceManagerDialogState.OpenInNewTabsConfirmation) {
            log(tag) { "Confirmation dialog dismissed, resolving as cancelled" }
            workspaceRepo.resolveConfirmation(dialogState.confirmationId, confirmed = false)
        }

        log(tag) { "dismissManagerDialog() - dialog removed for workspace $workspaceId" }
    }

    fun confirmManagerDialog(dialogState: WorkspaceManagerDialogState.Targeted) = launch {
        log(tag) { "confirmManagerDialog($dialogState)" }

        when (dialogState) {
            is WorkspaceManagerDialogState.OpenInNewTabsConfirmation -> {
                log(tag) { "Confirmation dialog confirmed, resolving" }
                workspaceRepo.resolveConfirmation(dialogState.confirmationId, confirmed = true)
            }
            is WorkspaceManagerDialogState.WorkspaceCloseConfirmation -> {
                log(tag) { "Workspace close confirmation confirmed, resolving" }
                workspaceRepo.resolveConfirmation(dialogState.confirmationId, confirmed = true)
            }
            // Handle other dialog types here in the future
        }
    }

    fun dismissBanner(workspaceId: Workspace.Id) = launch {
        log(tag) { "dismissBanner($workspaceId)" }
        _bannerStates.update { it - workspaceId }
    }

    data class State(
        private val state: WorkspaceRemote.State,
        val focusedWorkspace: Workspace.Id?,
        val selectedWorkspaces: Map<Int, Workspace.Id>,
        val isUpgraded: Boolean,
        val swipeGesturesEnabled: Boolean = true,
        val onDemandWorkspaceCreation: Boolean = true,
        val motd: MotdState? = null,
        val currentPaneCount: Int = 1,
    ) {
        val portraitPanelMode: WorkspacePanelMode
            get() = state.portraitPanelMode

        val landscapePanelMode: WorkspacePanelMode
            get() = state.landscapePanelMode

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

        private val isMultiPane: Boolean
            get() = currentPaneCount > 1

        /**
         * Workspaces that should render in panes/tabs.
         * Only includes normal workspaces (not sub-workspaces).
         * Sub-workspaces render as overlays (either Dialog or Box within parent pane).
         */
        val tabWorkspaces: List<Workspace.Info>
            get() = state.infos.filter { !it.isSubWorkspace }

        /**
         * Workspace that should render as a full-screen Dialog overlay covering all panes.
         * Includes:
         * - FULL_SCREEN modals (pickers, settings dialogs) - always render as Dialog
         * - PANE_LOCAL modals on single-pane devices (phones) - render as Dialog
         */
        val fullScreenModalWorkspace: Workspace.Info?
            get() = state.infos.firstOrNull { info ->
                info.isSubWorkspace && when (info.modalPresentation) {
                    // Full-screen modals always render as Dialog overlay
                    Workspace.ModalPresentationMode.FULL_SCREEN -> true

                    // Pane-local modals only render as Dialog in single-pane layout
                    Workspace.ModalPresentationMode.PANE_LOCAL -> !isMultiPane
                }
            }

        /**
         * Map of parent workspace ID to their pane-local modal child (if any).
         * Only populated in multi-pane layouts.
         * Each pane can look up if it has a child modal overlay: `paneLocalModals[parentId]`
         *
         * Example: Apps workspace (parent) → App details (child modal overlay)
         */
        val paneLocalModals: Map<Workspace.Id, Workspace.Info>
            get() = if (!isMultiPane) {
                emptyMap()
            } else {
                state.infos
                    .filter { info ->
                        info.isSubWorkspace &&
                            info.modalPresentation == Workspace.ModalPresentationMode.PANE_LOCAL &&
                            info.callerWorkspaceId != null
                    }
                    .associateBy { it.callerWorkspaceId!! }
            }
    }
}
