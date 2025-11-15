package eu.darken.butler.workspace.core

import eu.darken.butler.workspace.core.layout.WorkspacePanelMode
import kotlinx.coroutines.flow.Flow

interface WorkspaceRemote {

    val state: Flow<State>
    
    val events: Flow<WorkspaceEvent>

    /**
     * Emit a workspace event
     * Allows workspaces to communicate results (e.g., picker selection)
     */
    suspend fun emitEvent(event: WorkspaceEvent)

    data class State(
        val infos: List<Workspace.Info> = emptyList(),
        val portraitPanelMode: WorkspacePanelMode = WorkspacePanelMode.AUTO,
        val landscapePanelMode: WorkspacePanelMode = WorkspacePanelMode.AUTO,
    ) {
        val workspaceCount: Int
            get() = infos.size
        val operationCount: Int
            get() = infos.sumOf { it.operationCount }
        val attentionCount: Int
            get() = infos.sumOf { it.attentionCount }

    }

    suspend fun execute(action: WorkspaceAction): WorkspaceAction.Result
}