package eu.darken.butler.workspace.ui.workspaces

import eu.darken.butler.workspace.core.Workspace

data class WorkspacePaneInfo(
    val id: Workspace.Id,
    val type: Workspace.Type,
)

fun Workspace.Info.asPaneInfo() = WorkspacePaneInfo(
    id = id,
    type = type,
)