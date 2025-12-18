package eu.darken.butler.sdmaid.ui

import androidx.compose.ui.graphics.vector.ImageVector
import eu.darken.butler.common.ca.CaString
import eu.darken.butler.common.ca.toCaString
import eu.darken.butler.sdmaid.R
import eu.darken.butler.sdmaid.core.arguments.SdMaidArguments
import eu.darken.butler.workspace.core.Workspace
import eu.darken.butler.workspace.core.icon
import eu.darken.butler.workspace.ui.template.WorkspaceTemplate

data class SdMaidWorkspaceTemplate(
    override val title: CaString = R.string.sdmaid_workspace_title.toCaString(),
    override val subtitle: CaString = R.string.sdmaid_workspace_subtitle.toCaString(),
    override val arguments: Workspace.Arguments = SdMaidArguments.Default(),
) : WorkspaceTemplate {
    override val type: Workspace.Type
        get() = Workspace.Type.SDMAID

    override val icon: ImageVector
        get() = type.icon
}
