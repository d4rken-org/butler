package eu.darken.butler.explorer.ui.explorer.actions

import eu.darken.butler.explorer.core.engine.ExplorerLocation
import javax.inject.Inject

class DeviceActionProvider @Inject constructor() : ExplorerActionProvider {
    
    override fun getActions(
        location: ExplorerLocation,
        selectionState: ExplorerActionProvider.SelectionState,
        capabilities: ExplorerActionProvider.LocationCapabilities,
    ): List<ExplorerAction> {
        val actions = mutableListOf<ExplorerAction>()
        
        actions.add(ExplorerAction.Common.Refresh())
        actions.add(ExplorerAction.Device.StorageInfo)
        actions.add(ExplorerAction.Common.Sort())
        actions.add(ExplorerAction.Common.Filter())
        actions.add(ExplorerAction.Common.ToggleView())
        actions.add(ExplorerAction.Common.More())
        
        return actions
    }
}