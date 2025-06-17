package eu.darken.butler.searcher.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.ui.graphics.vector.ImageVector
import eu.darken.butler.R
import eu.darken.butler.common.ca.CaString
import eu.darken.butler.common.ca.toCaString
import eu.darken.butler.workspace.core.Workspace
import eu.darken.butler.workspace.ui.templates.WorkspaceTemplate


data class SearcherWorkspaceTemplate(
    override val icon: ImageVector =  Icons.Default.Search,
    override val title: CaString = R.string.searcher_title.toCaString(),
    override val subtitle: CaString = R.string.searcher_subtitle.toCaString(),
    override val arguments: Workspace.Arguments? = null,
) : WorkspaceTemplate {
    override val type: Workspace.Type
        get() = Workspace.Type.SEARCHER
}