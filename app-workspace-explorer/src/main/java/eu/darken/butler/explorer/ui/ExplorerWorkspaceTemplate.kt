package eu.darken.butler.explorer.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.twotone.Folder
import androidx.compose.ui.graphics.vector.ImageVector
import eu.darken.butler.common.ca.CaString
import eu.darken.butler.common.ca.toCaString
import eu.darken.butler.explorer.R
import eu.darken.butler.workspace.core.Workspace
import eu.darken.butler.workspace.ui.template.WorkspaceTemplate

data class ExplorerWorkspaceTemplate(
    override val icon: ImageVector =  Icons.TwoTone.Folder,
    override val title: CaString = R.string.explorer_title.toCaString(),
    override val subtitle: CaString = R.string.explorer_subtitle.toCaString(),
    override val arguments: Workspace.Arguments? = null,
) : WorkspaceTemplate {
    override val type: Workspace.Type
        get() = Workspace.Type.EXPLORER
}