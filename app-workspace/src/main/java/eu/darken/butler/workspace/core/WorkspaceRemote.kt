package eu.darken.butler.workspace.core

import kotlinx.coroutines.flow.Flow

interface WorkspaceRemote {

    val state: Flow<State>

    data class State(
        val workspaceInfos: List<Workspace.Info> = emptyList(),
        val selectedWorkspaceId: Workspace.Id? = null,
        val isButtonActionsFlipped: Boolean = false,
    ) {
        val count: Int
            get() = workspaceInfos.size
    }

    suspend fun execute(action: WorkspaceAction)
}