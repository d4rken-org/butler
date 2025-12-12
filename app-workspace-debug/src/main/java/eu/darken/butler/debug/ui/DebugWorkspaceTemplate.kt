package eu.darken.butler.debug.ui

import androidx.compose.ui.graphics.vector.ImageVector
import eu.darken.butler.common.ca.CaString
import eu.darken.butler.common.ca.toCaString
import eu.darken.butler.debug.R
import eu.darken.butler.debug.core.arguments.DebugArguments
import eu.darken.butler.workspace.core.Workspace
import eu.darken.butler.workspace.core.icon
import eu.darken.butler.workspace.ui.template.WorkspaceTemplate

data class DebugWorkspaceTemplate(
    override val title: CaString = R.string.debug_title.toCaString(),
    override val subtitle: CaString = R.string.debug_subtitle.toCaString(),
    override val arguments: Workspace.Arguments = DebugArguments.Default(),
) : WorkspaceTemplate {
    override val type: Workspace.Type
        get() = Workspace.Type.DEBUG

    override val icon: ImageVector
        get() = type.icon
}
