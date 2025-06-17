package eu.darken.butler.workspace.core

import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.debug.logging.logTag
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class WorkspaceRepo @Inject constructor() {
    private val _workspaces = MutableStateFlow<List<Workspace>>(emptyList())
    val workspaces: Flow<List<Workspace>> = _workspaces

    suspend fun create(
        type: Workspace.Type,
        arguments: Workspace.Arguments? = null,
    ): Workspace.Id {
        return Workspace.Id()
    }

    suspend fun get(id: Workspace.Id): Workspace? {
        return workspaces.first().single { it.id == id }
    }

    suspend fun delete(id: Workspace.Id) {
        log(TAG) { "delete($id)" }
        _workspaces.value = _workspaces.value.filter { it.id != id }
    }

    companion object {
        private val TAG = logTag("Workspace", "Repo")
    }

}