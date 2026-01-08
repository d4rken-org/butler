package eu.darken.butler.explorer.ui.explorer.actions

import eu.darken.butler.explorer.core.ExplorerViewStyle
import eu.darken.butler.explorer.core.engine.ExplorerItem
import eu.darken.butler.explorer.core.engine.ExplorerLocation
import eu.darken.butler.explorer.ui.explorer.util.ExplorerSelectionState
import javax.inject.Inject

class DirectoryActionProvider @Inject constructor() : ExplorerActionProvider {

    override fun getActions(
        location: ExplorerLocation,
        selectionState: ExplorerSelectionState,
        viewStyle: ExplorerViewStyle,
        trashEnabled: Boolean,
    ): List<ExplorerActionBarItem> {
        val actions = mutableListOf<ExplorerActionBarItem>()

        val directory = location as? ExplorerLocation.Directory
        val isWritable = (directory?.info?.isWritable ?: false)

        if (selectionState.isSelectionMode) {
            if (!selectionState.isAllSelected) {
                actions.add(ExplorerActionBarItem.Directory.SelectAll)
            }

            actions.add(ExplorerActionBarItem.Directory.OpenInNewTabs())

            if (selectionState.selectionCount == 1) {
                actions.add(ExplorerActionBarItem.Directory.Rename())
            }

            actions.add(ExplorerActionBarItem.Directory.Copy())

            actions.add(
                ExplorerActionBarItem.Directory.Cut(
                    isEnabled = isWritable,
                )
            )

            actions.add(
                ExplorerActionBarItem.Directory.Delete(
                    isEnabled = isWritable,
                    trashEnabled = trashEnabled,
                )
            )

            if (selectionState.selectedItems.all { it is ExplorerItem.File }) {
                actions.add(ExplorerActionBarItem.Directory.Share())
            }
            actions.add(ExplorerActionBarItem.Common.Info())
        } else {
            actions.add(
                ExplorerActionBarItem.Directory.Create(
                    isEnabled = isWritable,
                )
            )

            actions.add(ExplorerActionBarItem.Common.Refresh())
            actions.add(ExplorerActionBarItem.Common.Sort())
            actions.add(ExplorerActionBarItem.Common.Filter())

            val toggledViewStyle = when (viewStyle) {
                is ExplorerViewStyle.List -> ExplorerViewStyle.Grid()
                is ExplorerViewStyle.Grid -> ExplorerViewStyle.List()
            }
            actions.add(ExplorerActionBarItem.Common.UpdateViewStyle(toggledViewStyle))
        }

        return actions
    }
}