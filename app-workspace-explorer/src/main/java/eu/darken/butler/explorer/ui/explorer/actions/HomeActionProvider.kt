package eu.darken.butler.explorer.ui.explorer.actions

import eu.darken.butler.explorer.core.ExplorerViewStyle
import eu.darken.butler.explorer.core.engine.ExplorerLocation
import eu.darken.butler.explorer.core.toggled
import eu.darken.butler.explorer.ui.explorer.util.ExplorerSelectionState
import javax.inject.Inject

class HomeActionProvider @Inject constructor() : ExplorerActionProvider {

    override fun getActions(
        location: ExplorerLocation,
        selectionState: ExplorerSelectionState,
        viewStyle: ExplorerViewStyle,
        trashEnabled: Boolean,
    ): List<ExplorerActionBarItem> {
        val actions = mutableListOf<ExplorerActionBarItem>()

        if (!selectionState.isSelectionMode) {
            actions.add(ExplorerActionBarItem.Common.Sort())
            actions.add(ExplorerActionBarItem.Common.Filter())

            actions.add(ExplorerActionBarItem.Common.UpdateViewStyle(viewStyle.toggled()))
        }

        actions.add(ExplorerActionBarItem.Common.Refresh())

        return actions
    }
}