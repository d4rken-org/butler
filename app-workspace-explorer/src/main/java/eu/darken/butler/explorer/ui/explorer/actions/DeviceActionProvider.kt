package eu.darken.butler.explorer.ui.explorer.actions

import eu.darken.butler.explorer.core.ExplorerViewStyle
import eu.darken.butler.explorer.core.engine.ExplorerItem
import eu.darken.butler.explorer.core.engine.ExplorerLocation
import eu.darken.butler.explorer.core.favorites.ExplorerFavoritesRepo
import eu.darken.butler.explorer.core.toggled
import eu.darken.butler.explorer.ui.explorer.util.ExplorerSelectionState
import javax.inject.Inject

class DeviceActionProvider @Inject constructor(
    private val favoritesRepo: ExplorerFavoritesRepo,
) : ExplorerActionProvider {

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

            addFavoritesSelectionAction(actions, selectionState)
        } else {
            actions.add(ExplorerActionBarItem.Device.AddLocation())
            actions.add(ExplorerActionBarItem.Common.Sort())
            actions.add(ExplorerActionBarItem.Common.Filter())

            actions.add(ExplorerActionBarItem.Common.UpdateViewStyle(viewStyle.toggled()))
        }

        actions.add(ExplorerActionBarItem.Common.Refresh())

        return actions
    }

    private fun addFavoritesSelectionAction(
        actions: MutableList<ExplorerActionBarItem>,
        selectionState: ExplorerSelectionState,
    ) {
        val storages = selectionState.selectedItems.filterIsInstance<ExplorerItem.Storage>()
        if (storages.isEmpty()) return
        val paths = storages.map { it.target.path }
        val allFavorited = paths.all { favoritesRepo.isFavorite(it) }
        if (allFavorited) {
            actions.add(ExplorerActionBarItem.Common.RemoveFromFavorites(paths))
        } else {
            val toAdd = paths.filterNot { favoritesRepo.isFavorite(it) }
            actions.add(ExplorerActionBarItem.Common.AddToFavorites(items = toAdd))
        }
    }
}