package eu.darken.butler.workspace.core

import eu.darken.butler.common.debug.logging.Logging.Priority.*
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.debug.logging.logTag
import eu.darken.butler.editor.core.EditorWorkspace
import eu.darken.butler.explorer.core.ExplorerWorkspace
import eu.darken.butler.searcher.core.SearcherWorkspace
import eu.darken.butler.templates.core.TemplatesWorkspace
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class WorkspaceRepo @Inject constructor(
    private val templatesWorkspaceFactory: TemplatesWorkspace.Factory,
    private val explorerWorkspaceFactory: ExplorerWorkspace.Factory,
    private val searcherWorkspaceFactory: SearcherWorkspace.Factory,
    private val editorWorkspaceFactory: EditorWorkspace.Factory,
    private val workspaceSettings: WorkspaceSettings,
) : WorkspaceProvider, WorkspaceRemote {

    private val lock = Mutex()
    private val _workspaces = MutableStateFlow<List<Workspace>>(emptyList())
    private val focusedWorkspace = MutableStateFlow<Workspace.Id?>(null)
    private val selectedWorkspaces = MutableStateFlow<List<Workspace.Id>>(emptyList())
    private val infos: Flow<List<Workspace.Info>> = _workspaces.flatMapLatest { workspaces ->
        if (workspaces.isEmpty()) {
            flowOf(emptyList())
        } else {
            val infoFlows = workspaces.map { it.info }
            combine(infoFlows) { infos -> infos.toList() }
        }
    }

    override val state: Flow<WorkspaceRemote.State> = combine(
        infos,
        focusedWorkspace,
        selectedWorkspaces,
        workspaceSettings.isButtonActionsFlipped.flow
    ) { workspaceInfos, focusedId, selectedIds, isButtonFlipped ->
        WorkspaceRemote.State(
            infos = workspaceInfos,
            selectedWorkspaces = selectedIds,
            focusedWorkspace = focusedId,
            isButtonActionsFlipped = isButtonFlipped,
        )
    }

    private suspend fun create(
        type: Workspace.Type,
        arguments: Workspace.Arguments? = null,
        idToReplace: Workspace.Id? = null,
    ): Workspace.Id {
        log(TAG) { "create($type, $arguments, $idToReplace)" }
        val wip = _workspaces.value.toMutableList()

        val newWorkspace = when (type) {
            Workspace.Type.TEMPLATES -> templatesWorkspaceFactory.create(
                id = Workspace.Id(),
                arguments = arguments as TemplatesWorkspace.Arguments?
            )
            Workspace.Type.EXPLORER -> explorerWorkspaceFactory.create(
                id = Workspace.Id(),
                arguments = arguments as ExplorerWorkspace.Arguments?
            )
            Workspace.Type.SEARCHER -> searcherWorkspaceFactory.create(
                id = Workspace.Id(),
                arguments = arguments as SearcherWorkspace.Arguments?
            )
            Workspace.Type.EDITOR -> editorWorkspaceFactory.create(
                id = Workspace.Id(),
                arguments = arguments as EditorWorkspace.Arguments?
            )
        }
        if (idToReplace != null) {
            val index = wip.indexOfFirst { it.id == idToReplace }
            if (index == -1) throw IllegalStateException("Tab not found")
            log(TAG) { "Replacing workspace at index $index" }

            // TODO clean up old tab?

            wip[index] = newWorkspace
        } else {
            wip.add(newWorkspace)
        }

        _workspaces.value = wip

        return newWorkspace.id
    }

    override suspend fun get(id: Workspace.Id): Flow<Workspace?> {
        return _workspaces.map { wss -> wss.singleOrNull { it.id == id } }
    }

    override suspend fun execute(action: WorkspaceAction) = lock.withLock {
        log(TAG, INFO) { "execute($action)" }
        when (action) {
            is WorkspaceAction.Select -> {
                log(TAG, INFO) { "Selected tab $action, previous: ${focusedWorkspace.value}" }
                if (focusedWorkspace.value != action.id) {
                    focusedWorkspace.value = action.id
                    selectedWorkspaces.value = listOf(action.id)
                    log(TAG) { "Tab selection changed to: ${action.id}" }
                } else {
                    log(TAG) { "Tab selection unchanged, already selected: ${action.id}" }
                }
            }

            is WorkspaceAction.SelectMultiple -> {
                log(TAG, INFO) { "Selecting multiple tabs: ${action.ids}" }
                selectedWorkspaces.value = action.ids
                if (action.ids.isNotEmpty() && (focusedWorkspace.value == null || !action.ids.contains(
                        focusedWorkspace.value
                    ))
                ) {
                    focusedWorkspace.value = action.ids.first()
                }
                focusedWorkspace.value = action.ids.firstOrNull()
            }

            is WorkspaceAction.Focus -> {
                log(TAG, INFO) { "Focusing tab: ${action.id}" }
                if (selectedWorkspaces.value.contains(action.id)) {
                    focusedWorkspace.value = action.id
                } else {
                    log(TAG, WARN) { "Cannot focus tab ${action.id} - not in selected tabs" }
                }
            }

            is WorkspaceAction.ToggleSelection -> {
                log(TAG, INFO) { "Toggling selection for tab: ${action.id}" }
                val current = selectedWorkspaces.value
                selectedWorkspaces.value = if (current.contains(action.id)) {
                    current - action.id
                } else {
                    current + action.id
                }
                if (selectedWorkspaces.value.isEmpty()) {
                    focusedWorkspace.value = null
                } else if (!selectedWorkspaces.value.contains(focusedWorkspace.value)) {
                    focusedWorkspace.value = selectedWorkspaces.value.first()
                }
            }

            is WorkspaceAction.Create -> {
                log(TAG, INFO) { "Creating new workspace with $action" }
                val newId = create(
                    type = action.type,
                    arguments = action.arguments,
                    idToReplace = action.replace
                )
                log(TAG) { "New workspace created with id $newId, selecting and scrolling to it" }
                focusedWorkspace.value = newId
                selectedWorkspaces.value = listOf(newId)
            }

            is WorkspaceAction.Close -> {
                log(TAG, INFO) { "Closing workspace with id ${action.id}" }
                val tabsBeforeDelete = _workspaces.first()
                val closingIndex = tabsBeforeDelete.indexOfFirst { it.id == action.id }
                val wasInMultiSelection = selectedWorkspaces.value.contains(action.id)
                val wasFocused = focusedWorkspace.value == action.id

                _workspaces.value = _workspaces.value.filter { it.id != action.id }
                val tabsAfterDelete = tabsBeforeDelete - tabsBeforeDelete[closingIndex]

                // Update multi-selection
                if (wasInMultiSelection) {
                    selectedWorkspaces.value = selectedWorkspaces.value.filter { it != action.id }
                }

                // If closed tab wasn't selected, keep current selection unchanged
                if (tabsAfterDelete.isNotEmpty() && wasFocused) {
                    // Select next most intuitive tab when closing the selected tab
                    val newSelectedId = when {
                        // If there's a tab to the right, select it
                        closingIndex < tabsAfterDelete.size -> tabsAfterDelete[closingIndex].id
                        // Otherwise select the tab to the left (last tab)
                        else -> tabsAfterDelete.last().id
                    }
                    log(TAG) { "Closed selected tab, selecting new tab: $newSelectedId" }
                    focusedWorkspace.value = newSelectedId
                    if (selectedWorkspaces.value.isEmpty()) {
                        selectedWorkspaces.value = listOf(newSelectedId)
                    }
                } else if (tabsAfterDelete.isEmpty()) {
                    log(TAG) { "Closed last tab, setting selection to null" }
                    focusedWorkspace.value = null
                    selectedWorkspaces.value = emptyList()
                }

                // Update focus if needed
                if (wasFocused && tabsAfterDelete.isNotEmpty()) {
                    focusedWorkspace.value = selectedWorkspaces.value.firstOrNull() ?: focusedWorkspace.value
                }
            }
            is WorkspaceAction.Reorder -> {
                log(TAG, INFO) { "Reordering workspaces: ${action.workspaceIds}" }

                val current = _workspaces.value
                val reordered = action.workspaceIds.mapNotNull { id ->
                    current.find { it.id == id }
                }

                if (reordered.size != current.size) {
                    log(TAG, ERROR) { "Reorder failed: size mismatch. Expected ${current.size}, got ${reordered.size}" }
                    return
                }

                _workspaces.value = reordered
            }
            WorkspaceAction.CloseAll -> {
                log(TAG, INFO) { "Closing all workspaces" }
                focusedWorkspace.value = null
                selectedWorkspaces.value = emptyList()
                _workspaces.value = emptyList()
            }
        }
    }

    companion object {
        private val TAG = logTag("Workspace", "Repo")
    }

}
