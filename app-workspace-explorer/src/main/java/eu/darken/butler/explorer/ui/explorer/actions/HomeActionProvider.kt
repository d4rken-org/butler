package eu.darken.butler.explorer.ui.explorer.actions

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
    ): List<ExplorerAction> {
        val actions = mutableListOf<ExplorerAction>()

        actions.add(ExplorerAction.Common.Refresh())
        actions.add(ExplorerAction.Common.Sort())
        actions.add(ExplorerAction.Common.Filter())

        val toggledViewStyle = when (viewStyle) {
            is ExplorerViewStyle.List -> ExplorerViewStyle.Grid()
            is ExplorerViewStyle.Grid -> ExplorerViewStyle.List()
        }
        actions.add(ExplorerAction.Common.UpdateViewStyle(toggledViewStyle))

        return actions
    }
}