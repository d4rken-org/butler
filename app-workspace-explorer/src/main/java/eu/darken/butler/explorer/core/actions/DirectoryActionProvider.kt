package eu.darken.butler.explorer.core.actions

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.twotone.Sort
import androidx.compose.material.icons.twotone.ContentCopy
import androidx.compose.material.icons.twotone.ContentCut
import androidx.compose.material.icons.twotone.ContentPaste
import androidx.compose.material.icons.twotone.CreateNewFolder
import androidx.compose.material.icons.twotone.Delete
import androidx.compose.material.icons.twotone.FilterList
import androidx.compose.material.icons.twotone.GridView
import androidx.compose.material.icons.twotone.MoreVert
import androidx.compose.material.icons.twotone.SelectAll
import androidx.compose.material.icons.twotone.Share
import androidx.compose.material.icons.twotone.ViewList
import eu.darken.butler.explorer.core.engine.ExplorerLocation
import javax.inject.Inject

class DirectoryActionProvider @Inject constructor() : ExplorerActionProvider {
    
    override fun getActions(
        location: ExplorerLocation,
        selectionState: ExplorerActionProvider.SelectionState,
        capabilities: ExplorerActionProvider.LocationCapabilities,
    ): List<ExplorerAction> {
        val actions = mutableListOf<ExplorerAction>()
        
        val directory = location as? ExplorerLocation.Directory
        val isWritable = (directory?.info?.isWritable ?: false) || capabilities.canWrite
        val canModify = isWritable || capabilities.hasRootAccess || capabilities.hasAdbAccess
        
        if (selectionState.isSelectionMode) {
            actions.add(
                ExplorerAction(
                    id = "selection_info",
                    icon = Icons.TwoTone.SelectAll,
                    label = "${selectionState.selectionCount} selected",
                    group = ExplorerAction.Group.SELECTION_INFO,
                )
            )
            
            actions.add(
                ExplorerAction(
                    id = "copy",
                    icon = Icons.TwoTone.ContentCopy,
                    label = "Copy",
                    group = ExplorerAction.Group.PRIMARY,
                )
            )
            
            actions.add(
                ExplorerAction(
                    id = "cut",
                    icon = Icons.TwoTone.ContentCut,
                    label = "Cut",
                    isEnabled = canModify,
                    group = ExplorerAction.Group.PRIMARY,
                )
            )
            
            actions.add(
                ExplorerAction(
                    id = "delete",
                    icon = Icons.TwoTone.Delete,
                    label = "Delete",
                    isEnabled = canModify,
                    isDestructive = true,
                    group = ExplorerAction.Group.PRIMARY,
                )
            )
            
            actions.add(
                ExplorerAction(
                    id = "share",
                    icon = Icons.TwoTone.Share,
                    label = "Share",
                    group = ExplorerAction.Group.PRIMARY,
                )
            )
            
            actions.add(
                ExplorerAction(
                    id = "more",
                    icon = Icons.TwoTone.MoreVert,
                    label = "More",
                    group = ExplorerAction.Group.OVERFLOW,
                )
            )
        } else {
            actions.add(
                ExplorerAction(
                    id = "create_folder",
                    icon = Icons.TwoTone.CreateNewFolder,
                    label = "New folder",
                    isEnabled = canModify,
                    group = ExplorerAction.Group.PRIMARY,
                )
            )
            
            if (selectionState.hasClipboard) {
                actions.add(
                    ExplorerAction(
                        id = "paste",
                        icon = Icons.TwoTone.ContentPaste,
                        label = "Paste",
                        isEnabled = canModify,
                        group = ExplorerAction.Group.PRIMARY,
                    )
                )
            }
            
            actions.add(
                ExplorerAction(
                    id = "sort",
                    icon = Icons.AutoMirrored.TwoTone.Sort,
                    label = "Sort",
                    group = ExplorerAction.Group.SECONDARY,
                )
            )
            
            actions.add(
                ExplorerAction(
                    id = "filter",
                    icon = Icons.TwoTone.FilterList,
                    label = "Filter",
                    group = ExplorerAction.Group.SECONDARY,
                )
            )
            
            actions.add(
                ExplorerAction(
                    id = "toggle_view",
                    icon = Icons.TwoTone.GridView,
                    label = "View",
                    group = ExplorerAction.Group.SECONDARY,
                )
            )
            
            actions.add(
                ExplorerAction(
                    id = "more",
                    icon = Icons.TwoTone.MoreVert,
                    label = "More",
                    group = ExplorerAction.Group.OVERFLOW,
                )
            )
        }
        
        return actions
    }
}