package eu.darken.butler.explorer.ui.explorer.actions

import eu.darken.butler.common.files.APath
import eu.darken.butler.explorer.core.ExplorerViewStyle
import eu.darken.butler.explorer.core.engine.ExplorerLocation
import eu.darken.butler.explorer.ui.explorer.util.ExplorerSelectionState
import javax.inject.Inject

class HomeActionProvider @Inject constructor() : ExplorerActionProvider {

    override fun getActions(
        location: ExplorerLocation,
        selectionState: ExplorerSelectionState,
        viewStyle: ExplorerViewStyle,
        trashEnabled: Boolean,
        favoriteSelection: Set<APath<*>>,
    ): List<ExplorerActionBarItem> {
        val actions = mutableListOf<ExplorerActionBarItem>()

        if (favoriteSelection.isNotEmpty()) {
            actions.add(ExplorerActionBarItem.Common.RemoveFromFavorites(favoriteSelection.toList()))
            // One name per favorite, so naming needs exactly one of them, as with Device.RenameLocation.
            if (favoriteSelection.size == 1) {
                actions.add(ExplorerActionBarItem.Common.RenameFavorite(favoriteSelection.single()))
            }
        } else if (!selectionState.isSelectionMode) {
            actions.add(ExplorerActionBarItem.Common.Sort())
            actions.add(ExplorerActionBarItem.Common.Filter())

            actions.add(ExplorerActionBarItem.Common.ViewOptions(viewStyle))
            actions.add(ExplorerActionBarItem.Common.Refresh())
        }

        return actions
    }
}