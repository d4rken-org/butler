package eu.darken.butler.workspace.ui.workspaces

import android.app.Activity
import android.content.Intent
import androidx.lifecycle.SavedStateHandle
import dagger.hilt.android.lifecycle.HiltViewModel
import eu.darken.butler.common.WebpageTool
import eu.darken.butler.common.compose.tour.GuidedTourController
import eu.darken.butler.common.coroutine.DispatcherProvider
import eu.darken.butler.common.debug.bugreport.BugReportRepo
import eu.darken.butler.common.debug.logging.Logging.Priority.*
import eu.darken.butler.common.debug.logging.asLog
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.debug.logging.logTag
import eu.darken.butler.common.error.ErrorReportTool
import eu.darken.butler.common.flow.SingleEventFlow
import eu.darken.butler.common.flow.combine
import eu.darken.butler.common.navigation.Nav
import eu.darken.butler.common.navigation.upgrade
import eu.darken.butler.common.review.ReviewTool
import eu.darken.butler.common.ui.ViewModel4
import eu.darken.butler.main.core.motd.MotdRepo
import eu.darken.butler.main.core.motd.MotdState
import eu.darken.butler.upgrade.UpgradeRepo
import eu.darken.butler.workspace.contracts.bugreport.BugReportArguments
import eu.darken.butler.workspace.contracts.editor.EditorArguments
import eu.darken.butler.workspace.contracts.explorer.ExplorerArguments
import eu.darken.butler.workspace.contracts.viewer.ViewerArguments
import eu.darken.butler.workspace.core.OpenInNewTabsUseCase
import eu.darken.butler.workspace.core.PendingWorkspaceConfirmation
import eu.darken.butler.workspace.core.RenderedWorkspaceStacks
import eu.darken.butler.workspace.core.Workspace
import eu.darken.butler.workspace.core.WorkspaceStackChain
import eu.darken.butler.workspace.core.WorkspaceStacks
import eu.darken.butler.workspace.ui.WorkspacePageHostEntry
import eu.darken.butler.workspace.core.WorkspaceAction
import eu.darken.butler.workspace.core.WorkspaceEvent
import eu.darken.butler.workspace.core.WorkspaceRemote
import eu.darken.butler.workspace.core.WorkspaceRepo
import eu.darken.butler.workspace.core.WorkspaceSettings
import eu.darken.butler.workspace.core.layout.WorkspacePanelMode
import eu.darken.butler.workspace.core.session.SessionRestorationException
import eu.darken.butler.workspace.ui.WorkspacePageManager
import eu.darken.butler.workspace.ui.WorkspaceVisibilityTracker
import eu.darken.butler.workspace.ui.dialogs.ManagerDialog
import eu.darken.butler.workspace.ui.feedback.BannerState
import eu.darken.butler.workspace.ui.floatingbar.WorkspaceBarCollapseStates
import eu.darken.butler.workspace.ui.scroll.WorkspaceScrollPositions
import eu.darken.butler.workspace.ui.session.WorkspaceSessionManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.take
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
    private val bugReportRepo: BugReportRepo,
    private val openInNewTabsUseCase: OpenInNewTabsUseCase,
    private val reviewTool: ReviewTool,
    private val guidedTourController: GuidedTourController,
    val pageHosts: Map<Workspace.Type, @JvmSuppressWildcards WorkspacePageHostEntry>,
    val scrollPositions: WorkspaceScrollPositions,
    val barCollapseStates: WorkspaceBarCollapseStates,
    val pagerVisibility: WorkspaceVisibilityTracker,
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
                                candidates = data.candidates,
                                canRecover = data.canRecover,
                                minToClose = data.minToClose,
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
                                hasUnsavedChanges = data.hasUnsavedChanges,
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

                            // Auto-dismiss is owned by the banner UI, which pauses its countdown
                            // while the workspace's content layer is covered by a modal. A timer
                            // here would expire regardless and swallow the banner unseen.
                            _bannerStates.update { states ->
                                states + (targetWorkspaceId to bannerState)
                            }
                        }
                    }
                    else -> {} // Ignore other events
                }
            }
            .launchInViewModel()

        // Once session restore settles (this VM only exists post-onboarding), surface a fresh crash
        // report as a focused workspace. Wait for a terminal state — restore-disabled users never
        // reach Restored, so Disabled must also qualify; Error is left to the error handler above.
        sessionManager.state
            .filter {
                it is WorkspaceSessionManager.State.Restored || it == WorkspaceSessionManager.State.Disabled
            }
            .take(1)
            .onEach { surfaceUnseenCrashReportIfAny() }
            .launchInViewModel()

        log(tag) { "WorkspacesViewModel initialization complete" }
    }

    private suspend fun surfaceUnseenCrashReportIfAny() {
        if (!bugReportRepo.hasUnseenCrashes.first()) return
        log(tag, INFO) { "Unseen crash report present — surfacing bug report workspace" }

        val targetId = when (
            val result = workspaceRepo.execute(
                WorkspaceAction.Create(
                    type = Workspace.Type.BUG_REPORT,
                    arguments = BugReportArguments.Default(),
                ),
            )
        ) {
            is WorkspaceAction.Create.Result.Success -> result.newId
            is WorkspaceAction.Create.Result.AlreadyOpen -> result.existingId
            is WorkspaceAction.Create.Result.LimitReached -> {
                log(tag, WARN) { "Bug report workspace creation unexpectedly limited" }
                null
            }
            else -> null
        } ?: return

        workspacePageManager.setLayout(mapOf(0 to targetId), focusedId = targetId)
    }

    private val visibleMotd = kotlinx.coroutines.flow.combine(motdRepo.motd, hiddenMotdIds) { motd, hiddenIds ->
        motd?.takeIf { it.id !in hiddenIds }
    }

    // The review tool shares its state on AppScope, so a failure there can never reach a collector
    // side handler. This one only keeps a broken review pipeline from taking the workspace state
    // (and with it the whole screen) down with it.
    private val reviewState = reviewTool.state.catch { e ->
        if (e is CancellationException) throw e
        log(tag, ERROR) { "Review state failed: ${e.asLog()}" }
        emit(ReviewTool.State())
    }

    val state = combine(
        workspaceRepo.state,
        upgradeRepo.upgradeInfo,
        workspaceSettings.swipeGesturesEnabled.flow,
        workspaceSettings.onDemandWorkspaceCreation.flow,
        workspaceSettings.paneClickToFocus.flow,
        workspacePageManager.state,
        visibleMotd,
        sessionManager.state,
        _managerDialogs,
        reviewState,
        guidedTourController.session,
    ) { repoState, upgradeInfo, swipeGesturesEnabled, onDemandWorkspaceCreation, paneClickToFocus, uiState, motd, restorationState, dialogs, review, tourSession ->
        val base = State(
            state = repoState,
            focusedWorkspace = uiState.focusedWorkspaceId,
            selectedWorkspaces = uiState.selectedWorkspaces,
            visiblePaneSelections = uiState.visiblePaneAssignments,
            isUpgraded = upgradeInfo.isPro,
            swipeGesturesEnabled = swipeGesturesEnabled,
            onDemandWorkspaceCreation = swipeGesturesEnabled && onDemandWorkspaceCreation,
            paneClickToFocus = paneClickToFocus,
            motd = motd,
            currentPaneCount = uiState.currentPaneCount,
            isRestoring = restorationState == WorkspaceSessionManager.State.Restoring,
        )

        // Asking for a favor is the lowest-priority surface there is: anything that asks the user
        // for a decision, or covers the screen, has to win over it. Both modal buckets have to be
        // checked: a chain lands in exactly one of them, so either one alone would miss a case.
        // A guided tour scrims the whole screen, so the card would render dimmed and untappable
        // underneath it.
        val isQuiet = motd == null &&
            !uiState.isManagerOverlayVisible &&
            dialogs.isEmpty() &&
            base.fullScreenModalWorkspace == null &&
            base.paneLocalModalChains.isEmpty() &&
            tourSession == null

        base.copy(showReviewCard = review.shouldAskForReview && isQuiet)
    }.asStateFlow()

    fun executeScreenAction(action: WorkspaceScreenAction) = launch {
        log(tag) { "executeScreenAction($action)" }

        when (action) {
            is WorkspaceScreenAction.Select -> {
                workspacePageManager.setLayout(mapOf(0 to action.id), focusedId = action.id)
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
                // setPaneCount suspends through its MRU auto-fill, so once it returns the layout is
                // complete and everything it placed belongs to startup rather than to a user
                // assignment. Repeat calls are ignored, only the first one arms the session manager.
                sessionManager.onInitialPaneLayoutApplied()
            }
            is WorkspaceScreenAction.Rename -> {
                log(tag, INFO) { "Renaming workspace ${action.id} to ${action.customTitle}" }
                workspaceRepo.execute(WorkspaceAction.Rename(action.id, action.customTitle))
            }
            is WorkspaceScreenAction.ResumeWorkspace -> {
                log(tag, INFO) { "Resuming paused workspace ${action.id}" }
                workspaceRepo.execute(WorkspaceAction.Resume(action.id))
            }
            is WorkspaceScreenAction.CreateOnDemand -> {
                log(tag) { "Creating workspace on-demand" }
                when (val result = workspaceRepo.execute(WorkspaceAction.Create(type = Workspace.Type.TEMPLATES))) {
                    is WorkspaceAction.Create.Result.Success -> {
                        log(tag) { "On-demand workspace created: ${result.newId}, focusing it" }
                        workspacePageManager.setLayout(mapOf(0 to result.newId), focusedId = result.newId)
                    }
                    is WorkspaceAction.Create.Result.AlreadyOpen -> {
                        log(tag) { "Singleton already open, focusing existing: ${result.existingId}" }
                        workspacePageManager.setLayout(mapOf(0 to result.existingId), focusedId = result.existingId)
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
                        val selections = workspacePageManager.state.value.selectedWorkspaces +
                            (action.paneIndex to result.newId)
                        workspacePageManager.setLayout(selections, focusedId = result.newId)
                    }
                    is WorkspaceAction.Create.Result.AlreadyOpen -> {
                        log(tag) { "Singleton already open, focusing existing: ${result.existingId} in pane ${action.paneIndex}" }
                        val selections = workspacePageManager.state.value.selectedWorkspaces +
                            (action.paneIndex to result.existingId)
                        workspacePageManager.setLayout(selections, focusedId = result.existingId)
                    }
                    is WorkspaceAction.Create.Result.LimitReached -> {
                        log(tag, WARN) { "Workspace creation blocked - limit reached" }
                    }
                }
            }
            is WorkspaceScreenAction.OpenDropInPane -> {
                val item = action.payload.items.singleOrNull()
                if (item == null) {
                    log(tag, WARN) { "OpenDropInPane needs a single item, got ${action.payload.items.size}" }
                    return@launch
                }
                log(tag) { "Opening dropped ${item.path} in pane ${action.paneIndex}" }
                val request = openInNewTabsUseCase.createRequest(
                    item = item.toOpenInNewTabsItem(),
                    createExplorerArguments = { ExplorerArguments.Default(startPath = it) },
                    createEditorArguments = { EditorArguments.Default(filePath = it) },
                    createViewerArguments = { ViewerArguments.Default(filePath = it) },
                )
                when (val result = workspaceRepo.execute(request)) {
                    is WorkspaceAction.Create.Result.Success -> {
                        log(tag) { "Dropped item opened as ${result.newId} in pane ${action.paneIndex}" }
                        workspacePageManager.setLayout(
                            paneAssignmentAfterDrop(
                                current = workspacePageManager.state.value.selectedWorkspaces,
                                paneIndex = action.paneIndex,
                                workspaceId = result.newId,
                            ),
                            focusedId = result.newId,
                        )
                    }
                    is WorkspaceAction.Create.Result.AlreadyOpen -> {
                        log(tag) { "Dropped item already open as ${result.existingId}, moving it to the pane" }
                        workspacePageManager.setLayout(
                            paneAssignmentAfterDrop(
                                current = workspacePageManager.state.value.selectedWorkspaces,
                                paneIndex = action.paneIndex,
                                workspaceId = result.existingId,
                            ),
                            focusedId = result.existingId,
                        )
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

    fun reviewNow(activity: Activity) = launch {
        log(tag) { "reviewNow($activity)" }
        reviewTool.reviewNow(activity)
    }

    fun reviewDismiss() = launch {
        log(tag) { "reviewDismiss()" }
        reviewTool.dismiss()
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

    fun onCloseSelectedFromLimitDialog(victims: Set<Workspace.Id>) = launch {
        log(tag) { "onCloseSelectedFromLimitDialog($victims)" }
        val dialogState = _managerDialogs.value
            .filterIsInstance<ManagerDialog.Global.WorkspaceLimitReached>()
            .firstOrNull() ?: return@launch
        // The recovery runs on the repo's app scope, so awaiting it here can be cancelled without
        // aborting it half-way; only the reporting is tied to this screen.
        val error = workspaceRepo.resolveLimitByClosing(dialogState.id, victims).await()
        if (error != null) {
            log(tag, ERROR) { "Limit recovery failed: ${error.asLog()}" }
            errorEvents.emit(error)
        }
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

    fun shareWorkspaceError(workspaceId: Workspace.Id, error: Throwable) {
        log(tag) { "shareWorkspaceError($workspaceId, $error)" }
        val report = errorReportTool.buildReport(
            throwable = error,
            message = "Workspace initialization failed",
            errorContext = "Workspace:${workspaceId.shortTag}",
        )
        val intent = errorReportTool.createShareChooserIntent(report)
        shareIntentEvent.tryEmit(intent)
    }

    data class State(
        private val state: WorkspaceRemote.State,
        val focusedWorkspace: Workspace.Id?,
        val selectedWorkspaces: Map<Int, Workspace.Id>,
        val visiblePaneSelections: Map<Int, Workspace.Id> = emptyMap(),
        val isUpgraded: Boolean,
        val swipeGesturesEnabled: Boolean = true,
        val onDemandWorkspaceCreation: Boolean = true,
        val paneClickToFocus: Boolean = true,
        val motd: MotdState? = null,
        val currentPaneCount: Int = 1,
        val isRestoring: Boolean = false,
        val showReviewCard: Boolean = false,
    ) {
        val portraitPanelMode: WorkspacePanelMode
            get() = state.portraitPanelMode

        val landscapePanelMode: WorkspacePanelMode
            get() = state.landscapePanelMode

        val focused: Workspace.Id?
            get() = focusedWorkspace

        /**
         * Every pane assignment, including indices this layout does not render. Pane assignment
         * reads this so moving a workspace between panes does not silently drop the arrangement a
         * wider layout left behind. Use [visibleSelected] for anything the user sees.
         */
        val selected: Map<Int, WorkspacePaneInfo>
            get() = selectedWorkspaces.toPaneInfos()

        /** The assignments this layout actually shows - what pane numbers may be derived from. */
        val visibleSelected: Map<Int, WorkspacePaneInfo>
            get() = visiblePaneSelections.toPaneInfos()

        private fun Map<Int, Workspace.Id>.toPaneInfos(): Map<Int, WorkspacePaneInfo> = this
            .mapNotNull { (position, id) ->
                tabWorkspaces.find { it.id == id }?.let { position to it.asPaneInfo() }
            }
            .toMap()

        val all: List<Workspace.Info>
            get() = state.infos

        /**
         * Workspaces that should render in panes/tabs.
         * Only includes normal workspaces (not sub-workspaces).
         * Sub-workspaces render as overlays (either Dialog or Box within parent pane).
         */
        val tabWorkspaces: List<Workspace.Info>
            get() = state.infos.filter { !it.isSubWorkspace }

        /**
         * What this layout puts on screen, resolved by the shared ownership walk. Shared on purpose:
         * auto-pause decides what is idle from the same resolution, and the two disagreeing is what
         * let a rendered modal be treated as unseen.
         */
        private val renderedStacks: RenderedWorkspaceStacks by lazy {
            WorkspaceStacks(state.infos).renderedChains(focusedId = focusedWorkspace)
        }

        /**
         * Deepest modal of the chain that should render as a full-screen Dialog covering all panes,
         * or null when no chain qualifies. See [WorkspaceStackChain.isFullScreen].
         *
         * Mutually exclusive with [paneLocalModalChains]: every resolved chain lands in exactly one
         * of the two, so a chain is never rendered twice.
         */
        val fullScreenModalWorkspace: Workspace.Info?
            get() = renderedStacks.fullScreen?.leaf

        /**
         * Modal chains that render inside their owning tab's pane, keyed by that tab.
         *
         * Each value is nearest-tab-first, so a pane can stack it directly: index 0 sits on the
         * tab's own workspace, index 1 on that, and so on. Independent of the pane count - on a
         * single-pane layout the owning tab is the pager page the chain stacks inside.
         */
        val paneLocalModalChains: Map<Workspace.Id, List<Workspace.Info>>
            get() = renderedStacks.paneLocal.mapValues { (_, chain) -> chain.modals }

        /**
         * The tab that owns [focused], i.e. the pager page / pane the user is working in. Null when
         * nothing is focused or the focused id's caller chain is dangling or cyclic.
         *
         * The raw [focused] id is not usable for anything keyed on tabs: a stacked child never
         * appears in [tabWorkspaces], so looking it up there yields nothing.
         */
        val focusedRootId: Workspace.Id?
            get() = focusedWorkspace?.let { WorkspaceStacks(state.infos).rootOf(it)?.id }
    }
}
