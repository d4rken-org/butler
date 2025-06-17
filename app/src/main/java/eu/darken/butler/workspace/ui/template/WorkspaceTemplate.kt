package eu.darken.butler.workspace.ui.template

import androidx.compose.ui.graphics.vector.ImageVector
import eu.darken.butler.common.ca.CaString
import eu.darken.butler.workspace.core.Workspace

interface WorkspaceTemplate {
    val type: Workspace.Type
    val icon: ImageVector
    val title: CaString
    val subtitle: CaString
    val arguments: Workspace.Arguments?
}