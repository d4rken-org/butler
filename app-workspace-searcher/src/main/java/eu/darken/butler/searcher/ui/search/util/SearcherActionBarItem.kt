package eu.darken.butler.searcher.ui.search.util

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.twotone.OpenInNew
import androidx.compose.material.icons.automirrored.twotone.Sort
import androidx.compose.material.icons.automirrored.twotone.ViewList
import androidx.compose.material.icons.twotone.ContentCopy
import androidx.compose.material.icons.twotone.ContentCut
import androidx.compose.material.icons.twotone.Delete
import androidx.compose.material.icons.twotone.DeleteForever
import androidx.compose.material.icons.twotone.Deselect
import androidx.compose.material.icons.twotone.GridView
import androidx.compose.material.icons.twotone.Info
import androidx.compose.material.icons.twotone.InstallMobile
import androidx.compose.material.icons.twotone.Link
import androidx.compose.material.icons.twotone.OpenInBrowser
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
import eu.darken.butler.workspace.ui.actions.WorkspaceActionBarItem
import eu.darken.butler.common.R as CommonR

sealed interface SearcherActionBarItem : WorkspaceActionBarItem {
    override val icon: ImageVector
    override val label: CaString
    override val isVisible: Boolean get() = true
    override val isEnabled: Boolean get() = true
    override val isDestructive: Boolean get() = false
    override val group: WorkspaceActionBarItem.Group get() = WorkspaceActionBarItem.Group.PRIMARY
    override val badge: Boolean get() = false

    // Actions that work on one or more results
    data class Copy(
        val results: List<SearchItem>,
    ) : SearcherActionBarItem {
        override val icon = Icons.TwoTone.ContentCopy
        override val label = R.string.searcher_action_copy.toCaString()
    }

    data class Cut(
        val results: List<SearchItem>,
    ) : SearcherActionBarItem {
        override val icon = Icons.TwoTone.ContentCut
        override val label = R.string.searcher_action_cut.toCaString()
    }

    data class Delete(
        val results: List<SearchItem>,
        val trashEnabled: Boolean = false,
    ) : SearcherActionBarItem {
        override val icon = if (trashEnabled) Icons.TwoTone.Delete else Icons.TwoTone.DeleteForever
        override val label = R.string.searcher_action_delete.toCaString()
        override val isDestructive = !trashEnabled
    }

    data class Share(
        val results: List<SearchItem>,
    ) : SearcherActionBarItem {
        override val icon = Icons.TwoTone.Share
        override val label = CommonR.string.general_share_action.toCaString()
        override val isVisible: Boolean get() = results.size <= 10 // Reasonable limit for sharing
    }

    // Actions that only make sense for a single result
    /**
     * Opens the result in the workspace type that fits it, as a drill-down of this workspace:
     * an overlay in the same pane that returns here on back.
     */
    data class Open(
        val result: SearchItem,
    ) : SearcherActionBarItem {
        override val icon = Workspace.Type.VIEWER.icon
        override val label = R.string.searcher_action_open.toCaString()
    }

    /** Same routing as [Open], but as a workspace of its own instead of a drill-down. */
    data class OpenInTab(
        val result: SearchItem,
    ) : SearcherActionBarItem {
        override val icon = Icons.AutoMirrored.TwoTone.OpenInNew
        override val label = R.string.searcher_action_open_in_tab.toCaString()
    }

    data class OpenWith(
        val result: SearchItem,
    ) : SearcherActionBarItem {
        override val icon = Icons.TwoTone.OpenInBrowser
        override val label = R.string.searcher_action_open_with.toCaString()
    }

    data class Install(
        val result: SearchItem,
    ) : SearcherActionBarItem {
        override val icon = Icons.TwoTone.InstallMobile
        override val label = R.string.searcher_action_install.toCaString()
    }

    data class OpenInEditor(
        val result: SearchItem,
    ) : SearcherActionBarItem {
        override val icon = Workspace.Type.EDITOR.icon
        override val label = R.string.searcher_action_open_in_editor.toCaString()
    }

    data class OpenInExplorer(
        val result: SearchItem,
    ) : SearcherActionBarItem {
        override val icon = Workspace.Type.EXPLORER.icon
        override val label = R.string.searcher_action_open_in_explorer.toCaString()
    }

    data class CopyPath(
        val result: SearchItem,
    ) : SearcherActionBarItem {
        override val icon = Icons.TwoTone.Link
        override val label = R.string.searcher_action_copy_path.toCaString()
    }

    data class ShowProperties(
        val result: SearchItem,
    ) : SearcherActionBarItem {
        override val icon = Icons.TwoTone.Info
        override val label = R.string.searcher_action_properties.toCaString()
    }

    // Selection management actions
    data object SelectAll : SearcherActionBarItem {
        override val icon = Icons.TwoTone.SelectAll
        override val label = R.string.searcher_action_select_all.toCaString()
    }

    data object SelectAllFolders : SearcherActionBarItem {
        override val icon = Icons.TwoTone.SelectAll
        override val label = CommonR.string.common_select_all_folders_action.toCaString()
    }

    data object SelectAllFiles : SearcherActionBarItem {
        override val icon = Icons.TwoTone.SelectAll
        override val label = CommonR.string.common_select_all_files_action.toCaString()
    }

    data object DeselectAll : SearcherActionBarItem {
        override val icon = Icons.TwoTone.Deselect
        override val label = R.string.searcher_action_deselect_all.toCaString()
    }

    data class OpenInNewTabs(
        val results: List<SearchItem>,
    ) : SearcherActionBarItem {
        override val icon = Icons.AutoMirrored.TwoTone.OpenInNew
        override val label = R.string.searcher_action_open_in_new_tabs.toCaString()
        override val group = WorkspaceActionBarItem.Group.PRIMARY
    }

    // Common actions for browsing/viewing
    sealed interface Common : SearcherActionBarItem {
        data class Sort(
            override val isEnabled: Boolean = true,
            override val group: WorkspaceActionBarItem.Group = WorkspaceActionBarItem.Group.SECONDARY,
        ) : Common {
            override val icon = Icons.AutoMirrored.TwoTone.Sort
            override val label = R.string.searcher_action_sort.toCaString()
        }

        data class UpdateViewStyle(
            val viewStyle: SearcherViewStyle,
            override val isEnabled: Boolean = true,
            override val group: WorkspaceActionBarItem.Group = WorkspaceActionBarItem.Group.SECONDARY,
        ) : Common {
            override val icon = when (viewStyle) {
                is SearcherViewStyle.Grid -> Icons.TwoTone.GridView
                is SearcherViewStyle.List -> Icons.AutoMirrored.TwoTone.ViewList
            }
            override val label = R.string.searcher_action_view.toCaString()
        }
    }
}