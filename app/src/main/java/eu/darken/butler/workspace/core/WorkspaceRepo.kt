package eu.darken.butler.workspace.core

import eu.darken.butler.common.debug.logging.Logging.Priority.*
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.debug.logging.logTag
import eu.darken.butler.editor.core.EditorWorkspace
import eu.darken.butler.explorer.core.ExplorerWorkspace
import eu.darken.butler.searcher.core.SearcherWorkspace
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
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
) : WorkspaceProvider {
    private val lock = Mutex()
    private val _workspaces = MutableStateFlow<List<Workspace>>(emptyList())
    private val _selectedWorkspaceId = MutableStateFlow<Workspace.Id?>(null)
    private val selectedWorkspaceId: Flow<Workspace.Id?> = _selectedWorkspaceId
    private val infos: Flow<List<Workspace.Info>> = _workspaces.flatMapLatest { workspaces ->
        if (workspaces.isEmpty()) {
            flowOf(emptyList())
        } else {
            val infoFlows = workspaces.map { it.info }
            combine(infoFlows) { infos -> infos.toList() }
        }
    }

    data class State(
        val workspaceInfos: List<Workspace.Info> = emptyList(),
        val selectedWorkspaceId: Workspace.Id? = null
    )

    val state: Flow<State> = combine(
        infos,
        selectedWorkspaceId
    ) { workspaceInfos, selectedId ->
        State(
            workspaceInfos = workspaceInfos,
            selectedWorkspaceId = selectedId
        )
    }

    suspend fun create(
        type: Workspace.Type,
        arguments: Workspace.Arguments? = null,
        idToReplace: Workspace.Id? = null,
    ): Workspace.Id = lock.withLock {
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

        newWorkspace.id
    }

    override suspend fun get(id: Workspace.Id): Flow<Workspace?> = lock.withLock {
        _workspaces.map { wss -> wss.singleOrNull { it.id == id } }
    }

    suspend fun delete(id: Workspace.Id) = lock.withLock {
        log(TAG) { "delete($id)" }
        _workspaces.value = _workspaces.value.filter { it.id != id }
    }

    suspend fun reorder(workspaceIds: List<Workspace.Id>) = lock.withLock {
        log(TAG) { "reorder($workspaceIds)" }
        val current = _workspaces.value
        val reordered = workspaceIds.mapNotNull { id ->
            current.find { it.id == id }
        }

        if (reordered.size != current.size) {
            log(TAG, ERROR) { "Reorder failed: size mismatch. Expected ${current.size}, got ${reordered.size}" }
            return@withLock
        }

        _workspaces.value = reordered
    }

    suspend fun selectWorkspace(id: Workspace.Id) = lock.withLock {
        log(TAG) { "selectWorkspace($id)" }
        _selectedWorkspaceId.value = id
    }

    suspend fun clearSelectedWorkspace() = lock.withLock {
        log(TAG) { "clearSelectedWorkspace()" }
        _selectedWorkspaceId.value = null
    }

    companion object {
        private val TAG = logTag("Workspace", "Repo")
    }

}
