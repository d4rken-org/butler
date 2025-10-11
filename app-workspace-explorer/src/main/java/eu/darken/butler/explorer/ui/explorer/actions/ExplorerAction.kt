package eu.darken.butler.explorer.ui.explorer.actions

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.twotone.Sort
import androidx.compose.material.icons.twotone.Add
import androidx.compose.material.icons.twotone.ContentCopy
import androidx.compose.material.icons.twotone.ContentCut
import androidx.compose.material.icons.twotone.Delete
import androidx.compose.material.icons.twotone.Deselect
import androidx.compose.material.icons.twotone.DriveFileRenameOutline
import androidx.compose.material.icons.twotone.FilterList
import androidx.compose.material.icons.twotone.FolderShared
import androidx.compose.material.icons.twotone.GridView
import androidx.compose.material.icons.twotone.Info
import androidx.compose.material.icons.twotone.Refresh
import androidx.compose.material.icons.twotone.RemoveCircle
import androidx.compose.material.icons.twotone.SelectAll
import androidx.compose.material.icons.twotone.Share
import androidx.compose.ui.graphics.vector.ImageVector
import eu.darken.butler.common.ca.CaString
import eu.darken.butler.common.ca.toCaString
import eu.darken.butler.explorer.R

sealed interface ExplorerAction {
    val icon: ImageVector
    val label: CaString
    val isVisible: Boolean get() = true
    val isEnabled: Boolean get() = true
    val isDestructive: Boolean get() = false
    val group: Group get() = Group.PRIMARY
    val badge: Boolean get() = false

    enum class Group {
        PRIMARY,
        SECONDARY,
    }

    // Common actions shared across contexts
    sealed interface Common : ExplorerAction {
        data class Refresh(
            override val isEnabled: Boolean = true,
            override val group: Group = Group.PRIMARY,
        ) : Common {
            override val icon = Icons.TwoTone.Refresh
            override val label = R.string.explorer_action_refresh.toCaString()
        }

        data class Sort(
            override val isEnabled: Boolean = true,
            override val group: Group = Group.SECONDARY,
        ) : Common {
            override val icon = Icons.AutoMirrored.TwoTone.Sort
            override val label = R.string.explorer_action_sort.toCaString()
        }

        data class Filter(
            override val isEnabled: Boolean = true,
            override val group: Group = Group.SECONDARY,
            override val badge: Boolean = false,
        ) : Common {
            override val icon = Icons.TwoTone.FilterList
            override val label = R.string.explorer_action_filter.toCaString()
        }

        data class ToggleView(
            override val isEnabled: Boolean = true,
            override val group: Group = Group.SECONDARY,
        ) : Common {
            override val icon = Icons.TwoTone.GridView
            override val label = R.string.explorer_action_view.toCaString()
        }

        data class Info(
            override val isEnabled: Boolean = true,
            override val group: Group = Group.SECONDARY,
        ) : Common {
            override val icon = Icons.TwoTone.Info
            override val label = R.string.explorer_action_info.toCaString()
        }
    }

    // Directory-specific actions
    sealed interface Directory : ExplorerAction {
        data class Create(
            override val isEnabled: Boolean = true,
            override val group: Group = Group.PRIMARY,
        ) : Directory {
            override val icon = Icons.TwoTone.Add
            override val label = R.string.explorer_action_create.toCaString()
        }

        data class Rename(
            override val isEnabled: Boolean = true,
            override val group: Group = Group.PRIMARY,
        ) : Directory {
            override val icon = Icons.TwoTone.DriveFileRenameOutline
            override val label = R.string.explorer_action_rename.toCaString()
        }

        data class Copy(
            override val group: Group = Group.PRIMARY,
        ) : Directory {
            override val icon = Icons.TwoTone.ContentCopy
            override val label = R.string.explorer_action_copy.toCaString()
        }

        data class Cut(
            override val isEnabled: Boolean = true,
            override val group: Group = Group.PRIMARY,
        ) : Directory {
            override val icon = Icons.TwoTone.ContentCut
            override val label = R.string.explorer_action_cut.toCaString()
        }

        data class Delete(
            override val isEnabled: Boolean = true,
            override val group: Group = Group.PRIMARY,
        ) : Directory {
            override val icon = Icons.TwoTone.Delete
            override val label = R.string.explorer_action_delete.toCaString()
            override val isDestructive = true
        }

        data class Share(
            override val group: Group = Group.PRIMARY,
        ) : Directory {
            override val icon = Icons.TwoTone.Share
            override val label = R.string.explorer_action_share.toCaString()
        }

        object SelectAll : Directory {
            override val icon = Icons.TwoTone.SelectAll
            override val label = R.string.explorer_action_select_all.toCaString()
        }

        object DeselectAll : Directory {
            override val icon = Icons.TwoTone.Deselect
            override val label = R.string.explorer_action_deselect_all.toCaString()
        }
    }

    sealed interface Device : ExplorerAction {
        data class AddLocation(
            override val isEnabled: Boolean = true,
            override val group: Group = Group.PRIMARY,
        ) : Device {
            override val icon = Icons.TwoTone.FolderShared
            override val label = R.string.explorer_action_add_location.toCaString()
        }

        data class RemoveLocation(
            override val isEnabled: Boolean = true,
            override val group: Group = Group.PRIMARY,
        ) : Device {
            override val icon = Icons.TwoTone.RemoveCircle
            override val label = R.string.explorer_device_action_remove_location.toCaString()
            override val isDestructive = true
        }

        data class RenameLocation(
            override val isEnabled: Boolean = true,
            override val group: Group = Group.PRIMARY,
        ) : Device {
            override val icon = Icons.TwoTone.DriveFileRenameOutline
            override val label = R.string.explorer_location_rename_action.toCaString()
        }
    }

    sealed interface Home : ExplorerAction
}