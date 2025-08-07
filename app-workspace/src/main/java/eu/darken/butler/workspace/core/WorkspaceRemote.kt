package eu.darken.butler.workspace.core

import eu.darken.butler.workspace.ui.WorkspacePanelMode
import kotlinx.coroutines.flow.Flow

interface WorkspaceRemote {

    val state: Flow<State>

    data class State(
        val infos: List<Workspace.Info> = emptyList(),
        val focusedWorkspace: Workspace.Id? = null,
        val selectedWorkspaces: Map<Int, Workspace.Id> = emptyMap(),
        val isButtonActionsFlipped: Boolean = false,
        val panelMode: WorkspacePanelMode = WorkspacePanelMode.AUTO,
    ) {
        val workspaceCount: Int
            get() = infos.size
        val operationCount: Int
            get() = infos.sumOf { it.operationCount }
        val attentionCount: Int
            get() = infos.sumOf { it.attentionCount }

    }

    suspend fun execute(action: WorkspaceAction)
}