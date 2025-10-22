package eu.darken.butler.searcher.ui.search

import androidx.compose.ui.graphics.vector.ImageVector
import eu.darken.butler.searcher.R
import eu.darken.butler.common.ca.CaString
import eu.darken.butler.common.ca.toCaString
import eu.darken.butler.workspace.core.Workspace
import eu.darken.butler.workspace.core.icon
import eu.darken.butler.workspace.ui.template.WorkspaceTemplate


data class SearcherWorkspaceTemplate(
    override val title: CaString = R.string.searcher_title.toCaString(),
    override val subtitle: CaString = R.string.searcher_subtitle.toCaString(),
    override val arguments: Workspace.Arguments? = null,
) : WorkspaceTemplate {
    override val type: Workspace.Type
        get() = Workspace.Type.SEARCHER

    override val icon: ImageVector
        get() = type.icon
}