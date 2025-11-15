package eu.darken.butler.explorer.ui.explorer.actions

import eu.darken.butler.explorer.core.ExplorerViewStyle
import eu.darken.butler.explorer.core.engine.ExplorerLocation
import eu.darken.butler.explorer.ui.explorer.ExplorerSelectionState

interface ExplorerActionProvider {

    fun getActions(
        location: ExplorerLocation,
        selectionState: ExplorerSelectionState,
        viewStyle: ExplorerViewStyle,
    ): List<ExplorerAction>

}