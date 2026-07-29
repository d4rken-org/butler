package eu.darken.butler.workspace.ui

import android.os.Parcelable
import androidx.lifecycle.SavedStateHandle
import eu.darken.butler.common.coroutine.AppScope
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.debug.logging.logTag
import eu.darken.butler.common.parcel.InstantParceler
import eu.darken.butler.workspace.core.Workspace
import eu.darken.butler.workspace.core.WorkspaceEvent
import eu.darken.butler.workspace.core.WorkspaceRemote
import eu.darken.butler.workspace.core.WorkspaceStacks
import eu.darken.butler.workspace.ui.floatingbar.WorkspaceBarCollapseStates
import eu.darken.butler.workspace.ui.scroll.WorkspaceScrollPositions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.parcelize.Parcelize
import kotlinx.parcelize.TypeParceler
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Clock
import kotlin.time.Instant

@Singleton
class WorkspacePageManager @Inject constructor(
    @AppScope private val appScope: CoroutineScope,
    private val workspaceRemote: WorkspaceRemote,
    private val scrollPositions: WorkspaceScrollPositions,
    private val barCollapseStates: WorkspaceBarCollapseStates,
) {
    @Parcelize
    @TypeParceler<Instant, InstantParceler>
    data class State(
        val focusedWorkspaceId: Workspace.Id? = null,
        val selectedWorkspaces: Map<Int, Workspace.Id> = emptyMap(),
        val currentPaneCount: Int = 1,
        val workspaceAccessTimes: Map<Workspace.Id, Instant> = emptyMap(),
        val isManagerOverlayVisible: Boolean = false,
    ) : Parcelable {

        /**
         * The pane assignments the current layout can actually show.
         *
         * [selectedWorkspaces] deliberately outlives layout changes: [setPaneCount] lowers
         * [currentPaneCount] without pruning, so collapsing quad -> dual and expanding back restores
         * the arrangement instead of losing it. The price is that the raw map keeps indices no pane
         * renders, and a workspace parked on one is open but invisible.
         *
         * Anything answering "which pane is this workspace in" *for the user* - pane badges, pane
         * chips, whether a workspace counts as on-screen - must read this rather than the raw map,
         * or it will advertise a pane the layout does not have. Assignment and session persistence
         * still work on [selectedWorkspaces]; narrowing those would discard the retention above.
         */
        val visiblePaneAssignments: Map<Int, Workspace.Id>
            get() = selectedWorkspaces.filterKeys { it in 0 until currentPaneCount }
    }

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    private val _selectionEvents = MutableSharedFlow<Workspace.Id>()
    val selectionEvents = _selectionEvents.asSharedFlow()

    init {
        // Handle workspace events
        workspaceRemote.events
            .onEach { event ->
                log(TAG) { "Workspace event received: $event" }
                when (event) {
                    is WorkspaceEvent.Created -> {
                        handleWorkspaceCreated(
                            workspaceId = event.workspaceId,
                            replacedId = event.replacedId,
                            autoFocus = event.autoFocus,
                            sourceWorkspaceId = event.sourceWorkspaceId,
                        )
                    }

                    is WorkspaceEvent.Closed -> {
                        scrollPositions.forget(event.workspaceId)
                        barCollapseStates.forget(event.workspaceId)
                        handleWorkspaceClosed(event.workspaceId, event.callerWorkspaceId)
                    }

                    is WorkspaceEvent.ResultEvent -> {
                        // Handled by individual workspaces, UI manager ignores it
                    }

                    is WorkspaceEvent.BatchCreationCompleted -> {
                        // Handled by WorkspacesViewModel for banner feedback
                    }

                    is WorkspaceEvent.SelectionRequested -> {
                        log(TAG) { "Selection requested for workspace: ${event.workspaceId}" }
                        handleWorkspaceSelection(event.workspaceId, event.sourceWorkspaceId)
                    }

                    is WorkspaceEvent.Reordered -> {
                        log(TAG) { "Workspaces reordered: ${event.workspaceIds}" }
                    }

                    is WorkspaceEvent.Renamed -> {
                        log(TAG) { "Workspace ${event.workspaceId} renamed to ${event.customTitle}" }
                    }

                    WorkspaceEvent.AllClosed -> {
                        log(TAG) { "All workspaces closed" }
                        scrollPositions.clear()
                        barCollapseStates.clear()
                        _state.update {
                            it.copy(
                                focusedWorkspaceId = null,
                                selectedWorkspaces = emptyMap()
                            )
                        }
                    }
                }
            }
            .launchIn(appScope)

        // Clean up stale workspace IDs that no longer exist
        workspaceRemote.state
            .onEach { repoState ->
                val validWorkspaceIds = repoState.infos.map { it.id }.toSet()
                val currentState = _state.value
                val cleanedFocusedId = currentState.focusedWorkspaceId?.takeIf { it in validWorkspaceIds }
                val cleanedSelectedIds = currentState.selectedWorkspaces.filterValues { it in validWorkspaceIds }

                // Update state if cleanup removed any IDs.
                // Note: this only clears stale IDs; it never picks a replacement focus. Re-focusing a
                // surviving workspace after a close is owned solely by handleWorkspaceClosed(), which is
                // the only path that knows the closing workspace's callerWorkspaceId (for picker return).
                if (cleanedFocusedId != currentState.focusedWorkspaceId || cleanedSelectedIds != currentState.selectedWorkspaces) {
                    _state.update {
                        it.copy(
                            focusedWorkspaceId = cleanedFocusedId,
                            selectedWorkspaces = cleanedSelectedIds
                        )
                    }
                }
            }
            .launchIn(appScope)
    }

    /**
     * Initialize state from SavedStateHandle for persistence
     */
    fun initializeFromSavedState(savedStateHandle: SavedStateHandle) {
        val savedState = savedStateHandle.get<State>("workspaceUIState")
        if (savedState != null) {
            _state.value = savedState
        }
    }

    fun showManagerOverlay() {
        log(TAG) { "showManagerOverlay()" }
        _state.update { it.copy(isManagerOverlayVisible = true) }
    }

    fun hideManagerOverlay() {
        log(TAG) { "hideManagerOverlay()" }
        _state.update { it.copy(isManagerOverlayVisible = false) }
    }

    suspend fun selectWorkspaceFromManager(workspaceId: Workspace.Id) {
        log(TAG) { "selectWorkspaceFromManager: $workspaceId" }
        _selectionEvents.emit(workspaceId)
        handleWorkspaceSelection(workspaceId)
    }

    suspend fun handleWorkspaceSelection(
        workspaceId: Workspace.Id,
        sourceWorkspaceId: Workspace.Id? = null,
    ) {
        log(TAG) { "handleWorkspaceSelection: workspaceId=$workspaceId, sourceWorkspaceId=$sourceWorkspaceId" }

        // Check if this is a sub-workspace (modal) - they only get focus, not pane assignment.
        // Wait for the workspace to appear in state before checking isSubWorkspace to avoid
        // acting on stale state (new workspaces may not have emitted their info flow yet).
        val repoInfos = workspaceRemote.state
            .first { repoState -> repoState.infos.any { it.id == workspaceId } }
            .infos
        val workspaceInfo = repoInfos.find { it.id == workspaceId }
        val isSubWorkspace = workspaceInfo?.isSubWorkspace == true

        if (isSubWorkspace) {
            log(TAG) { "Sub-workspace selected, only updating focus (not pane selections)" }
            _state.update { state ->
                state.copy(
                    focusedWorkspaceId = workspaceId,
                    workspaceAccessTimes = state.workspaceAccessTimes + (workspaceId to Clock.System.now()),
                )
            }
            return
        }

        _state.update { currentState ->
            // Only a pane the layout renders counts as "already there". A workspace parked on an
            // index left behind by a wider layout is invisible, so selecting it has to move it onto
            // screen rather than just focus something the user cannot see.
            val existingPosition = currentState.visiblePaneAssignments.entries
                .find { it.value == workspaceId }?.key

            // Update MRU timestamp
            val updatedAccessTimes = currentState.workspaceAccessTimes + (workspaceId to Clock.System.now())

            if (existingPosition != null) {
                // Workspace already selected, just focus it
                log(TAG) { "Workspace $workspaceId already selected in pane $existingPosition, focusing it" }
                currentState.copy(
                    focusedWorkspaceId = workspaceId,
                    workspaceAccessTimes = updatedAccessTimes,
                )
            } else {
                // Workspace not selected, assign it to a pane or replace current selection.
                val newSelections = if (currentState.currentPaneCount > 1) {
                    // An explicit selection always focuses, and a focused workspace that occupies no
                    // pane is invisible, so this path may evict.
                    assignPane(
                        currentState = currentState,
                        workspaceId = workspaceId,
                        sourcePaneIndex = currentState.paneOf(sourceWorkspaceId, repoInfos),
                        allowEviction = true,
                    )
                } else {
                    // Single pane mode: replace current selection
                    log(TAG) { "Single pane mode, selected workspace $workspaceId" }
                    mapOf(0 to workspaceId)
                }

                currentState.copy(
                    focusedWorkspaceId = workspaceId,
                    selectedWorkspaces = newSelections,
                    workspaceAccessTimes = updatedAccessTimes,
                )
            }
        }
    }

    fun setFocusedWorkspace(workspaceId: Workspace.Id?) {
        _state.update { currentState ->
            if (workspaceId == null || currentState.selectedWorkspaces.values.contains(workspaceId)) {
                // Update MRU timestamp when focusing a workspace
                val updatedAccessTimes = if (workspaceId != null) {
                    currentState.workspaceAccessTimes + (workspaceId to Clock.System.now())
                } else {
                    currentState.workspaceAccessTimes
                }
                currentState.copy(
                    focusedWorkspaceId = workspaceId,
                    workspaceAccessTimes = updatedAccessTimes,
                )
            } else {
                currentState
            }
        }
    }

    suspend fun setPaneCount(count: Int) {
        log(TAG) { "Setting pane count to $count" }

        val currentState = _state.value
        val oldPaneCount = currentState.currentPaneCount

        // Update pane count first
        _state.update { it.copy(currentPaneCount = count) }

        // Auto-fill empty panes if pane count increased
        if (count > oldPaneCount) {
            log(TAG) { "Pane count increased from $oldPaneCount to $count, checking for empty panes to fill" }

            val currentSelections = currentState.selectedWorkspaces
            val emptyPaneIndices = (0 until count).filter { paneIndex ->
                !currentSelections.containsKey(paneIndex)
            }

            if (emptyPaneIndices.isNotEmpty()) {
                log(TAG) { "Found ${emptyPaneIndices.size} empty pane(s): $emptyPaneIndices" }

                // Get all available workspaces
                val allWorkspaces = workspaceRemote.state.first().infos

                // Filter: exclude currently selected workspaces and modal workspaces
                val selectedIds = currentSelections.values.toSet()
                val availableWorkspaces = allWorkspaces.filter { workspace ->
                    !selectedIds.contains(workspace.id) && !workspace.isSubWorkspace
                }

                if (availableWorkspaces.isNotEmpty()) {
                    log(TAG) { "Found ${availableWorkspaces.size} available workspace(s)" }

                    // Sort by MRU (most recent first)
                    val sortedByMru = availableWorkspaces.sortedByDescending { workspace ->
                        currentState.workspaceAccessTimes[workspace.id] ?: Instant.DISTANT_PAST
                    }

                    // Assign MRU workspaces to empty panes
                    val newSelections = currentSelections.toMutableMap()
                    var autoFocusId: Workspace.Id? = null

                    emptyPaneIndices.zip(sortedByMru).forEach { (paneIndex, workspace) ->
                        log(TAG) { "Auto-filling pane $paneIndex with MRU workspace ${workspace.id}" }
                        newSelections[paneIndex] = workspace.id
                        if (autoFocusId == null) {
                            autoFocusId = workspace.id
                        }
                    }

                    // Update state with new selections
                    _state.update { state ->
                        state.copy(
                            selectedWorkspaces = newSelections,
                            // Auto-focus first auto-filled workspace if nothing is focused
                            focusedWorkspaceId = state.focusedWorkspaceId ?: autoFocusId,
                        )
                    }

                    log(TAG) { "Auto-filled ${emptyPaneIndices.size} pane(s)" }
                } else {
                    log(TAG) { "No available workspaces to auto-fill empty panes" }
                }
            } else {
                log(TAG) { "No empty panes to fill" }
            }
        }
    }

    fun toggleWorkspaceSelection(workspaceId: Workspace.Id, position: Int? = null) {
        _state.update { currentState ->
            val current = currentState.selectedWorkspaces
            val existingPosition = current.entries.find { it.value == workspaceId }?.key

            val newSelections = if (existingPosition != null) {
                // Remove from selection
                current - existingPosition
            } else {
                // Add to selection
                val targetPosition = position ?: current.keys.maxOrNull()?.plus(1) ?: 0
                current + (targetPosition to workspaceId)
            }

            // Update focus if needed
            val newFocus = when {
                newSelections.isEmpty() -> null
                !newSelections.values.contains(currentState.focusedWorkspaceId) -> newSelections.values.first()
                else -> currentState.focusedWorkspaceId
            }

            currentState.copy(
                selectedWorkspaces = newSelections,
                focusedWorkspaceId = newFocus
            )
        }
    }

    fun setSelectedWorkspaces(selections: Map<Int, Workspace.Id>) {
        _state.update { currentState ->
            // Auto-focus first selected if current focus not in selection
            val newFocus =
                if (currentState.focusedWorkspaceId == null || !selections.values.contains(currentState.focusedWorkspaceId)) {
                    selections.values.firstOrNull()
                } else {
                    currentState.focusedWorkspaceId
                }

            currentState.copy(
                selectedWorkspaces = selections,
                focusedWorkspaceId = newFocus
            )
        }
    }

    /**
     * Atomically applies a pane layout: selections and focus in a single state update, with one
     * MRU stamp for the focused workspace. Replaces the setFocusedWorkspace()+setSelectedWorkspaces()
     * two-step, whose ordering either dropped the MRU stamp (focus target not yet selected, so
     * setFocusedWorkspace no-ops) or dropped the focus change entirely (old focus still among the
     * new selections, so setSelectedWorkspaces keeps it). Intermediate emissions between the two
     * steps also fed pager/focus races in the UI.
     *
     * For normal (pane-rendered) workspaces only — sub-workspace focus goes through
     * [handleWorkspaceSelection], which focuses without pane assignment.
     */
    fun setLayout(selections: Map<Int, Workspace.Id>, focusedId: Workspace.Id?) {
        _state.update { currentState ->
            val newFocus = focusedId?.takeIf { selections.containsValue(it) }
                ?: currentState.focusedWorkspaceId?.takeIf { selections.containsValue(it) }
                ?: selections.values.firstOrNull()
            log(TAG) { "setLayout: selections=$selections, requestedFocus=$focusedId, newFocus=$newFocus" }

            val updatedAccessTimes = if (newFocus != null) {
                currentState.workspaceAccessTimes + (newFocus to Clock.System.now())
            } else {
                currentState.workspaceAccessTimes
            }

            currentState.copy(
                selectedWorkspaces = selections,
                focusedWorkspaceId = newFocus,
                workspaceAccessTimes = updatedAccessTimes,
            )
        }
    }

    /**
     * Atomically sets both focus and selections during session restoration.
     * Unlike calling setFocusedWorkspace() + setSelectedWorkspaces() separately,
     * this avoids the auto-focus side effect in setSelectedWorkspaces().
     */
    fun applyRestoredUIState(
        focusedId: Workspace.Id?,
        selectedWorkspaces: Map<Int, Workspace.Id>,
    ) {
        _state.update { currentState ->
            val updatedAccessTimes = if (focusedId != null) {
                currentState.workspaceAccessTimes + (focusedId to Clock.System.now())
            } else {
                currentState.workspaceAccessTimes
            }
            currentState.copy(
                focusedWorkspaceId = focusedId,
                selectedWorkspaces = selectedWorkspaces,
                workspaceAccessTimes = updatedAccessTimes,
            )
        }
    }

    private suspend fun handleWorkspaceCreated(
        workspaceId: Workspace.Id,
        replacedId: Workspace.Id?,
        autoFocus: Boolean,
        sourceWorkspaceId: Workspace.Id?,
    ) {
        log(TAG) {
            "handleWorkspaceCreated: workspaceId=$workspaceId, replacedId=$replacedId, " +
                "autoFocus=$autoFocus, sourceWorkspaceId=$sourceWorkspaceId"
        }

        // A replace (e.g. the Templates tile morphing a tab into an Explorer) retires the old
        // workspace without ever emitting Closed, so its view state has to be dropped here.
        if (replacedId != null && replacedId != workspaceId) {
            scrollPositions.forget(replacedId)
            barCollapseStates.forget(replacedId)
        }

        // Wait until workspace is reflected in state for accurate isSubWorkspace check.
        // Sub-workspaces render as modal overlays and must never be assigned to a pane.
        val repoInfos = workspaceRemote.state
            .first { repoState -> repoState.infos.any { it.id == workspaceId } }
            .infos
        val workspaceInfo = repoInfos.find { it.id == workspaceId }

        if (workspaceInfo?.isSubWorkspace == true) {
            log(TAG) { "Sub-workspace $workspaceId created, skipping pane assignment" }
            _state.update { it.copy(workspaceAccessTimes = it.workspaceAccessTimes + (workspaceId to Clock.System.now())) }
            return
        }

        _state.update { currentState ->
            // Update MRU timestamp for newly created workspace
            val updatedAccessTimes = currentState.workspaceAccessTimes + (workspaceId to Clock.System.now())
            val sourcePaneIndex = currentState.paneOf(sourceWorkspaceId, repoInfos)

            if (replacedId != null) {
                // This is a replacement
                val replacedPaneIndex = currentState.selectedWorkspaces.entries.find { it.value == replacedId }?.key

                if (replacedPaneIndex != null) {
                    log(TAG) { "Replacing workspace $replacedId at pane $replacedPaneIndex with $workspaceId" }
                    val newSelections = currentState.selectedWorkspaces + (replacedPaneIndex to workspaceId)

                    // Transfer focus if the replaced workspace was focused, or if autoFocus is requested
                    val newFocus = if (currentState.focusedWorkspaceId == replacedId || autoFocus) {
                        log(TAG) { "Transferring focus from $replacedId to $workspaceId (autoFocus=$autoFocus)" }
                        workspaceId
                    } else {
                        currentState.focusedWorkspaceId
                    }

                    currentState.copy(
                        selectedWorkspaces = newSelections,
                        focusedWorkspaceId = newFocus,
                        workspaceAccessTimes = updatedAccessTimes,
                    )
                } else {
                    log(TAG) { "Replaced workspace $replacedId was not in any pane, treating as new workspace" }
                    assignToEmptyPaneInternal(currentState, workspaceId, sourcePaneIndex)
                        .copy(workspaceAccessTimes = updatedAccessTimes)
                }
            } else {
                // New workspace, not a replacement
                if (!currentState.selectedWorkspaces.containsValue(workspaceId)) {
                    log(TAG) { "New workspace $workspaceId, assigning to empty pane (autoFocus=$autoFocus)" }
                    val newState = assignToEmptyPaneInternal(currentState, workspaceId, sourcePaneIndex)
                        .copy(workspaceAccessTimes = updatedAccessTimes)

                    // Auto-focus if requested or if no workspace is focused
                    if (autoFocus) {
                        log(TAG) { "Auto-focusing new workspace $workspaceId" }
                        // A focused workspace with no pane is invisible, so this branch - unlike the
                        // background creates above - may evict to make room for it.
                        val newSelections = when {
                            currentState.currentPaneCount == 1 -> mapOf(0 to workspaceId)
                            else -> assignPane(
                                currentState = currentState,
                                workspaceId = workspaceId,
                                sourcePaneIndex = sourcePaneIndex,
                                allowEviction = true,
                            )
                        }
                        newState.copy(
                            focusedWorkspaceId = workspaceId,
                            selectedWorkspaces = newSelections,
                        )
                    } else if (newState.focusedWorkspaceId == null) {
                        log(TAG) { "No focused workspace, setting focus to $workspaceId" }
                        newState.copy(focusedWorkspaceId = workspaceId)
                    } else {
                        newState
                    }
                } else {
                    log(TAG) { "Workspace $workspaceId already assigned to a pane" }
                    currentState.copy(workspaceAccessTimes = updatedAccessTimes)
                }
            }
        }
    }

    private suspend fun handleWorkspaceClosed(workspaceId: Workspace.Id, callerWorkspaceId: Workspace.Id?) {
        log(TAG) { "handleWorkspaceClosed: workspaceId=$workspaceId, callerWorkspaceId=$callerWorkspaceId" }

        val currentState = _state.value
        val wasSelected = currentState.selectedWorkspaces.values.contains(workspaceId)
        val wasFocused = currentState.focusedWorkspaceId == workspaceId

        log(TAG) { "handleWorkspaceClosed: wasSelected=$wasSelected, wasFocused=$wasFocused" }

        // Read the repo snapshot BEFORE entering the update block (suspending call).
        val repoSnapshot = workspaceRemote.state.first()

        // Replacement focus candidates: normal (non-sub) workspaces, excluding the closing one.
        // Exclude it explicitly because the exported state flow may still replay a pre-removal
        // snapshot that contains it, and since it was just focused it holds the latest access time,
        // so MRU would otherwise select the very tab being closed (focus -> null).
        val availableWorkspaces = repoSnapshot.infos
            .filter { !it.isSubWorkspace && it.id != workspaceId }
        log(TAG) { "handleWorkspaceClosed: availableWorkspaces=${availableWorkspaces.size}" }

        // Validity set for the CURRENT focus: all live workspaces (incl. sub-workspaces) minus the
        // closing one. A focused modal sub-workspace is still valid focus, so it must not be treated
        // as stranded when some unrelated workspace closes.
        val liveIds = repoSnapshot.infos.map { it.id }.filter { it != workspaceId }.toSet()

        // Prefer returning to caller workspace, fall back to MRU
        val callerWorkspace = callerWorkspaceId?.let { callerId ->
            availableWorkspaces.find { it.id == callerId }
        }
        val mruWorkspace = availableWorkspaces.maxByOrNull {
            currentState.workspaceAccessTimes[it.id] ?: Instant.DISTANT_PAST
        }
        val nextWorkspace = callerWorkspace ?: mruWorkspace
        log(TAG) { "handleWorkspaceClosed: callerWorkspace=${callerWorkspace?.id}, mruWorkspace=${mruWorkspace?.id}, nextWorkspace=${nextWorkspace?.id}" }

        _state.update { state ->
            log(TAG) { "handleWorkspaceClosed.update: state.selectedWorkspaces=${state.selectedWorkspaces}" }

            // Re-focus when the closed workspace held focus, or when focus is already stranded — the
            // latter covers the race where the cleanup observer nulled/invalidated focus before this
            // event arrived. Without it, a stranded null focus lets the pager settle on the trailing
            // placeholder page, which auto-creates a new workspace. A null focus only counts as
            // stranded when a normal workspace survives to receive it.
            val focusedId = state.focusedWorkspaceId
            val focusStranded = if (focusedId == null) {
                availableWorkspaces.isNotEmpty()
            } else {
                focusedId !in liveIds
            }
            val needsRefocus = wasFocused || focusStranded
            log(TAG) { "handleWorkspaceClosed.update: focusStranded=$focusStranded, needsRefocus=$needsRefocus" }

            // Remove closed workspace from selection
            val position = state.selectedWorkspaces.entries.find { it.value == workspaceId }?.key
            val newSelections = if (position != null) {
                state.selectedWorkspaces - position
            } else {
                state.selectedWorkspaces
            }

            val newFocus = if (needsRefocus) nextWorkspace?.id else state.focusedWorkspaceId

            // Ensure we have a selection if we have a focus
            val finalSelections = if (newFocus != null && newSelections.isEmpty()) {
                mapOf(0 to newFocus)
            } else {
                newSelections
            }

            log(TAG) { "handleWorkspaceClosed.update: newFocus=$newFocus, finalSelections=$finalSelections" }

            state.copy(
                selectedWorkspaces = finalSelections,
                focusedWorkspaceId = newFocus,
            )
        }
    }

    /**
     * Placement for creates that did not ask for focus: batch "open in new tabs", session-restore
     * registration and the tab manager's create path all land here. They deliberately never request
     * selection, so evicting for them would let a background create replace visible content while
     * focus stays elsewhere - all panes full means the workspace stays reachable from the rail only.
     */
    private fun assignToEmptyPaneInternal(
        currentState: State,
        workspaceId: Workspace.Id,
        sourcePaneIndex: Int?,
    ): State {
        val paneCount = currentState.currentPaneCount
        log(TAG) { "assignToEmptyPane: Pane count=$paneCount, workspace=$workspaceId" }

        val newSelections = if (paneCount > 1) {
            assignPane(
                currentState = currentState,
                workspaceId = workspaceId,
                sourcePaneIndex = sourcePaneIndex,
                allowEviction = false,
            )
        } else {
            // Single pane mode
            if (currentState.selectedWorkspaces.isEmpty()) {
                // No workspace selected, assign to pane 0
                log(TAG) { "assignToEmptyPane: Single pane mode, assigned to pane 0" }
                mapOf(0 to workspaceId)
            } else {
                // Pane 0 already occupied, don't select the new workspace
                log(TAG) { "assignToEmptyPane: Single pane mode, pane 0 occupied, workspace created but not selected" }
                currentState.selectedWorkspaces
            }
        }

        return currentState.copy(selectedWorkspaces = newSelections)
    }

    /**
     * Where a workspace goes when it isn't already in a rendered pane: the empty pane next to the one
     * it was invoked from, else any empty pane by ascending index, else - only when [allowEviction] -
     * the least-recently-used pane other than the invoking one, so the list the user acted from
     * survives. Multi-pane only; single-pane placement is trivial and stays at the call sites.
     */
    private fun assignPane(
        currentState: State,
        workspaceId: Workspace.Id,
        sourcePaneIndex: Int?,
        allowEviction: Boolean,
    ): Map<Int, Workspace.Id> {
        val paneCount = currentState.currentPaneCount
        // Drop any hidden assignment it still holds first, or it would occupy two panes at once.
        // Other workspaces' retained assignments are left alone.
        val currentSelections = currentState.selectedWorkspaces.filterValues { it != workspaceId }

        val emptyPaneIndex = paneSearchOrder(paneCount, sourcePaneIndex)
            .firstOrNull { !currentSelections.containsKey(it) }
        if (emptyPaneIndex != null) {
            log(TAG) { "assignPane: $workspaceId -> empty pane $emptyPaneIndex (source pane $sourcePaneIndex)" }
            return currentSelections + (emptyPaneIndex to workspaceId)
        }

        if (!allowEviction) {
            log(TAG) { "assignPane: All panes full, $workspaceId created but not selected" }
            return currentSelections
        }

        fun lastUsed(paneIndex: Int): Instant {
            val occupant = currentSelections[paneIndex] ?: return Instant.DISTANT_PAST
            return currentState.workspaceAccessTimes[occupant] ?: Instant.DISTANT_PAST
        }

        val victim = (0 until paneCount)
            .filter { it != sourcePaneIndex }
            // Ties (never visited, or two stamps in the same instant) resolve to the lowest index.
            .minWithOrNull(compareBy<Int> { lastUsed(it) }.thenBy { it })
        if (victim == null) {
            log(TAG) { "assignPane: All panes full and none evictable, $workspaceId stays unassigned" }
            return currentSelections
        }

        log(TAG) { "assignPane: All panes full, evicting LRU pane $victim for $workspaceId" }
        return currentSelections + (victim to workspaceId)
    }

    /**
     * The pane [workspaceId] acts from, resolved through its ownership root: a modal child occupies
     * no pane of its own, so the raw id would find nothing and eviction would fail to protect the
     * tab the user is actually working in. Null when there is no source or it is off screen.
     */
    private fun State.paneOf(workspaceId: Workspace.Id?, infos: List<Workspace.Info>): Int? {
        val rootId = workspaceId?.let { WorkspaceStacks(infos).rootOf(it)?.id } ?: return null
        return visiblePaneAssignments.entries.find { it.value == rootId }?.key
    }

    companion object {
        private val TAG = logTag("Workspace", "UIManager")

        /**
         * Neighbour panes per index, nearest first. Explicit rather than index±1: the column-based
         * layouts fill column by column (see [eu.darken.butler.workspace.ui.manager.WorkspaceDesign.forPane]),
         * so in a quad grid pane 1 borders 0 and 3 while 2 sits diagonally across. Narrower layouts
         * use the same table truncated to their pane count.
         */
        private val PANE_NEIGHBOURS = mapOf(
            0 to listOf(1, 2),
            1 to listOf(0, 3),
            2 to listOf(0, 3),
            3 to listOf(1, 2),
        )

        /** Panes to try, neighbours of [sourcePaneIndex] first, then everything else ascending. */
        internal fun paneSearchOrder(paneCount: Int, sourcePaneIndex: Int?): List<Int> {
            val all = (0 until paneCount).toList()
            if (sourcePaneIndex == null) return all
            val neighbours = PANE_NEIGHBOURS[sourcePaneIndex].orEmpty().filter { it < paneCount }
            return neighbours + all.filterNot { it in neighbours }
        }
    }
}
