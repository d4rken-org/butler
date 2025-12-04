package eu.darken.butler.searcher.ui.search

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.twotone.Sort
import androidx.compose.material.icons.twotone.ContentCopy
import androidx.compose.material.icons.twotone.ContentCut
import androidx.compose.material.icons.twotone.Delete
import androidx.compose.material.icons.twotone.DeleteForever
import androidx.compose.material.icons.twotone.Deselect
import androidx.compose.material.icons.twotone.GridView
import androidx.compose.material.icons.twotone.Link
import androidx.compose.material.icons.twotone.OpenInNew
import androidx.compose.material.icons.twotone.SelectAll
import androidx.compose.material.icons.twotone.Share
import androidx.compose.ui.graphics.vector.ImageVector
import eu.darken.butler.common.ca.CaString
import eu.darken.butler.common.ca.toCaString
import eu.darken.butler.searcher.R
import eu.darken.butler.searcher.core.SearchItem
import eu.darken.butler.searcher.core.SearcherViewStyle
import eu.darken.butler.workspace.core.Workspace
import eu.darken.butler.workspace.core.icon
import eu.darken.butler.workspace.ui.actions.WorkspaceAction

sealed interface SearcherAction : WorkspaceAction {
    override val icon: ImageVector
    override val label: CaString
    override val isVisible: Boolean get() = true
    override val isEnabled: Boolean get() = true
    override val isDestructive: Boolean get() = false
    override val group: WorkspaceAction.Group get() = WorkspaceAction.Group.PRIMARY
    override val badge: Boolean get() = false

    // Actions that work on one or more results
    data class Copy(
        val results: List<SearchItem>,
    ) : SearcherAction {
        override val icon = Icons.TwoTone.ContentCopy
        override val label = R.string.searcher_action_copy.toCaString()
    }

    data class Cut(
        val results: List<SearchItem>,
    ) : SearcherAction {
        override val icon = Icons.TwoTone.ContentCut
        override val label = R.string.searcher_action_cut.toCaString()
    }

    data class Delete(
        val results: List<SearchItem>,
        val trashEnabled: Boolean = false,
    ) : SearcherAction {
        override val icon = if (trashEnabled) Icons.TwoTone.Delete else Icons.TwoTone.DeleteForever
        override val label = R.string.searcher_action_delete.toCaString()
        override val isDestructive = !trashEnabled
        override val supportsLongPress = trashEnabled
    }

    data class Share(
        val results: List<SearchItem>,
    ) : SearcherAction {
        override val icon = Icons.TwoTone.Share
        override val label = eu.darken.butler.common.R.string.general_share_action.toCaString()
        override val isVisible: Boolean get() = results.size <= 10 // Reasonable limit for sharing
    }

    // Actions that only make sense for a single result
    data class OpenInEditor(
        val result: SearchItem,
    ) : SearcherAction {
        override val icon = Workspace.Type.EDITOR.icon
        override val label = R.string.searcher_action_open_in_editor.toCaString()
    }

    data class OpenInExplorer(
        val result: SearchItem,
    ) : SearcherAction {
        override val icon = Workspace.Type.EXPLORER.icon
        override val label = R.string.searcher_action_open_in_explorer.toCaString()
    }

    data class CopyPath(
        val result: SearchItem,
    ) : SearcherAction {
        override val icon = Icons.TwoTone.Link
        override val label = R.string.searcher_action_copy_path.toCaString()
    }

    // Selection management actions
    data object SelectAll : SearcherAction {
        override val icon = Icons.TwoTone.SelectAll
        override val label = R.string.searcher_action_select_all.toCaString()
    }

    data object DeselectAll : SearcherAction {
        override val icon = Icons.TwoTone.Deselect
        override val label = R.string.searcher_action_deselect_all.toCaString()
    }

    data class OpenInNewTabs(
        val results: List<SearchItem>,
    ) : SearcherAction {
        override val icon = Icons.TwoTone.OpenInNew
        override val label = R.string.searcher_action_open_in_new_tabs.toCaString()
        override val group = WorkspaceAction.Group.PRIMARY
    }

    // Common actions for browsing/viewing
    sealed interface Common : SearcherAction {
        data class Sort(
            override val isEnabled: Boolean = true,
            override val group: WorkspaceAction.Group = WorkspaceAction.Group.SECONDARY,
        ) : Common {
            override val icon = Icons.AutoMirrored.TwoTone.Sort
            override val label = R.string.searcher_action_sort.toCaString()
        }

        data class UpdateViewStyle(
            val viewStyle: SearcherViewStyle,
            override val isEnabled: Boolean = true,
            override val group: WorkspaceAction.Group = WorkspaceAction.Group.SECONDARY,
        ) : Common {
            override val icon = Icons.TwoTone.GridView
            override val label = R.string.searcher_action_view.toCaString()
        }
    }
}