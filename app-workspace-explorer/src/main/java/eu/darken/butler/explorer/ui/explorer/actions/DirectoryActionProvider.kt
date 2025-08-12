package eu.darken.butler.explorer.ui.explorer.actions

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
            actions.add(ExplorerAction.Directory.SelectionInfo(selectionState.selectionCount))
            
            actions.add(ExplorerAction.Directory.Copy())
            
            actions.add(
                ExplorerAction.Directory.Cut(
                    isEnabled = canModify,
                )
            )
            
            actions.add(
                ExplorerAction.Directory.Delete(
                    isEnabled = canModify,
                )
            )
            
            actions.add(ExplorerAction.Directory.Share())
            
            actions.add(ExplorerAction.Common.More())
        } else {
            actions.add(
                ExplorerAction.Directory.CreateFolder(
                    isEnabled = canModify,
                )
            )
            
            if (selectionState.hasClipboard) {
                actions.add(
                    ExplorerAction.Directory.Paste(
                        isEnabled = canModify,
                    )
                )
            }
            
            actions.add(ExplorerAction.Common.Sort())
            actions.add(ExplorerAction.Common.Filter())
            actions.add(ExplorerAction.Common.ToggleView())
            actions.add(ExplorerAction.Common.More())
        }
        
        return actions
    }
}