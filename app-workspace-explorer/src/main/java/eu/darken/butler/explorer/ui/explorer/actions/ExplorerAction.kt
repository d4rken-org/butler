package eu.darken.butler.explorer.ui.explorer.actions

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.twotone.Sort
import androidx.compose.material.icons.twotone.Add
import androidx.compose.material.icons.twotone.ContentCopy
import androidx.compose.material.icons.twotone.ContentCut
import androidx.compose.material.icons.twotone.ContentPaste
import androidx.compose.material.icons.twotone.Delete
import androidx.compose.material.icons.twotone.DriveFileRenameOutline
import androidx.compose.material.icons.twotone.FilterList
import androidx.compose.material.icons.twotone.GridView
import androidx.compose.material.icons.twotone.MoreVert
import androidx.compose.material.icons.twotone.Refresh
import androidx.compose.material.icons.twotone.SelectAll
import androidx.compose.material.icons.twotone.Settings
import androidx.compose.material.icons.twotone.Share
import androidx.compose.material.icons.twotone.Storage
import androidx.compose.ui.graphics.vector.ImageVector

sealed interface ExplorerAction {
    val icon: ImageVector
    val label: String
    val isVisible: Boolean get() = true
    val isEnabled: Boolean get() = true
    val isDestructive: Boolean get() = false
    val group: Group get() = Group.PRIMARY
    val badge: String? get() = null

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
            override val label = "Refresh"
        }

        data class Sort(
            override val isEnabled: Boolean = true,
            override val group: Group = Group.SECONDARY,
        ) : Common {
            override val icon = Icons.AutoMirrored.TwoTone.Sort
            override val label = "Sort"
        }

        data class Filter(
            override val isEnabled: Boolean = true,
            override val group: Group = Group.SECONDARY,
        ) : Common {
            override val icon = Icons.TwoTone.FilterList
            override val label = "Filter"
        }

        data class ToggleView(
            override val isEnabled: Boolean = true,
            override val group: Group = Group.SECONDARY,
        ) : Common {
            override val icon = Icons.TwoTone.GridView
            override val label = "View"
        }
    }

    // Directory-specific actions
    sealed interface Directory : ExplorerAction {
        data class Create(
            override val isEnabled: Boolean = true,
            override val group: Group = Group.PRIMARY,
        ) : Directory {
            override val icon = Icons.TwoTone.Add
            override val label = "Create"
        }

        data class Rename(
            override val isEnabled: Boolean = true,
            override val group: Group = Group.PRIMARY,
        ) : Directory {
            override val icon = Icons.TwoTone.DriveFileRenameOutline
            override val label = "Rename"
        }

        data class Copy(
            override val group: Group = Group.PRIMARY,
        ) : Directory {
            override val icon = Icons.TwoTone.ContentCopy
            override val label = "Copy"
        }

        data class Cut(
            override val isEnabled: Boolean = true,
            override val group: Group = Group.PRIMARY,
        ) : Directory {
            override val icon = Icons.TwoTone.ContentCut
            override val label = "Cut"
        }

        data class Delete(
            override val isEnabled: Boolean = true,
            override val group: Group = Group.PRIMARY,
        ) : Directory {
            override val icon = Icons.TwoTone.Delete
            override val label = "Delete"
            override val isDestructive = true
        }

        data class Share(
            override val group: Group = Group.PRIMARY,
        ) : Directory {
            override val icon = Icons.TwoTone.Share
            override val label = "Share"
        }

        data class Paste(
            override val isEnabled: Boolean = true,
            override val group: Group = Group.PRIMARY,
        ) : Directory {
            override val icon = Icons.TwoTone.ContentPaste
            override val label = "Paste"
        }

        object SelectAll : Directory {
            override val icon = Icons.TwoTone.SelectAll
            override val label = "Select All"
        }
    }

    sealed interface Device : ExplorerAction {

    }

    sealed interface Home : ExplorerAction {

    }
}