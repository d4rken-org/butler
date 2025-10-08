package eu.darken.butler.workspace.ui.manager

import eu.darken.butler.workspace.core.WorkspaceAction

interface WorkspaceActionHandler {
    fun executeWorkspaceAction(action: WorkspaceAction)

    fun navToWorkspaceManager()

    fun navToSettings()

    fun navToUpgradeButler()
}