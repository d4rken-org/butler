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
    ): List<ExplorerAction> {
        val actions = mutableListOf<ExplorerAction>()

        val directory = location as? ExplorerLocation.Directory
        val isWritable = (directory?.info?.isWritable ?: false)

        if (selectionState.isSelectionMode) {
            if (!selectionState.isAllSelected) {
                actions.add(ExplorerAction.Directory.SelectAll)
            }

            actions.add(ExplorerAction.Directory.OpenInNewTabs())

            if (selectionState.selectionCount == 1) {
                actions.add(ExplorerAction.Directory.Rename())
            }

            actions.add(ExplorerAction.Directory.Copy())

            actions.add(
                ExplorerAction.Directory.Cut(
                    isEnabled = isWritable,
                )
            )

            actions.add(
                ExplorerAction.Directory.Delete(
                    isEnabled = isWritable,
                    trashEnabled = trashEnabled,
                )
            )

            if (selectionState.selectedItems.all { it is ExplorerItem.File }) {
                actions.add(ExplorerAction.Directory.Share())
            }
            actions.add(ExplorerAction.Common.Info())
        } else {
            actions.add(
                ExplorerAction.Directory.Create(
                    isEnabled = isWritable,
                )
            )

            actions.add(ExplorerAction.Common.Refresh())
            actions.add(ExplorerAction.Common.Sort())
            actions.add(ExplorerAction.Common.Filter())

            val toggledViewStyle = when (viewStyle) {
                is ExplorerViewStyle.List -> ExplorerViewStyle.Grid()
                is ExplorerViewStyle.Grid -> ExplorerViewStyle.List()
            }
            actions.add(ExplorerAction.Common.UpdateViewStyle(toggledViewStyle))
        }

        return actions
    }
}