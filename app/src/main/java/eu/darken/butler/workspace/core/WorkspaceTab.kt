package eu.darken.butler.workspace.core

import eu.darken.butler.common.ca.CaString
import eu.darken.butler.common.ca.toCaString

data class WorkspaceTab(
    val id: Workspace.Id = Workspace.Id(),
    val title: CaString = "New Tab".toCaString(), // TODO should we provide this via workspacemanager?
    val type: Workspace.Type = Workspace.Type.NEW
)