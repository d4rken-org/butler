package eu.darken.butler.explorer.ui.explorer.actions

import eu.darken.butler.common.files.APath
import eu.darken.butler.explorer.core.ExplorerViewStyle
import eu.darken.butler.explorer.core.engine.ExplorerItem
import eu.darken.butler.explorer.core.engine.ExplorerLocation
import eu.darken.butler.explorer.ui.explorer.util.ExplorerSelectionState
import eu.darken.butler.workspace.ui.actions.FileActionCapabilities
import javax.inject.Inject

/**
 * Actions for the Recent listing.
 *
 * Recent is an index of files scattered across storage, not a folder: there is no destination to
 * create in, paste into, compress to or extract into, and its order is the loader's rather than the
 * sorter's, so none of those actions are offered. What acts on the selected paths themselves is.
 */
class RecentActionProvider @Inject constructor() : ExplorerActionProvider {

    override fun getActions(
        location: ExplorerLocation,
        selectionState: ExplorerSelectionState,
        viewStyle: ExplorerViewStyle,
        trashEnabled: Boolean,
        favoriteSelection: Set<APath<*>>,
    ): List<ExplorerActionBarItem> {
        val actions = mutableListOf<ExplorerActionBarItem>()

        if (selectionState.isSelectionMode) {
            if (!selectionState.isAllSelected) {
                actions.add(ExplorerActionBarItem.Directory.SelectAll)
            }

            actions.add(ExplorerActionBarItem.Directory.OpenInNewTabs())

            if (selectionState.selectionCount == 1) {
                actions.add(ExplorerActionBarItem.Directory.Rename())
            }

            actions.add(ExplorerActionBarItem.Directory.Copy())

            // No location-level writability to gate on: the selected rows answer for themselves,
            // and an unknown (null) canWrite is treated as writable, as ExplorerItem.Lookup says.
            val selectionIsWritable = selectionState.selectedItems.none {
                it is ExplorerItem.Lookup && it.canWrite == false
            }

            actions.add(ExplorerActionBarItem.Directory.Cut(isEnabled = selectionIsWritable))

            actions.add(
                ExplorerActionBarItem.Directory.Delete(
                    isEnabled = selectionIsWritable,
                    trashEnabled = trashEnabled,
                )
            )

            // Handing a file to another app needs a URI the system can resolve, which is what
            // FileActionCapabilities answers.
            val selectionIsShareable = selectionState.selectedItems.all {
                it is ExplorerItem.File && FileActionCapabilities.canHandOffToOtherApps(it.path)
            }
            if (selectionIsShareable) {
                actions.add(ExplorerActionBarItem.Directory.Share())
            }

            actions.add(ExplorerActionBarItem.Common.Info())
        } else {
            actions.add(ExplorerActionBarItem.Common.Refresh())

            // null = still loading -> not treated as empty.
            val isEmpty = location.items?.isEmpty() == true
            if (!isEmpty) {
                actions.add(ExplorerActionBarItem.Common.Filter())
                actions.add(ExplorerActionBarItem.Common.ViewOptions(viewStyle))
            }
        }

        return actions
    }
}
