package eu.darken.butler.workspace.ui

import android.os.Parcelable
import androidx.lifecycle.SavedStateHandle
import eu.darken.butler.common.coroutine.AppScope
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.debug.logging.logTag
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
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class WorkspacePageManager @Inject constructor(
    @AppScope private val appScope: CoroutineScope,
    private val workspaceRemote: WorkspaceRemote,
) {
    @Parcelize
    data class State(
        val focusedWorkspaceId: Workspace.Id? = null,
        val selectedWorkspaces: Map<Int, Workspace.Id> = emptyMap(),
        val currentPaneCount: Int = 1,
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
                        handleWorkspaceCreated(event.workspaceId, event.replacedId)
                    }
                    is WorkspaceEvent.Closed -> {
                        handleWorkspaceClosed(event.workspaceId)
                    }
                    is WorkspaceEvent.ResultEvent -> {
                        // Handled by individual workspaces, UI manager ignores it
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

                // Update state if cleanup removed any IDs
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

    suspend fun selectWorkspaceFromManager(workspaceId: Workspace.Id) {
        log(TAG) { "selectWorkspaceFromManager: $workspaceId" }
        _selectionEvents.emit(workspaceId)
        handleWorkspaceSelection(workspaceId)
    }

    fun handleWorkspaceSelection(workspaceId: Workspace.Id) {
        log(TAG) { "handleWorkspaceSelection: workspaceId=$workspaceId" }

        _state.update { currentState ->
            val existingPosition = currentState.selectedWorkspaces.entries.find { it.value == workspaceId }?.key

            if (existingPosition != null) {
                // Workspace already selected, just focus it
                log(TAG) { "Workspace $workspaceId already selected in pane $existingPosition, focusing it" }
                currentState.copy(focusedWorkspaceId = workspaceId)
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
                    selectedWorkspaces = newSelections
                )
            }
        }
    }

    fun setFocusedWorkspace(workspaceId: Workspace.Id?) {
        _state.update { currentState ->
            if (workspaceId == null || currentState.selectedWorkspaces.values.contains(workspaceId)) {
                currentState.copy(focusedWorkspaceId = workspaceId)
            } else {
                currentState
            }
        }
    }

    fun setPaneCount(count: Int) {
        log(TAG) { "Setting pane count to $count" }
        _state.update { it.copy(currentPaneCount = count) }
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

    private fun handleWorkspaceCreated(workspaceId: Workspace.Id, replacedId: Workspace.Id?) {
        log(TAG) { "handleWorkspaceCreated: workspaceId=$workspaceId, replacedId=$replacedId" }

        _state.update { currentState ->
            if (replacedId != null) {
                // This is a replacement
                val replacedPaneIndex = currentState.selectedWorkspaces.entries.find { it.value == replacedId }?.key

                if (replacedPaneIndex != null) {
                    log(TAG) { "Replacing workspace $replacedId at pane $replacedPaneIndex with $workspaceId" }
                    val newSelections = currentState.selectedWorkspaces + (replacedPaneIndex to workspaceId)

                    // Transfer focus if the replaced workspace was focused
                    val newFocus = if (currentState.focusedWorkspaceId == replacedId) {
                        log(TAG) { "Transferring focus from $replacedId to $workspaceId" }
                        workspaceId
                    } else {
                        currentState.focusedWorkspaceId
                    }

                    currentState.copy(
                        selectedWorkspaces = newSelections,
                        focusedWorkspaceId = newFocus
                    )
                } else {
                    log(TAG) { "Replaced workspace $replacedId was not in any pane, treating as new workspace" }
                    assignToEmptyPaneInternal(currentState, workspaceId)
                }
            } else {
                // New workspace, not a replacement
                if (!currentState.selectedWorkspaces.containsValue(workspaceId)) {
                    log(TAG) { "New workspace $workspaceId, assigning to empty pane" }
                    val newState = assignToEmptyPaneInternal(currentState, workspaceId)

                    // Auto-focus if no workspace is focused
                    if (newState.focusedWorkspaceId == null) {
                        log(TAG) { "No focused workspace, setting focus to $workspaceId" }
                        newState.copy(focusedWorkspaceId = workspaceId)
                    } else {
                        newState
                    }
                } else {
                    log(TAG) { "Workspace $workspaceId already assigned to a pane" }
                    currentState
                }
            }
        }
    }

    private suspend fun handleWorkspaceClosed(workspaceId: Workspace.Id) {
        log(TAG) { "handleWorkspaceClosed: workspaceId=$workspaceId" }

        val currentState = _state.value
        val wasSelected = currentState.selectedWorkspaces.values.contains(workspaceId)
        val wasFocused = currentState.focusedWorkspaceId == workspaceId

        if (wasSelected) {
            _state.update { state ->
                // Remove from selection
                val position = state.selectedWorkspaces.entries.find { it.value == workspaceId }?.key
                val newSelections = if (position != null) {
                    state.selectedWorkspaces - position
                } else {
                    state.selectedWorkspaces
                }

                // Select next workspace if this was focused
                val newFocus = if (wasFocused) {
                    val workspaces = workspaceRemote.state.first().infos
                    if (workspaces.isNotEmpty()) {
                        val newSelected = workspaces.firstOrNull()
                        newSelected?.let {
                            // If no workspaces selected, select the first one
                            if (newSelections.isEmpty()) {
                                return@update state.copy(
                                    selectedWorkspaces = mapOf(0 to it.id),
                                    focusedWorkspaceId = it.id
                                )
                            }
                            it.id
                        }
                    } else {
                        null
                    }
                } else {
                    state.focusedWorkspaceId
                }

                state.copy(
                    selectedWorkspaces = newSelections,
                    focusedWorkspaceId = newFocus
                )
            }
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