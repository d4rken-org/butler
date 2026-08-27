package eu.darken.butler.explorer.ui.explorer.actions

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.twotone.Sort
import androidx.compose.material.icons.automirrored.twotone.ViewList
import androidx.compose.material.icons.twotone.Add
import androidx.compose.material.icons.twotone.Compress
import androidx.compose.material.icons.twotone.ContentCopy
import androidx.compose.material.icons.twotone.ContentCut
import androidx.compose.material.icons.twotone.Delete
import androidx.compose.material.icons.twotone.DeleteForever
import androidx.compose.material.icons.twotone.Deselect
import androidx.compose.material.icons.twotone.DriveFileRenameOutline
import androidx.compose.material.icons.twotone.Edit
import androidx.compose.material.icons.twotone.FilterList
import androidx.compose.material.icons.twotone.FolderShared
import androidx.compose.material.icons.twotone.GridView
import androidx.compose.material.icons.twotone.Info
import androidx.compose.material.icons.automirrored.twotone.OpenInNew
import androidx.compose.material.icons.twotone.Refresh
import androidx.compose.material.icons.twotone.RemoveCircle
import androidx.compose.material.icons.twotone.Bookmark
import androidx.compose.material.icons.twotone.BookmarkBorder
import androidx.compose.material.icons.twotone.SelectAll
import androidx.compose.material.icons.twotone.Share
import androidx.compose.material.icons.twotone.Unarchive
import androidx.compose.material.icons.twotone.Visibility
import androidx.compose.ui.graphics.vector.ImageVector
import eu.darken.butler.common.ca.CaString
import eu.darken.butler.common.ca.toCaString
import eu.darken.butler.common.files.APath
import eu.darken.butler.explorer.R
import eu.darken.butler.explorer.core.ExplorerViewStyle
import eu.darken.butler.explorer.core.engine.ExplorerItem
import eu.darken.butler.workspace.ui.actions.WorkspaceActionBarItem

sealed interface ExplorerActionBarItem : WorkspaceActionBarItem {
    override val icon: ImageVector
    override val label: CaString
    override val isVisible: Boolean get() = true
    override val isEnabled: Boolean get() = true
    override val isDestructive: Boolean get() = false
    override val group: WorkspaceActionBarItem.Group get() = WorkspaceActionBarItem.Group.PRIMARY
    override val badge: Boolean get() = false

    // Common actions shared across contexts
    sealed interface Common : ExplorerActionBarItem {
        data class Refresh(
            override val isEnabled: Boolean = true,
            override val group: WorkspaceActionBarItem.Group = WorkspaceActionBarItem.Group.SECONDARY,
        ) : Common {
            override val icon = Icons.TwoTone.Refresh
            override val label = R.string.explorer_action_refresh.toCaString()
        }

        data class Sort(
            override val isEnabled: Boolean = true,
            override val group: WorkspaceActionBarItem.Group = WorkspaceActionBarItem.Group.SECONDARY,
            override val badge: Boolean = false,
        ) : Common {
            override val icon = Icons.AutoMirrored.TwoTone.Sort
            override val label = R.string.explorer_action_sort.toCaString()
        }

        data class Filter(
            override val isEnabled: Boolean = true,
            override val group: WorkspaceActionBarItem.Group = WorkspaceActionBarItem.Group.SECONDARY,
            override val badge: Boolean = false,
        ) : Common {
            override val icon = Icons.TwoTone.FilterList
            override val label = R.string.explorer_action_filter.toCaString()
        }

        data class UpdateViewStyle(
            val viewStyle: ExplorerViewStyle,
            override val isEnabled: Boolean = true,
            override val group: WorkspaceActionBarItem.Group = WorkspaceActionBarItem.Group.SECONDARY,
        ) : Common {
            override val icon = when (viewStyle) {
                is ExplorerViewStyle.Grid -> Icons.TwoTone.GridView
                is ExplorerViewStyle.List -> Icons.AutoMirrored.TwoTone.ViewList
            }
            override val label = R.string.explorer_action_view.toCaString()
        }

        data class Info(
            override val isEnabled: Boolean = true,
            override val group: WorkspaceActionBarItem.Group = WorkspaceActionBarItem.Group.SECONDARY,
        ) : Common {
            override val icon = Icons.TwoTone.Info
            override val label = R.string.explorer_action_info.toCaString()
        }

        data class Rename(
            val item: ExplorerItem.Lookup,
            override val icon: ImageVector = Icons.TwoTone.DriveFileRenameOutline,
            val labelRes: Int = R.string.explorer_action_rename,
        ) : Common {
            override val label = labelRes.toCaString()
        }

        data class AddToFavorites(
            val items: List<APath<*>>,
            override val isEnabled: Boolean = true,
            override val group: WorkspaceActionBarItem.Group = WorkspaceActionBarItem.Group.PRIMARY,
        ) : Common {
            override val icon = Icons.TwoTone.Bookmark
            override val label = R.string.explorer_action_add_to_favorites.toCaString()
        }

        data class RemoveFromFavorites(
            val items: List<APath<*>>,
            override val isEnabled: Boolean = true,
            override val group: WorkspaceActionBarItem.Group = WorkspaceActionBarItem.Group.PRIMARY,
        ) : Common {
            override val icon = Icons.TwoTone.BookmarkBorder
            override val label = R.string.explorer_action_remove_from_favorites.toCaString()
        }
    }

    // Directory-specific actions
    sealed interface Directory : ExplorerActionBarItem {
        data class Create(
            override val isEnabled: Boolean = true,
            override val group: WorkspaceActionBarItem.Group = WorkspaceActionBarItem.Group.PRIMARY,
        ) : Directory {
            override val icon = Icons.TwoTone.Add
            override val label = R.string.explorer_action_create.toCaString()
        }

        data class Rename(
            override val isEnabled: Boolean = true,
            override val group: WorkspaceActionBarItem.Group = WorkspaceActionBarItem.Group.PRIMARY,
        ) : Directory {
            override val icon = Icons.TwoTone.DriveFileRenameOutline
            override val label = R.string.explorer_action_rename.toCaString()
        }

        data class Copy(
            override val group: WorkspaceActionBarItem.Group = WorkspaceActionBarItem.Group.PRIMARY,
        ) : Directory {
            override val icon = Icons.TwoTone.ContentCopy
            override val label = R.string.explorer_action_copy.toCaString()
        }

        data class Cut(
            override val isEnabled: Boolean = true,
            override val group: WorkspaceActionBarItem.Group = WorkspaceActionBarItem.Group.PRIMARY,
        ) : Directory {
            override val icon = Icons.TwoTone.ContentCut
            override val label = R.string.explorer_action_cut.toCaString()
        }

        data class Delete(
            override val isEnabled: Boolean = true,
            val trashEnabled: Boolean = false,
            override val group: WorkspaceActionBarItem.Group = WorkspaceActionBarItem.Group.PRIMARY,
        ) : Directory {
            override val icon = if (trashEnabled) Icons.TwoTone.Delete else Icons.TwoTone.DeleteForever
            override val label = R.string.explorer_action_delete.toCaString()
            override val isDestructive = !trashEnabled
        }

        data class Share(
            override val group: WorkspaceActionBarItem.Group = WorkspaceActionBarItem.Group.PRIMARY,
        ) : Directory {
            override val icon = Icons.TwoTone.Share
            override val label = eu.darken.butler.common.R.string.general_share_action.toCaString()
        }

        data class Compress(
            override val group: WorkspaceActionBarItem.Group = WorkspaceActionBarItem.Group.SECONDARY,
        ) : Directory {
            override val icon = Icons.TwoTone.Compress
            override val label = R.string.explorer_action_compress.toCaString()
        }

        data class Extract(
            override val group: WorkspaceActionBarItem.Group = WorkspaceActionBarItem.Group.SECONDARY,
        ) : Directory {
            override val icon = Icons.TwoTone.Unarchive
            override val label = R.string.explorer_action_extract.toCaString()
        }

        object SelectAll : Directory {
            override val icon = Icons.TwoTone.SelectAll
            override val label = R.string.explorer_action_select_all.toCaString()
        }

        object DeselectAll : Directory {
            override val icon = Icons.TwoTone.Deselect
            override val label = R.string.explorer_action_deselect_all.toCaString()
        }

        data class OpenInNewTabs(
            override val isEnabled: Boolean = true,
            override val group: WorkspaceActionBarItem.Group = WorkspaceActionBarItem.Group.PRIMARY,
        ) : Directory {
            override val icon = Icons.AutoMirrored.TwoTone.OpenInNew
            override val label = R.string.explorer_action_open_in_new_tabs.toCaString()
        }

        /**
         * Action-bar toggle for the currently-viewed folder, shown when no selection is active.
         * `isFavorite` is for icon/label display only — execution must be atomic via repo.toggle().
         */
        data class ToggleFavoriteCurrent(
            val path: APath<*>,
            val isFavorite: Boolean,
            override val isEnabled: Boolean = true,
            override val group: WorkspaceActionBarItem.Group = WorkspaceActionBarItem.Group.PRIMARY,
        ) : Directory {
            override val icon = if (isFavorite) Icons.TwoTone.Bookmark else Icons.TwoTone.BookmarkBorder
            override val label = (
                if (isFavorite) R.string.explorer_action_remove_from_favorites
                else R.string.explorer_action_add_to_favorites
            ).toCaString()
        }
    }

    sealed interface Device : ExplorerActionBarItem {
        data class AddLocation(
            override val isEnabled: Boolean = true,
            override val group: WorkspaceActionBarItem.Group = WorkspaceActionBarItem.Group.PRIMARY,
        ) : Device {
            override val icon = Icons.TwoTone.FolderShared
            override val label = R.string.explorer_action_add_location.toCaString()
        }

        data class RemoveLocation(
            override val isEnabled: Boolean = true,
            override val group: WorkspaceActionBarItem.Group = WorkspaceActionBarItem.Group.PRIMARY,
        ) : Device {
            override val icon = Icons.TwoTone.RemoveCircle
            override val label = R.string.explorer_device_action_remove_location.toCaString()
            override val isDestructive = true
        }

        data class RenameLocation(
            override val isEnabled: Boolean = true,
            override val group: WorkspaceActionBarItem.Group = WorkspaceActionBarItem.Group.PRIMARY,
        ) : Device {
            override val icon = Icons.TwoTone.DriveFileRenameOutline
            override val label = R.string.explorer_location_rename_action.toCaString()
        }
    }

    sealed interface Network : ExplorerActionBarItem {
        data class AddLocation(
            override val isEnabled: Boolean = true,
            override val group: WorkspaceActionBarItem.Group = WorkspaceActionBarItem.Group.PRIMARY,
        ) : Network {
            override val icon = Icons.TwoTone.Add
            override val label = R.string.explorer_network_add_location_action.toCaString()
        }

        data class EditLocation(
            override val isEnabled: Boolean = true,
            override val group: WorkspaceActionBarItem.Group = WorkspaceActionBarItem.Group.PRIMARY,
        ) : Network {
            override val icon = Icons.TwoTone.Edit
            override val label = R.string.explorer_network_edit_location_action.toCaString()
        }

        data class RemoveLocation(
            override val isEnabled: Boolean = true,
            override val group: WorkspaceActionBarItem.Group = WorkspaceActionBarItem.Group.PRIMARY,
        ) : Network {
            override val icon = Icons.TwoTone.RemoveCircle
            override val label = R.string.explorer_network_remove_location_action.toCaString()
            override val isDestructive = true
        }
    }

    sealed interface Home : ExplorerActionBarItem

    /**
     * Actions for single-file context menu operations (from FileOptionsBottomSheet).
     */
    sealed interface File : ExplorerActionBarItem {
        /**
         * Opens the file in the workspace type that fits it, as a drill-down of this workspace:
         * an overlay in the same pane that returns here on back.
         */
        data class Open(
            val item: ExplorerItem.File,
            override val icon: ImageVector = Icons.TwoTone.Visibility,
            val labelRes: Int = R.string.explorer_file_action_open,
        ) : File {
            override val label = labelRes.toCaString()
        }

        /** Same routing as [Open], but as a workspace of its own instead of a drill-down. */
        data class OpenInTab(
            val item: ExplorerItem.File,
            override val icon: ImageVector = Icons.AutoMirrored.TwoTone.OpenInNew,
            val labelRes: Int = R.string.explorer_file_action_open_in_tab,
        ) : File {
            override val label = labelRes.toCaString()
        }

        data class OpenInEditor(
            val item: ExplorerItem.File,
            override val icon: ImageVector = Icons.TwoTone.DriveFileRenameOutline,
            val labelRes: Int = R.string.explorer_file_action_open_in_editor,
        ) : File {
            override val label = labelRes.toCaString()
        }

        data class OpenWith(
            val item: ExplorerItem.File,
            override val icon: ImageVector = Icons.AutoMirrored.TwoTone.OpenInNew,
            val labelRes: Int = R.string.explorer_file_action_open_with,
        ) : File {
            override val label = labelRes.toCaString()
        }

        data class Share(
            val item: ExplorerItem.File,
            override val icon: ImageVector = Icons.TwoTone.Share,
            val labelRes: Int = R.string.explorer_file_action_share,
        ) : File {
            override val label = labelRes.toCaString()
        }

        data class Copy(
            val item: ExplorerItem.File,
            override val icon: ImageVector = Icons.TwoTone.ContentCopy,
            val labelRes: Int = R.string.explorer_file_action_copy,
        ) : File {
            override val label = labelRes.toCaString()
        }

        data class Cut(
            val item: ExplorerItem.File,
            override val icon: ImageVector = Icons.TwoTone.ContentCut,
            val labelRes: Int = R.string.explorer_file_action_cut,
        ) : File {
            override val label = labelRes.toCaString()
        }

        data class Delete(
            val item: ExplorerItem.File,
            override val icon: ImageVector = Icons.TwoTone.Delete,
            val labelRes: Int = R.string.explorer_file_action_delete,
        ) : File {
            override val label = labelRes.toCaString()
            override val isDestructive = true
        }

        data class ShowProperties(
            val item: ExplorerItem.File,
            override val icon: ImageVector = Icons.TwoTone.Info,
            val labelRes: Int = R.string.explorer_file_action_properties,
        ) : File {
            override val label = labelRes.toCaString()
        }

        data class Extract(
            val item: ExplorerItem.File,
            override val icon: ImageVector = Icons.TwoTone.Unarchive,
            val labelRes: Int = R.string.explorer_file_action_extract,
        ) : File {
            override val label = labelRes.toCaString()
        }
    }

    sealed interface Trash : ExplorerActionBarItem {
        object SelectAll : Trash {
            override val icon = Icons.TwoTone.SelectAll
            override val label = R.string.explorer_action_select_all.toCaString()
        }

        data class Restore(
            val items: List<ExplorerItem.Trash.Root>,
            override val icon: ImageVector,
            val labelRes: Int,
            override val isEnabled: Boolean = true,
            override val group: WorkspaceActionBarItem.Group = WorkspaceActionBarItem.Group.PRIMARY,
        ) : Trash {
            override val label = labelRes.toCaString()
        }

        data class DeletePermanently(
            val items: List<ExplorerItem.Trash.Root>,
            override val icon: ImageVector,
            val labelRes: Int,
            override val isEnabled: Boolean = true,
            override val group: WorkspaceActionBarItem.Group = WorkspaceActionBarItem.Group.PRIMARY,
        ) : Trash {
            override val label = labelRes.toCaString()
            override val isDestructive = true
        }

        data class EmptyBin(
            override val icon: ImageVector,
            val labelRes: Int,
            override val isEnabled: Boolean = true,
            override val group: WorkspaceActionBarItem.Group = WorkspaceActionBarItem.Group.SECONDARY,
        ) : Trash {
            override val label = labelRes.toCaString()
            override val isDestructive = true
        }
    }

    /**
     * Actions for browsing inside a trashed folder (read-only).
     * No create/paste/rename operations allowed.
     */
    sealed interface TrashNested : ExplorerActionBarItem {
        object SelectAll : TrashNested {
            override val icon = Icons.TwoTone.SelectAll
            override val label = R.string.explorer_action_select_all.toCaString()
        }

        data class Restore(
            val items: List<ExplorerItem.Trash.Nested>,
            override val icon: ImageVector,
            val labelRes: Int,
            override val isEnabled: Boolean = true,
            override val group: WorkspaceActionBarItem.Group = WorkspaceActionBarItem.Group.PRIMARY,
        ) : TrashNested {
            override val label = labelRes.toCaString()
        }

        data class DeletePermanently(
            val items: List<ExplorerItem.Trash.Nested>,
            override val icon: ImageVector,
            val labelRes: Int,
            override val isEnabled: Boolean = true,
            override val group: WorkspaceActionBarItem.Group = WorkspaceActionBarItem.Group.PRIMARY,
        ) : TrashNested {
            override val label = labelRes.toCaString()
            override val isDestructive = true
        }
    }
}