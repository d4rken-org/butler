package eu.darken.butler.explorer.ui.explorer.actions

import eu.darken.butler.explorer.core.ExplorerViewStyle
import eu.darken.butler.explorer.core.engine.ExplorerItem
import eu.darken.butler.explorer.core.engine.ExplorerLocation
import eu.darken.butler.explorer.ui.explorer.util.ExplorerSelectionState
import javax.inject.Inject

class DeviceActionProvider @Inject constructor() : ExplorerActionProvider {

    override fun getActions(
        location: ExplorerLocation,
        selectionState: ExplorerSelectionState,
        viewStyle: ExplorerViewStyle,
        trashEnabled: Boolean,
    ): List<ExplorerActionBarItem> {
        val actions = mutableListOf<ExplorerActionBarItem>()

        if (selectionState.selectedItems.isNotEmpty()) {
            // Check if selected items are SAF storage items
            val selectedSAFItems = selectionState.selectedItems
                .filterIsInstance<ExplorerItem.Storage.SAF>()

            // Check if any storage items are selected (for opening in new tabs)
            val hasStorageItems = selectionState.selectedItems.any { it is ExplorerItem.Storage }
            if (hasStorageItems) {
                actions.add(ExplorerActionBarItem.Directory.OpenInNewTabs())
            }

            actions.add(ExplorerActionBarItem.Common.Info())

            if (selectedSAFItems.size == 1) {
                actions.add(ExplorerActionBarItem.Device.RenameLocation())
            }

            if (selectedSAFItems.isNotEmpty()) {
                actions.add(ExplorerActionBarItem.Device.RemoveLocation())
            }
        } else {
            actions.add(ExplorerActionBarItem.Device.AddLocation())
        }

        actions.add(ExplorerActionBarItem.Common.Refresh())
        actions.add(ExplorerActionBarItem.Common.Sort())
        actions.add(ExplorerActionBarItem.Common.Filter())

        val toggledViewStyle = when (viewStyle) {
            is ExplorerViewStyle.List -> ExplorerViewStyle.Grid()
            is ExplorerViewStyle.Grid -> ExplorerViewStyle.List()
        }
        actions.add(ExplorerActionBarItem.Common.UpdateViewStyle(toggledViewStyle))

        return actions
    }
}