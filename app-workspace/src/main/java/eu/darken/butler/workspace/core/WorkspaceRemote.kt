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
        /**
         * Open tabs, i.e. what the Butler button's badge shows. Excludes stacked sub-workspaces:
         * they occupy no tab of their own and do not count toward the free-tier limit either, so
         * counting them would make the badge contradict the limit the user runs into.
         *
         * Deliberately NOT narrowed the way the limit's own count is: quota-exempt types (Developer,
         * Bug Report) are real tabs the user can switch to, they are merely exempt from the cap.
         */
        val workspaceCount: Int
            get() = infos.count { !it.isSubWorkspace }
        val operationCount: Int
            get() = infos.sumOf { it.operationCount }
        val attentionCount: Int
            get() = infos.sumOf { it.attentionCount }

    }

    suspend fun execute(action: WorkspaceAction): WorkspaceAction.Result
}