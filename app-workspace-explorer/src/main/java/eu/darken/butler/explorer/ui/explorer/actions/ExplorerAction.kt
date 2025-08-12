package eu.darken.butler.explorer.ui.explorer.actions

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.twotone.Sort
import androidx.compose.material.icons.twotone.ContentCopy
import androidx.compose.material.icons.twotone.ContentCut
import androidx.compose.material.icons.twotone.ContentPaste
import androidx.compose.material.icons.twotone.CreateNewFolder
import androidx.compose.material.icons.twotone.Delete
import androidx.compose.material.icons.twotone.FilterList
import androidx.compose.material.icons.twotone.GridView
import androidx.compose.material.icons.twotone.Info
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
        SELECTION_INFO,
        PRIMARY,
        SECONDARY,
        OVERFLOW,
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
        
        data class More(
            override val group: Group = Group.OVERFLOW,
        ) : Common {
            override val icon = Icons.TwoTone.MoreVert
            override val label = "More"
        }
    }
    
    // Directory-specific actions
    sealed interface Directory : ExplorerAction {
        data class CreateFolder(
            override val isEnabled: Boolean = true,
            override val group: Group = Group.PRIMARY,
        ) : Directory {
            override val icon = Icons.TwoTone.CreateNewFolder
            override val label = "New folder"
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
        
        data class SelectionInfo(
            val count: Int,
        ) : Directory {
            override val icon = Icons.TwoTone.SelectAll
            override val label = "$count selected"
            override val group = Group.SELECTION_INFO
        }
    }
    
    // Device-specific actions
    sealed interface Device : ExplorerAction {
        object StorageInfo : Device {
            override val icon = Icons.TwoTone.Storage
            override val label = "Storage"
            override val group = Group.PRIMARY
        }
    }
    
    // Home-specific actions
    sealed interface Home : ExplorerAction {
        object Settings : Home {
            override val icon = Icons.TwoTone.Settings
            override val label = "Settings"
            override val group = Group.OVERFLOW
        }
    }
}