package eu.darken.butler.workspace.ui.manager

import eu.darken.butler.workspace.core.WorkspaceAction
import eu.darken.butler.workspace.ui.template.QuickCreateItem
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

class FakeWorkspaceButtonProvider(
    initialState: WorkspaceButtonViewModel.State = WorkspaceButtonViewModel.State(),
) : WorkspaceButtonProvider {
    override val state: Flow<WorkspaceButtonViewModel.State> = flowOf(initialState)
    override fun executeWorkspaceAction(action: WorkspaceAction) {}
    override fun navToWorkspaceManager() {}
    override fun navToSettings() {}
    override fun navToUpgradeButler() {}
    override fun createWorkspace(item: QuickCreateItem) {}
    override fun createTemplatesWorkspace() {}
}
