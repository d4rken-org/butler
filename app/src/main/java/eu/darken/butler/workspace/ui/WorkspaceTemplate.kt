package eu.darken.butler.workspace.ui

import eu.darken.butler.common.ca.CaString
import eu.darken.butler.workspace.core.Workspace

data class WorkspaceTemplate(
    val title: CaString,
    val description: CaString,
    val type: Workspace.Type,
    val arguments: Workspace.Arguments?,
)