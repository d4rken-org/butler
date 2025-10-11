package eu.darken.butler.explorer.ui.explorer.actions

import eu.darken.butler.explorer.core.engine.ExplorerItem
import eu.darken.butler.explorer.core.engine.ExplorerLocation
import eu.darken.butler.explorer.ui.explorer.ExplorerSelectionState
import javax.inject.Inject

class DeviceActionProvider @Inject constructor() : ExplorerActionProvider {

    override fun getActions(
        location: ExplorerLocation,
        selectionState: ExplorerSelectionState,
    ): List<ExplorerAction> {
        val actions = mutableListOf<ExplorerAction>()

        if (selectionState.selectedItems.isNotEmpty()) {
            // Check if selected items are SAF storage items
            val selectedSAFItems = location.items
                ?.filterIsInstance<ExplorerItem.Storage.SAF>()
                ?.filter { it.id in selectionState.selectedItems }
                ?: emptyList()

            if (selectedSAFItems.size == 1) {
                actions.add(ExplorerAction.Device.RenameLocation())
            }

            if (selectedSAFItems.isNotEmpty()) {
                actions.add(ExplorerAction.Device.RemoveLocation())
            }
        } else {
            actions.add(ExplorerAction.Device.AddLocation())
        }

        actions.add(ExplorerAction.Common.Refresh())
        actions.add(ExplorerAction.Common.Sort())
        actions.add(ExplorerAction.Common.ToggleView())

        return actions
    }
}