package eu.darken.butler.explorer.ui.explorer.actions

import eu.darken.butler.explorer.core.engine.ExplorerLocation
import eu.darken.butler.explorer.ui.explorer.ExplorerSelectionState
import javax.inject.Inject

class DirectoryActionProvider @Inject constructor() : ExplorerActionProvider {

    override fun getActions(
        location: ExplorerLocation,
        selectionState: ExplorerSelectionState,
    ): List<ExplorerAction> {
        val actions = mutableListOf<ExplorerAction>()

        val directory = location as? ExplorerLocation.Directory
        val isWritable = (directory?.info?.isWritable ?: false)

        if (selectionState.isSelectionMode) {
            actions.add(ExplorerAction.Directory.DeselectAll)

            if (!selectionState.isAllSelected) {
                actions.add(ExplorerAction.Directory.SelectAll)
            }

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
                )
            )

            actions.add(ExplorerAction.Directory.Share())
        } else {
            actions.add(
                ExplorerAction.Directory.Create(
                    isEnabled = isWritable,
                )
            )

            actions.add(ExplorerAction.Common.Sort())
            actions.add(ExplorerAction.Common.Filter())
            actions.add(ExplorerAction.Common.ToggleView())
        }

        return actions
    }
}