package eu.darken.butler.editor.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.ui.graphics.vector.ImageVector
import eu.darken.butler.R
import eu.darken.butler.common.ca.CaString
import eu.darken.butler.common.ca.toCaString
import eu.darken.butler.workspace.core.Workspace
import eu.darken.butler.workspace.ui.template.WorkspaceTemplate


data class EditorWorkspaceTemplate(
    override val icon: ImageVector =  Icons.Default.Edit,
    override val title: CaString = R.string.editor_title.toCaString(),
    override val subtitle: CaString = R.string.editor_subtitle.toCaString(),
    override val arguments: Workspace.Arguments? = null,
) : WorkspaceTemplate {
    override val type: Workspace.Type
        get() = Workspace.Type.EDITOR
}