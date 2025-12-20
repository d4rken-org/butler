package eu.darken.butler.developer.ui

import androidx.compose.ui.graphics.vector.ImageVector
import eu.darken.butler.common.ca.CaString
import eu.darken.butler.common.ca.toCaString
import eu.darken.butler.developer.R
import eu.darken.butler.developer.core.arguments.DeveloperArguments
import eu.darken.butler.workspace.core.Workspace
import eu.darken.butler.workspace.core.icon
import eu.darken.butler.workspace.ui.template.WorkspaceTemplate

data class DeveloperWorkspaceTemplate(
    override val title: CaString = R.string.developer_title.toCaString(),
    override val subtitle: CaString = R.string.developer_subtitle.toCaString(),
    override val arguments: Workspace.Arguments = DeveloperArguments.Default(),
) : WorkspaceTemplate {
    override val type: Workspace.Type
        get() = Workspace.Type.DEVELOPER

    override val icon: ImageVector
        get() = type.icon
}
