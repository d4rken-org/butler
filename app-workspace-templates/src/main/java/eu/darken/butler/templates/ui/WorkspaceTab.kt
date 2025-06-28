package eu.darken.butler.templates.ui

import eu.darken.butler.common.ca.CaString
import eu.darken.butler.workspace.core.Workspace

data class WorkspaceTab(
    val type: Workspace.Type,
    val id: Workspace.Id,
    val title: CaString,
)