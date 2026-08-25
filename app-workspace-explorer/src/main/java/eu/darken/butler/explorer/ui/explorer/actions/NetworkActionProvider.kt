package eu.darken.butler.explorer.ui.explorer.actions

import eu.darken.butler.explorer.core.ExplorerViewStyle
import eu.darken.butler.explorer.core.engine.ExplorerItem
import eu.darken.butler.explorer.core.engine.ExplorerLocation
import eu.darken.butler.explorer.core.toggled
import eu.darken.butler.explorer.ui.explorer.util.ExplorerSelectionState
import javax.inject.Inject

class NetworkActionProvider @Inject constructor() : ExplorerActionProvider {

    override fun getActions(
        location: ExplorerLocation,
        selectionState: ExplorerSelectionState,
        viewStyle: ExplorerViewStyle,
        trashEnabled: Boolean,
    ): List<ExplorerActionBarItem> {
        val actions = mutableListOf<ExplorerActionBarItem>()

        val selectedNetworkItems = selectionState.selectedItems
            .filterIsInstance<ExplorerItem.Storage.Network>()

        if (selectionState.selectedItems.isNotEmpty()) {
            if (selectedNetworkItems.size == 1) {
                actions.add(ExplorerActionBarItem.Network.EditLocation())
                actions.add(ExplorerActionBarItem.Network.RenameLocation())
                actions.add(ExplorerActionBarItem.Common.Info())
            }

            if (selectedNetworkItems.isNotEmpty()) {
                actions.add(ExplorerActionBarItem.Network.RemoveLocation())
            }
        } else {
            actions.add(ExplorerActionBarItem.Network.AddLocation())
        }

        actions.add(ExplorerActionBarItem.Common.Refresh())
        actions.add(ExplorerActionBarItem.Common.Sort())
        actions.add(ExplorerActionBarItem.Common.Filter())

        actions.add(ExplorerActionBarItem.Common.UpdateViewStyle(viewStyle.toggled()))

        return actions
    }
}
