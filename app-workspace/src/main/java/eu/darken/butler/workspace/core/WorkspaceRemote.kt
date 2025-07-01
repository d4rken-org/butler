package eu.darken.butler.workspace.core

import kotlinx.coroutines.flow.Flow

interface WorkspaceRemote {

    val state: Flow<State>

    data class State(
        val workspaceInfos: List<Workspace.Info> = emptyList(),
        val selectedWorkspaceId: Workspace.Id? = null,
        val selectedWorkspaceIds: List<Workspace.Id> = emptyList(),
        val focusedWorkspaceId: Workspace.Id? = null,
        val isButtonActionsFlipped: Boolean = false,
    ) {
        val workspaceCount: Int
            get() = workspaceInfos.size
        val operationCount: Int
            get() = workspaceInfos.sumOf { it.operationCount }
        val attentionCount: Int
            get() = workspaceInfos.sumOf { it.attentionCount }
    }

    suspend fun execute(action: WorkspaceAction)
}