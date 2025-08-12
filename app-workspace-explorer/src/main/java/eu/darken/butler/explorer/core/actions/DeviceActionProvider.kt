package eu.darken.butler.explorer.core.actions

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.twotone.Sort
import androidx.compose.material.icons.twotone.FilterList
import androidx.compose.material.icons.twotone.GridView
import androidx.compose.material.icons.twotone.MoreVert
import androidx.compose.material.icons.twotone.Refresh
import androidx.compose.material.icons.twotone.Storage
import eu.darken.butler.explorer.core.engine.ExplorerLocation
import javax.inject.Inject

class DeviceActionProvider @Inject constructor() : ExplorerActionProvider {
    
    override fun getActions(
        location: ExplorerLocation,
        selectionState: ExplorerActionProvider.SelectionState,
        capabilities: ExplorerActionProvider.LocationCapabilities,
    ): List<ExplorerAction> {
        val actions = mutableListOf<ExplorerAction>()
        
        actions.add(
            ExplorerAction(
                id = "refresh",
                icon = Icons.TwoTone.Refresh,
                label = "Refresh",
                group = ExplorerAction.Group.PRIMARY,
            )
        )
        
        actions.add(
            ExplorerAction(
                id = "storage_info",
                icon = Icons.TwoTone.Storage,
                label = "Storage",
                group = ExplorerAction.Group.PRIMARY,
            )
        )
        
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
        
        return actions
    }
}