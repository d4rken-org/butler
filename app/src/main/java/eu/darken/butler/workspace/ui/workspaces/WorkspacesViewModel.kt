package eu.darken.butler.workspace.ui.workspaces

import android.content.Intent
import androidx.lifecycle.SavedStateHandle
import dagger.hilt.android.lifecycle.HiltViewModel
import eu.darken.butler.common.WebpageTool
import eu.darken.butler.common.coroutine.DispatcherProvider
import eu.darken.butler.common.debug.logging.Logging.Priority.*
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.debug.logging.logTag
import eu.darken.butler.common.error.ErrorReportTool
import eu.darken.butler.common.flow.SingleEventFlow
import eu.darken.butler.common.flow.combine
import eu.darken.butler.common.navigation.Nav
import eu.darken.butler.common.navigation.upgrade
import eu.darken.butler.common.ui.ViewModel4
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
import eu.darken.butler.workspace.core.session.SessionRestorationException
import eu.darken.butler.workspace.ui.WorkspacePageManager
import eu.darken.butler.workspace.ui.dialogs.ManagerDialog
import eu.darken.butler.workspace.ui.feedback.BannerState
import eu.darken.butler.workspace.ui.session.WorkspaceSessionManager
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import javax.inject.Inject
import kotlin.uuid.Uuid


@HiltViewModel
class WorkspacesViewModel @Inject constructor(
    dispatchers: DispatcherProvider,
    upgradeRepo: UpgradeRepo,
    private val workspaceRepo: WorkspaceRepo,
    workspaceSettings: WorkspaceSettings,
    private val savedStateHandle: SavedStateHandle,
    val workspacePageManager: WorkspacePageManager,
    private val sessionManager: WorkspaceSessionManager,
    private val motdRepo: MotdRepo,
    private val webpageTool: WebpageTool,
    private val errorReportTool: ErrorReportTool,
) : ViewModel4(dispatchers, logTag("Workspace", "Screen", "VM")) {

    private val hiddenMotdIds = MutableStateFlow<Set<Uuid>>(emptySet())

    private val _bannerStates = MutableStateFlow<Map<Workspace.Id, BannerState>>(emptyMap())
    val bannerStates = _bannerStates.asStateFlow()

    private val _showClearSessionConfirmation = MutableStateFlow(false)
    val showClearSessionConfirmation = _showClearSessionConfirmation.asStateFlow()

    // Unified dialog registry - single source of truth
    private val _managerDialogs = MutableStateFlow<List<ManagerDialog>>(emptyList())
    val managerDialogs: StateFlow<List<ManagerDialog>> = _managerDialogs

    private var currentSessionError: Throwable? = null
    val shareIntentEvent = SingleEventFlow<Intent>()

    init {
        sessionManager.state
            .onEach { restorationState ->
                log(tag, INFO) { "Restoration state updated: $restorationState" }
                if (restorationState is WorkspaceSessionManager.State.Error) {
                    currentSessionError = restorationState.exception
                    val exception = SessionRestorationException(
                        cause = restorationState.exception,
                        onRequestClearSession = { _showClearSessionConfirmation.value = true },
                        onRequestShareError = { shareSessionError() },
                    )
                    errorEvents.emitBlocking(exception)
                }
            }
            .launchInViewModel()

        // Store and restore WorkspacePageManager from saved state
        workspacePageManager.initializeFromSavedState(savedStateHandle)
        workspacePageManager.state
            .onEach { state -> savedStateHandle["workspaceUIState"] = state }
            .launchInViewModel()

        // Observe pending confirmations and populate unified dialog registry
        workspaceRepo.pendingConfirmations
            .onEach { confirmations ->
                log(tag) { "Pending confirmations updated: ${confirmations.size}" }

                val dialogs = confirmations.mapNotNull { (confirmationId, confirmation) ->
                    when (val data = confirmation.data) {
                        is PendingWorkspaceConfirmation.ConfirmationData.WorkspaceLimitReached -> {
                            ManagerDialog.Global.WorkspaceLimitReached(
                                id = confirmationId,
                                currentCount = data.currentCount,
                                limit = data.limit,
                            )
                        }
                        is PendingWorkspaceConfirmation.ConfirmationData.BatchWorkspaceCreation -> {
                            val targetId = confirmation.sourceWorkspaceId
                                ?: workspacePageManager.state.value.focusedWorkspaceId
                                ?: run {
                                    log(tag, WARN) { "No target workspace for confirmation $confirmationId" }
                                    return@mapNotNull null
                                }
                            ManagerDialog.WorkspaceTargeted.BatchCreationConfirmation(
                                id = confirmationId,
                                targetWorkspaceId = targetId,
                                totalCount = data.totalCount,
                            )
                        }
                        is PendingWorkspaceConfirmation.ConfirmationData.WorkspaceCloseConfirmation -> {
                            val targetId = confirmation.sourceWorkspaceId
                                ?: workspacePageManager.state.value.focusedWorkspaceId
                                ?: run {
                                    log(tag, WARN) { "No target workspace for confirmation $confirmationId" }
                                    return@mapNotNull null
                                }
                            ManagerDialog.WorkspaceTargeted.CloseConfirmation(
                                id = confirmationId,
                                targetWorkspaceId = targetId,
                                workspaceTitle = data.workspaceTitle,
                            )
                        }
                    }
                }
                _managerDialogs.value = dialogs
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

    private val visibleMotd = kotlinx.coroutines.flow.combine(motdRepo.motd, hiddenMotdIds) { motd, hiddenIds ->
        motd?.takeIf { it.id !in hiddenIds }
    }

    val state = combine(
        workspaceRepo.state,
        upgradeRepo.upgradeInfo,
        workspaceSettings.swipeGesturesEnabled.flow,
        workspaceSettings.onDemandWorkspaceCreation.flow,
        workspacePageManager.state,
        visibleMotd,
        sessionManager.state,
    ) { repoState, upgradeInfo, swipeGesturesEnabled, onDemandWorkspaceCreation, uiState, motd, restorationState ->
        State(
            state = repoState,
            focusedWorkspace = uiState.focusedWorkspaceId,
            selectedWorkspaces = uiState.selectedWorkspaces,
            isUpgraded = upgradeInfo.isUpgraded,
            swipeGesturesEnabled = swipeGesturesEnabled,
            onDemandWorkspaceCreation = swipeGesturesEnabled && onDemandWorkspaceCreation,
            motd = motd,
            currentPaneCount = uiState.currentPaneCount,
            isRestoring = restorationState == WorkspaceSessionManager.State.Restoring,
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
                when (val result = workspaceRepo.execute(WorkspaceAction.Create(type = Workspace.Type.TEMPLATES))) {
                    is WorkspaceAction.Create.Result.Success -> {
                        log(tag) { "On-demand workspace created: ${result.newId}, focusing it" }
                        workspacePageManager.setFocusedWorkspace(result.newId)
                        workspacePageManager.setSelectedWorkspaces(mapOf(0 to result.newId))
                    }
                    is WorkspaceAction.Create.Result.LimitReached -> {
                        log(tag, WARN) { "On-demand workspace creation blocked - limit reached" }
                    }
                }
            }
            is WorkspaceScreenAction.CreateForPane -> {
                log(tag) { "Creating workspace for pane ${action.paneIndex}" }
                when (val result = workspaceRepo.execute(WorkspaceAction.Create(type = Workspace.Type.TEMPLATES))) {
                    is WorkspaceAction.Create.Result.Success -> {
                        log(tag) { "Workspace created: ${result.newId}, assigning to pane ${action.paneIndex}" }
                        workspacePageManager.setFocusedWorkspace(result.newId)
                        val currentSelections = workspacePageManager.state.value.selectedWorkspaces.toMutableMap()
                        currentSelections[action.paneIndex] = result.newId
                        workspacePageManager.setSelectedWorkspaces(currentSelections)
                    }
                    is WorkspaceAction.Create.Result.LimitReached -> {
                        log(tag, WARN) { "Workspace creation blocked - limit reached" }
                    }
                }
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
        val dialogState = _managerDialogs.value
            .filterIsInstance<ManagerDialog.WorkspaceTargeted>()
            .firstOrNull { it.targetWorkspaceId == workspaceId } ?: return@launch

        log(tag) { "Confirmation dialog dismissed, resolving as cancelled" }
        workspaceRepo.resolveConfirmation(dialogState.id, confirmed = false)
    }

    fun confirmManagerDialog(dialogState: ManagerDialog.WorkspaceTargeted) = launch {
        log(tag) { "confirmManagerDialog($dialogState)" }
        workspaceRepo.resolveConfirmation(dialogState.id, confirmed = true)
    }

    fun dismissBanner(workspaceId: Workspace.Id) = launch {
        log(tag) { "dismissBanner($workspaceId)" }
        _bannerStates.update { it - workspaceId }
    }

    fun dismissWorkspaceLimitDialog() {
        log(tag) { "dismissWorkspaceLimitDialog()" }
        val dialogState = _managerDialogs.value
            .filterIsInstance<ManagerDialog.Global.WorkspaceLimitReached>()
            .firstOrNull() ?: return
        workspaceRepo.resolveConfirmation(dialogState.id, confirmed = false)
    }

    fun onUpgradeFromLimitDialog() {
        log(tag) { "onUpgradeFromLimitDialog()" }
        val dialogState = _managerDialogs.value
            .filterIsInstance<ManagerDialog.Global.WorkspaceLimitReached>()
            .firstOrNull() ?: return
        workspaceRepo.resolveConfirmation(dialogState.id, confirmed = false)
        navTo(Nav.Main.upgrade())
    }

    fun dismissClearSessionConfirmation() {
        log(tag) { "dismissClearSessionConfirmation()" }
        _showClearSessionConfirmation.value = false
    }

    fun confirmClearSession() = launch {
        log(tag) { "confirmClearSession()" }
        _showClearSessionConfirmation.value = false
        sessionManager.clearSession()
    }

    private fun shareSessionError() {
        log(tag) { "shareSessionError()" }
        val error = currentSessionError ?: return
        val report = errorReportTool.buildReport(
            throwable = error,
            message = "Session restoration failed",
            errorContext = "WorkspacesViewModel",
        )
        val intent = errorReportTool.createShareChooserIntent(report)
        shareIntentEvent.tryEmit(intent)
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
        val isRestoring: Boolean = false,
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
