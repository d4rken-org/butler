package eu.darken.butler.workspace.ui.manager

import dagger.hilt.android.lifecycle.HiltViewModel
import eu.darken.butler.common.ca.CaString
import eu.darken.butler.common.ca.toCaString
import eu.darken.butler.common.coroutine.DispatcherProvider
import eu.darken.butler.common.datastore.value
import eu.darken.butler.common.debug.logging.Logging.Priority.*
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.debug.logging.logTag
import eu.darken.butler.common.flow.combine
import eu.darken.butler.common.ui.ViewModel4
import eu.darken.butler.workspace.core.Workspace
import eu.darken.butler.workspace.core.WorkspaceAction
import eu.darken.butler.workspace.core.WorkspacePauseGate
import eu.darken.butler.workspace.core.WorkspaceRepo
import eu.darken.butler.workspace.core.WorkspaceSettings
import eu.darken.butler.workspace.core.WorkspaceStacks
import eu.darken.butler.workspace.core.defaultArguments
import eu.darken.butler.workspace.ui.WorkspacePageManager
import eu.darken.butler.workspace.ui.manager.preview.WorkspacePreviewManager
import eu.darken.butler.workspace.ui.template.QuickCreateItem
import eu.darken.butler.workspace.ui.template.WorkspaceTemplate
import eu.darken.butler.workspace.ui.template.availableTemplates
import eu.darken.butler.workspace.ui.template.toQuickCreateItem
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import kotlin.time.Duration.Companion.milliseconds

@HiltViewModel
class WorkspaceManagerViewModel @Inject constructor(
    dispatchers: DispatcherProvider,
    private val workspaceRepo: WorkspaceRepo,
    private val workspaceSettings: WorkspaceSettings,
    private val workspacePageManager: WorkspacePageManager,
    private val workspacePauseGate: WorkspacePauseGate,
    private val workspacePreviewManager: WorkspacePreviewManager,
    workspaceTemplates: Set<@JvmSuppressWildcards WorkspaceTemplate>,
) : ViewModel4(dispatchers, logTag("Workspace", "Manager", "VM")) {

    private val filterOperationsFlow = MutableStateFlow(false)
    private val filterAttentionFlow = MutableStateFlow(false)

    /**
     * Null means selection mode is off; a set (never empty) means it is on. Ids can name tabs that
     * closed from elsewhere while the manager was open, so what the UI sees is pruned in [state]
     * and what a batch close acts on is re-resolved against the repo.
     */
    private val selectionFlow = MutableStateFlow<Set<Workspace.Id>?>(null)

    private val quickCreateItems = workspaceTemplates.availableTemplates()
        .map { templates -> templates.filter { it.isQuickCreate }.map { it.toQuickCreateItem() } }

    val state = combine(
        workspaceRepo.state,
        workspaceSettings.showTipBadgeExplanation.flow,
        workspaceSettings.livePreview.flow,
        workspacePageManager.state,
        filterOperationsFlow,
        filterAttentionFlow,
        quickCreateItems,
        selectionFlow,
    ) { repoState, showBadge, livePreview, pageManagerState, filterOps, filterAtt, quickCreate, selection ->
        val stacks = WorkspaceStacks(repoState.infos)
        val focusedId = pageManagerState.focusedWorkspaceId
        val topChains = stacks.topChainByRoot(focusedId)
        val membersByOwner = repoState.infos.groupBy { stacks.ownerOf(it.id) }
        // Pane chips describe where a workspace is on screen, so they follow the layout's own panes
        // rather than the raw selection map, which retains indices from wider layouts.
        val visibleAssignments = pageManagerState.visiblePaneAssignments
        State(
            workspaces = stacks.unitOwners.map { owner ->
                val chain = topChains[owner.id]
                val top = chain?.leaf ?: owner
                // Counts belong to the unit, not to one member: an overlay's running operation or
                // attention badge is the tab's, and the manager's filters read these per card.
                val members = membersByOwner[owner.id].orEmpty().ifEmpty { listOf(owner) }
                WorkspaceItem(
                    id = owner.id,
                    topId = top.id,
                    type = top.type,
                    title = owner.customTitle?.toCaString() ?: top.title,
                    subtitle = top.subtitle,
                    autoTitle = top.title,
                    customTitle = owner.customTitle,
                    // A pane holds tabs, so pane placement and focus are the owner's; focus may sit
                    // anywhere in the unit
                    isFocused = members.any { it.id == focusedId },
                    isVisibleInPane = visibleAssignments.values.contains(owner.id),
                    paneNumber = visibleAssignments.entries.find { it.value == owner.id }?.key,
                    operationCount = members.sumOf { it.operationCount },
                    attentionCount = members.sumOf { it.attentionCount },
                    hasUnsavedChanges = members.any { it.hasUnsavedChanges },
                    isSubWorkspace = owner.isSubWorkspace,
                    isRecovery = stacks.recoveryUnits.containsKey(owner.id),
                    isPaused = owner.isPaused,
                    canPause = owner.canBePausedManually(stacks, focusedId),
                    stackDepth = chain?.modals?.size ?: 0,
                )
            },
            useLivePreview = livePreview,
            showBadgeExplanation = showBadge,
            operationsCount = repoState.operationCount,
            attentionCount = repoState.attentionCount,
            currentPaneCount = pageManagerState.currentPaneCount,
            filterOperations = filterOps,
            filterAttention = filterAtt,
            quickCreateItems = quickCreate,
            hasUnsavedChanges = repoState.infos.any { it.hasUnsavedChanges },
            selectedIds = selection
                ?.intersect(stacks.unitOwners.map { it.id }.toSet())
                // An empty result is not "selection mode with nothing picked": every selected tab
                // closed from elsewhere, so the mode is over. Left as an empty set the bar would sit
                // at "0 selected" with no way back except Cancel.
                ?.ifEmpty { null },
        )
    }.asStateFlow()

    /**
     * Mirrors WorkspaceRepo's pause guards, relaxed for visible-but-unfocused panes: manually
     * pausing one of those is explicit user intent, while auto-pause may not touch it. Content-path
     * claims are invisible here, so this stays eventually consistent - the repo can still refuse.
     *
     * Pausing acts on a whole ownership unit, so every member has to be pausable, not just this card:
     * a modal child that would lose state (or owes its caller a result) keeps its whole tab awake.
     * Child cards never offer Pause themselves - a child only goes down with the tab that owns it.
     * A focused member anywhere in the unit excludes it too: resume-on-focus would immediately undo
     * the pause. [stacks] guards against cycles and orphaned callers, which nothing validates at
     * creation time; an unresolvable unit is not offered.
     */
    private fun Workspace.Info.canBePausedManually(
        stacks: WorkspaceStacks,
        focusedId: Workspace.Id?,
    ): Boolean {
        if (isPaused || !isReady || isSubWorkspace) return false
        val members = stacks.unitOf(id) ?: return false
        if (members.any { it.id == focusedId }) return false
        return members.all { member ->
            when {
                member.id != id && !member.pausableAsChild -> false
                member.operationCount > 0 || member.attentionCount > 0 -> false
                member.hasUnsavedChanges || !member.isPausable -> false
                !member.isReady -> false
                else -> true
            }
        }
    }

    fun closeWorkspace(id: Workspace.Id) = launch {
        log(tag) { "closeWorkspace($id)" }
        workspaceRepo.execute(
            WorkspaceAction.Close(id, sourceWorkspaceId = closeConfirmationHost(), undoable = true)
        )
    }

    /**
     * Whose pane hosts a close confirmation raised from here: the focused tab's pane when that is
     * one of the panes on screen, otherwise the first that is. Null when no pane shows anything.
     *
     * The overlay can be gone before the confirmation is published - dismissing it right after the
     * tap is enough - and an unanchored confirmation then falls back to the closing tab itself,
     * whose pane may be off screen. Naming a pane that is on screen leaves a host that can compose
     * the dialog either way.
     *
     * The focused id can name a stacked child rather than a tab, so it is resolved to its ownership
     * root before it is looked for among the panes.
     */
    private fun closeConfirmationHost(): Workspace.Id? {
        val pageState = workspacePageManager.state.value
        val visibleIds = pageState.visiblePaneAssignments.entries.sortedBy { it.key }.map { it.value }
        val focusedRoot = pageState.focusedWorkspaceId?.let { workspaceRepo.peekOwnershipRoot(it) }
        return visibleIds.firstOrNull { it == focusedRoot } ?: visibleIds.firstOrNull()
    }

    /**
     * The lease keeps the pause from swapping an instance out from under a preview capture of the
     * same unit - the manager being open is exactly when captures run. Keyed on the ownership root,
     * because the pause releases every member of that unit, not just this card.
     */
    fun pauseWorkspace(id: Workspace.Id) = launch {
        log(tag) { "pauseWorkspace($id)" }
        val leaseKey = workspaceRepo.peekOwnershipRoot(id)
        val result = workspacePauseGate.withLease(leaseKey) { workspaceRepo.execute(WorkspaceAction.Pause(id)) }
        // canPause is eventually consistent, so a benign refusal is expected; the card just stays.
        if (result !is WorkspaceAction.Pause.Result.Success) {
            log(tag, WARN) { "Pausing $id did not succeed: $result" }
        }
    }

    /** Resumes the whole unit [id] belongs to, so a child card's Resume also wakes its owner. */
    fun resumeWorkspace(id: Workspace.Id) = launch {
        log(tag) { "resumeWorkspace($id)" }
        workspaceRepo.execute(WorkspaceAction.Resume(id))
    }

    /** The cards are unit owners, so this is a unit order; the repo expands it to the full list. */
    fun reorderWorkspaces(workspaceIds: List<Workspace.Id>) = launch {
        workspaceRepo.execute(WorkspaceAction.Reorder(workspaceIds))
    }

    fun renameWorkspace(id: Workspace.Id, customTitle: String?) = launch {
        log(tag) { "renameWorkspace($id, $customTitle)" }
        workspaceRepo.execute(WorkspaceAction.Rename(id, customTitle))
    }

    /**
     * The card names a tab; what has to be focused is whatever sits on top of it *now*. Resolving
     * here rather than baking a leaf id into the card is what keeps a tap from naming an overlay that
     * closed while the manager was open: selectWorkspaceFromManager suspends until the id exists in
     * the repo, so a vanished leaf would hang the tap and leave the manager stuck open.
     *
     * The topology comes from peekStacks, not from the shared state flow: that flow's replay cache
     * can lag a swap and name a leaf the repo has already dropped, which is exactly the hang this
     * resolution exists to avoid - the tap would do nothing and the manager would never close.
     */
    fun selectWorkspace(id: Workspace.Id) = launch {
        log(tag) { "selectWorkspace($id)" }
        val stacks = workspaceRepo.peekStacks()
        val focusedId = workspacePageManager.state.value.focusedWorkspaceId
        val target = stacks.topChainByRoot(focusedId)[id]?.leaf?.id ?: id
        // Emit selection event to notify the parent screen
        workspacePageManager.selectWorkspaceFromManager(target)

        // Wait for selection to be processed before navigating back
        // This ensures pager animation can complete without being interrupted
        val selectionProcessed = withTimeoutOrNull(500.milliseconds) {
            workspacePageManager.state.first { it.focusedWorkspaceId == target }
        }

        if (selectionProcessed == null) {
            log(tag) { "Selection processing timed out, navigating back anyway" }
        }

        navigateBack()
    }

    fun createWorkspace(item: QuickCreateItem) = createWorkspace(item.type, item.arguments)

    fun createWorkspace(type: Workspace.Type, arguments: Workspace.Arguments? = null) = launch {
        log(tag) { "createWorkspace($type)" }
        val args = arguments ?: type.defaultArguments ?: return@launch
        // Opts into limit recovery: this is the most obvious way to run into the free-tier cap, so it
        // has to be a place the limit dialog can offer tabs to close rather than only an upgrade.
        // A recovered create focuses the new tab (the repo replays createAndFocus semantics), which an
        // unblocked create from here does not - resolving the dialog is the one case where landing on
        // what you just made is the point.
        val request = WorkspaceAction.Create(type, args, allowLimitRecovery = true)
        when (val result = workspaceRepo.execute(request)) {
            is WorkspaceAction.Create.Result.Success -> {
                log(tag) { "Workspace created: ${result.newId}" }
                // A fresh tab has no operations and needs no attention, so an active filter would
                // hide the card that was just asked for.
                clearFilters()
            }
            is WorkspaceAction.Create.Result.AlreadyOpen -> {
                log(tag) { "Singleton already open, focusing existing: ${result.existingId}" }
                workspacePageManager.selectWorkspaceFromManager(result.existingId)
            }
            is WorkspaceAction.Create.Result.LimitReached -> {
                log(tag, WARN) { "Workspace creation blocked - limit reached" }
            }
        }
    }

    fun navigateBack() {
        log(tag) { "navigateBack()" }
        workspacePageManager.hideManagerOverlay()
    }

    fun dismissBadgeExplanation() = launch {
        workspaceSettings.showTipBadgeExplanation.value(false)
    }

    fun closeAllWorkspaces() = launch {
        log(tag) { "closeAllWorkspaces()" }
        workspaceRepo.execute(WorkspaceAction.CloseAll)
    }

    fun startSelection(id: Workspace.Id) {
        log(tag) { "startSelection($id)" }
        selectionFlow.value = setOf(id)
    }

    /** Deselecting the last card leaves selection mode, so the contextual bar never sits at zero. */
    fun toggleSelection(id: Workspace.Id) {
        val current = selectionFlow.value ?: return
        val next = if (current.contains(id)) current - id else current + id
        log(tag) { "toggleSelection($id) -> ${next.size} selected" }
        selectionFlow.value = next.ifEmpty { null }
    }

    /**
     * Selects every open tab. The filters are cleared in the same step, so the selection can never
     * hold a card the grid is hiding: the tabs chip that triggers this shows the UNFILTERED count,
     * and selecting fewer tabs than the number on that chip is the surprise worth avoiding.
     */
    fun selectAllTabs() = launch {
        val all = workspaceRepo.peekStacks().unitOwners.map { it.id }.toSet()
        log(tag) { "selectAllTabs() -> ${all.size}" }
        filterOperationsFlow.value = false
        filterAttentionFlow.value = false
        selectionFlow.value = all.ifEmpty { null }
    }

    fun clearSelection() {
        log(tag) { "clearSelection()" }
        selectionFlow.value = null
    }

    /**
     * Closes every selected tab. The set is captured and cleared synchronously, before any
     * suspension: the confirming dialog dismisses immediately, so a toggle landing between the
     * confirm and this coroutine would otherwise change what gets closed - or be erased by the
     * clear. The repo then re-resolves the captured ids, because a tab can close from another
     * surface while the manager is open and the shared state flow's replay cache can lag that.
     */
    fun closeSelectedWorkspaces() {
        val confirmed = selectionFlow.value.orEmpty()
        selectionFlow.value = null
        if (confirmed.isEmpty()) return
        log(tag) { "closeSelectedWorkspaces() - ${confirmed.size} confirmed" }
        launch { workspaceRepo.execute(WorkspaceAction.CloseSelected(confirmed)) }
    }

    /**
     * Pauses the pausable members of the selection and leaves the rest untouched, reporting what
     * actually happened rather than what looked eligible: [WorkspaceItem.canPause] is eventually
     * consistent and the repo can still refuse. Ids are captured before suspending for the same
     * reason [closeSelectedWorkspaces] does it.
     */
    fun pauseSelectedWorkspaces() {
        val confirmed = selectionFlow.value.orEmpty()
        selectionFlow.value = null
        if (confirmed.isEmpty()) return
        launch {
            val stacks = workspaceRepo.peekStacks()
            val focusedId = workspacePageManager.state.value.focusedWorkspaceId
            val eligible = stacks.unitOwners
                .filter { confirmed.contains(it.id) && it.canBePausedManually(stacks, focusedId) }
                .map { it.id }
            log(tag) { "pauseSelectedWorkspaces() - ${eligible.size} of ${confirmed.size} eligible" }
            var paused = 0
            eligible.forEach { id ->
                val leaseKey = workspaceRepo.peekOwnershipRoot(id)
                val result = workspacePauseGate.withLease(leaseKey) {
                    workspaceRepo.execute(WorkspaceAction.Pause(id))
                }
                if (result is WorkspaceAction.Pause.Result.Success) {
                    paused++
                } else {
                    log(tag, WARN) { "Pausing $id did not succeed: $result" }
                }
            }
            log(tag, INFO) { "pauseSelectedWorkspaces() - paused $paused of ${confirmed.size}" }
        }
    }

    /**
     * Leaves selection mode if it is on, reporting whether it did. Back has to make that decision
     * from the authoritative flow rather than from a collected snapshot of [state], which lags a
     * long-press by a frame and would dismiss the whole manager instead.
     */
    fun clearSelectionIfActive(): Boolean {
        if (selectionFlow.value == null) return false
        log(tag) { "clearSelectionIfActive() - leaving selection mode" }
        selectionFlow.value = null
        return true
    }

    fun onScreenAppeared() {
        log(tag) { "onScreenAppeared() - invalidating focused workspace preview" }
        // Cleared before the launch, not inside it: a reopened manager can accept a long-press
        // before a coroutine gets scheduled, and the reset would then wipe that fresh selection.
        // The manager can be dismissed mid-selection from outside this ViewModel, so a fresh opening
        // resets it here rather than relying on every dismissal path to do so.
        selectionFlow.value = null
        launch { workspacePreviewManager.invalidateFocusedWorkspacePreview() }
    }

    fun toggleOperationsFilter() {
        log(tag) { "toggleOperationsFilter() - current: ${filterOperationsFlow.value}" }
        filterOperationsFlow.value = !filterOperationsFlow.value
    }

    fun toggleAttentionFilter() {
        log(tag) { "toggleAttentionFilter() - current: ${filterAttentionFlow.value}" }
        filterAttentionFlow.value = !filterAttentionFlow.value
    }

    fun clearFilters() {
        log(tag) { "clearFilters()" }
        filterOperationsFlow.value = false
        filterAttentionFlow.value = false
    }

    data class State(
        val workspaces: List<WorkspaceItem> = emptyList(),
        val showBadgeExplanation: Boolean = true,
        val useLivePreview: Boolean = true,
        val operationsCount: Int = 0,
        val attentionCount: Int = 0,
        val currentPaneCount: Int = 1,
        val filterOperations: Boolean = false,
        val filterAttention: Boolean = false,
        val quickCreateItems: List<QuickCreateItem> = emptyList(),
        val hasUnsavedChanges: Boolean = false,
        /** Null when selection mode is off. Pruned to tabs that are still open. */
        val selectedIds: Set<Workspace.Id>? = null,
    ) {
        val workspaceCount: Int = workspaces.size

        val isSelectionActive: Boolean = selectedIds != null

        val selectedCount: Int = selectedIds?.size ?: 0

        /** Whether every open tab is checked. Selecting freezes the filters, so this is unambiguous. */
        val allSelected: Boolean
            get() = selectedIds != null && workspaces.isNotEmpty() &&
                    workspaces.all { selectedIds.contains(it.id) }

        /**
         * How many of the selected tabs can currently be paused. Always short of [selectedCount]
         * after a select-all: the focused tab is never pausable, so "some were skipped" is the
         * normal case rather than the exception.
         */
        val selectionPausableCount: Int = selectedIds
            ?.let { ids -> workspaces.count { ids.contains(it.id) && it.canPause } }
            ?: 0

        val selectionHasUnsavedChanges: Boolean = selectedIds
            ?.let { ids -> workspaces.any { ids.contains(it.id) && it.hasUnsavedChanges } }
            ?: false

        val filteredWorkspaces: List<WorkspaceItem>
            get() {
                // If no filters active, return all workspaces
                if (!filterOperations && !filterAttention) return workspaces

                return workspaces.filter { workspace ->
                    val matchesOperations = !filterOperations || workspace.operationCount > 0
                    val matchesAttention = !filterAttention || workspace.attentionCount > 0
                    matchesOperations && matchesAttention
                }
            }
    }

    /**
     * One card per ownership unit: [id] is the tab everything acts on - close, rename, pause,
     * resume, reorder - while the identity shown is the workspace currently on top of that tab.
     */
    data class WorkspaceItem(
        val id: Workspace.Id,
        /** The workspace whose content the card previews: the top of the stack, else the tab itself. */
        val topId: Workspace.Id,
        val type: Workspace.Type,
        /** The resolved display title: the custom name when set, otherwise the automatic title. */
        val title: CaString,
        val subtitle: CaString?,
        /** The automatic title, shown in the card's info bar and as the rename dialog's placeholder. */
        val autoTitle: CaString,
        val isFocused: Boolean = false,
        /** True when a pane currently renders this tab. Unrelated to multi-select. */
        val isVisibleInPane: Boolean = false,
        val paneNumber: Int? = null,
        val operationCount: Int = 0,
        val attentionCount: Int = 0,
        /**
         * True when any member of the unit holds unsaved in-memory changes. Deliberately separate
         * from [attentionCount]: editing without having saved yet is a normal working state, not a
         * fault, so it must not glow like one.
         */
        val hasUnsavedChanges: Boolean = false,
        val customTitle: String? = null,
        /**
         * A stacked workspace only ever gets a card of its own when its ownership cannot be resolved
         * ([isRecovery]). Sub-workspaces are excluded from session persistence, so renaming one would
         * silently not survive a restart - such a card hides its rename affordance.
         */
        val isSubWorkspace: Boolean = false,
        /**
         * True for a card standing in for an unresolvable ownership component. It has no tab to
         * place, so selecting it would focus something no pane renders - the card only offers Close.
         */
        val isRecovery: Boolean = false,
        val isPaused: Boolean = false,
        val canPause: Boolean = false,
        /** How many sub-workspaces are stacked on this tab; 0 for a plain tab. Drives the stack badge. */
        val stackDepth: Int = 0,
    )
}
