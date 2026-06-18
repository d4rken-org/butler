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
) {
    @Parcelize
    @TypeParceler<Instant, InstantParceler>
    data class State(
        val focusedWorkspaceId: Workspace.Id? = null,
        val selectedWorkspaces: Map<Int, Workspace.Id> = emptyMap(),
        val currentPaneCount: Int = 1,
        val workspaceAccessTimes: Map<Workspace.Id, Instant> = emptyMap(),
        val isManagerOverlayVisible: Boolean = false,
    ) : Parcelable

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
                        handleWorkspaceCreated(event.workspaceId, event.replacedId, event.autoFocus)
                    }

                    is WorkspaceEvent.Closed -> {
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
                        handleWorkspaceSelection(event.workspaceId)
                    }

                    is WorkspaceEvent.Reordered -> {
                        log(TAG) { "Workspaces reordered: ${event.workspaceIds}" }
                    }

                    WorkspaceEvent.AllClosed -> {
                        log(TAG) { "All workspaces closed" }
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

    suspend fun handleWorkspaceSelection(workspaceId: Workspace.Id) {
        log(TAG) { "handleWorkspaceSelection: workspaceId=$workspaceId" }

        // Check if this is a sub-workspace (modal) - they only get focus, not pane assignment.
        // Wait for the workspace to appear in state before checking isSubWorkspace to avoid
        // acting on stale state (new workspaces may not have emitted their info flow yet).
        val workspaceInfo = workspaceRemote.state
            .first { repoState -> repoState.infos.any { it.id == workspaceId } }
            .infos.find { it.id == workspaceId }
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
            val existingPosition = currentState.selectedWorkspaces.entries.find { it.value == workspaceId }?.key

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
                // Workspace not selected, assign it to an empty pane or replace current selection
                val paneCount = currentState.currentPaneCount
                val currentSelections = currentState.selectedWorkspaces

                val newSelections = if (paneCount > 1) {
                    // Multi-pane mode: find empty pane
                    val emptyPaneIndex = (0 until paneCount).firstOrNull { paneIndex ->
                        !currentSelections.containsKey(paneIndex)
                    }

                    if (emptyPaneIndex != null) {
                        log(TAG) { "Assigned workspace $workspaceId to empty pane $emptyPaneIndex" }
                        currentSelections + (emptyPaneIndex to workspaceId)
                    } else {
                        log(TAG) { "All panes full, replaced pane 0 with workspace $workspaceId" }
                        currentSelections + (0 to workspaceId)
                    }
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

    private suspend fun handleWorkspaceCreated(workspaceId: Workspace.Id, replacedId: Workspace.Id?, autoFocus: Boolean) {
        log(TAG) { "handleWorkspaceCreated: workspaceId=$workspaceId, replacedId=$replacedId, autoFocus=$autoFocus" }

        // Wait until workspace is reflected in state for accurate isSubWorkspace check.
        // Sub-workspaces render as modal overlays and must never be assigned to a pane.
        val workspaceInfo = workspaceRemote.state
            .first { repoState -> repoState.infos.any { it.id == workspaceId } }
            .infos.find { it.id == workspaceId }

        if (workspaceInfo?.isSubWorkspace == true) {
            log(TAG) { "Sub-workspace $workspaceId created, skipping pane assignment" }
            _state.update { it.copy(workspaceAccessTimes = it.workspaceAccessTimes + (workspaceId to Clock.System.now())) }
            return
        }

        _state.update { currentState ->
            // Update MRU timestamp for newly created workspace
            val updatedAccessTimes = currentState.workspaceAccessTimes + (workspaceId to Clock.System.now())

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
                    assignToEmptyPaneInternal(currentState, workspaceId)
                        .copy(workspaceAccessTimes = updatedAccessTimes)
                }
            } else {
                // New workspace, not a replacement
                if (!currentState.selectedWorkspaces.containsValue(workspaceId)) {
                    log(TAG) { "New workspace $workspaceId, assigning to empty pane (autoFocus=$autoFocus)" }
                    val newState = assignToEmptyPaneInternal(currentState, workspaceId)
                        .copy(workspaceAccessTimes = updatedAccessTimes)

                    // Auto-focus if requested or if no workspace is focused
                    if (autoFocus) {
                        log(TAG) { "Auto-focusing new workspace $workspaceId" }
                        // In single-pane mode, switch to the new workspace
                        val newSelections = if (currentState.currentPaneCount == 1) {
                            mapOf(0 to workspaceId)
                        } else {
                            newState.selectedWorkspaces
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

    private fun assignToEmptyPaneInternal(currentState: State, workspaceId: Workspace.Id): State {
        val paneCount = currentState.currentPaneCount
        val currentSelections = currentState.selectedWorkspaces
        log(TAG) { "assignToEmptyPane: Pane count=$paneCount, workspace=$workspaceId" }

        val newSelections = if (paneCount > 1) {
            // Find first empty pane
            val emptyPaneIndex = (0 until paneCount).firstOrNull { paneIndex ->
                !currentSelections.containsKey(paneIndex)
            }
            log(TAG) { "assignToEmptyPane: Empty pane index=$emptyPaneIndex" }

            if (emptyPaneIndex != null) {
                // Assign to empty pane
                log(TAG) { "assignToEmptyPane: Assigned to empty pane $emptyPaneIndex" }
                currentSelections + (emptyPaneIndex to workspaceId)
            } else {
                // All panes full, don't select the new workspace
                log(TAG) { "assignToEmptyPane: All panes full, workspace created but not selected" }
                currentSelections
            }
        } else {
            // Single pane mode
            if (currentSelections.isEmpty()) {
                // No workspace selected, assign to pane 0
                log(TAG) { "assignToEmptyPane: Single pane mode, assigned to pane 0" }
                mapOf(0 to workspaceId)
            } else {
                // Pane 0 already occupied, don't select the new workspace
                log(TAG) { "assignToEmptyPane: Single pane mode, pane 0 occupied, workspace created but not selected" }
                currentSelections
            }
        }

        return currentState.copy(selectedWorkspaces = newSelections)
    }

    companion object {
        private val TAG = logTag("Workspace", "UIManager")
    }
}
