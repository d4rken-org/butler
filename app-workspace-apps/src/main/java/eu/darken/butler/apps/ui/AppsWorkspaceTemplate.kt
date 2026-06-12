package eu.darken.butler.apps.ui

import androidx.compose.ui.graphics.vector.ImageVector
import eu.darken.butler.apps.R
import eu.darken.butler.common.ca.CaString
import eu.darken.butler.common.ca.toCaString
import eu.darken.butler.workspace.contracts.apps.AppsArguments
import eu.darken.butler.workspace.core.Workspace
import eu.darken.butler.workspace.core.icon
import eu.darken.butler.workspace.ui.template.WorkspaceTemplate

data class AppsWorkspaceTemplate(
    override val title: CaString = R.string.apps_title.toCaString(),
    override val subtitle: CaString = R.string.apps_subtitle.toCaString(),
    override val arguments: Workspace.Arguments = AppsArguments.Default(),
) : WorkspaceTemplate {
    override val type: Workspace.Type
        get() = Workspace.Type.APPS

    override val icon: ImageVector
        get() = type.icon
}
