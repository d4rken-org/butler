package eu.darken.butler.explorer.ui.explorer.actions

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.twotone.DeleteForever
import androidx.compose.material.icons.twotone.DeleteSweep
import androidx.compose.material.icons.twotone.Restore
import eu.darken.butler.explorer.R
import eu.darken.butler.explorer.core.ExplorerViewStyle
import eu.darken.butler.explorer.core.engine.ExplorerItem
import eu.darken.butler.explorer.core.engine.ExplorerLocation
import eu.darken.butler.explorer.core.toggled
import eu.darken.butler.explorer.ui.explorer.util.ExplorerSelectionState
import javax.inject.Inject

/**
 * Unified action provider for both root trash view and nested trash browsing.
 * Handles read-only actions: restore and delete permanently.
 * Root trash also has EmptyBin action.
 */
class TrashActionProvider @Inject constructor() : ExplorerActionProvider {

    override fun getActions(
        location: ExplorerLocation,
        selectionState: ExplorerSelectionState,
        viewStyle: ExplorerViewStyle,
        trashEnabled: Boolean,
    ): List<ExplorerActionBarItem> = when (location) {
        is ExplorerLocation.Trash.Root -> getRootActions(location, selectionState, viewStyle)
        is ExplorerLocation.Trash.Nested -> getNestedActions(location, selectionState, viewStyle)
        else -> emptyList()
    }

    private fun getRootActions(
        location: ExplorerLocation.Trash.Root,
        selectionState: ExplorerSelectionState,
        viewStyle: ExplorerViewStyle,
    ): List<ExplorerActionBarItem> {
        val actions = mutableListOf<ExplorerActionBarItem>()

        if (selectionState.isSelectionMode) {
            if (!selectionState.isAllSelected) {
                actions.add(ExplorerActionBarItem.Trash.SelectAll)
            }

            val selectedRootItems = selectionState.selectedItems.filterIsInstance<ExplorerItem.Trash.Root>()

            actions.add(
                ExplorerActionBarItem.Trash.Restore(
                    items = selectedRootItems,
                    icon = Icons.TwoTone.Restore,
                    labelRes = R.string.explorer_trash_restore_selected_action,
                    isEnabled = selectedRootItems.isNotEmpty(),
                )
            )

            actions.add(
                ExplorerActionBarItem.Trash.DeletePermanently(
                    items = selectedRootItems,
                    icon = Icons.TwoTone.DeleteForever,
                    labelRes = R.string.explorer_trash_delete_selected_action,
                    isEnabled = selectedRootItems.isNotEmpty(),
                )
            )
        } else {
            // null = still loading -> not treated as empty.
            val isEmpty = location.items?.isEmpty() == true

            actions.add(ExplorerActionBarItem.Common.Refresh())
            // Sort/filter/view-style have no effect on an empty trash.
            if (!isEmpty) {
                actions.add(ExplorerActionBarItem.Common.Sort())
                actions.add(ExplorerActionBarItem.Common.Filter())
                actions.add(ExplorerActionBarItem.Common.UpdateViewStyle(viewStyle.toggled()))
            }

            actions.add(
                ExplorerActionBarItem.Trash.EmptyBin(
                    icon = Icons.TwoTone.DeleteSweep,
                    labelRes = R.string.explorer_trash_empty_trash_action,
                    isEnabled = location.info?.itemCount?.let { it > 0 } ?: false,
                )
            )
        }

        return actions
    }

    private fun getNestedActions(
        location: ExplorerLocation.Trash.Nested,
        selectionState: ExplorerSelectionState,
        viewStyle: ExplorerViewStyle,
    ): List<ExplorerActionBarItem> {
        val actions = mutableListOf<ExplorerActionBarItem>()

        if (selectionState.isSelectionMode) {
            if (!selectionState.isAllSelected) {
                actions.add(ExplorerActionBarItem.TrashNested.SelectAll)
            }

            val selectedNestedItems = selectionState.selectedItems.filterIsInstance<ExplorerItem.Trash.Nested>()

            actions.add(
                ExplorerActionBarItem.TrashNested.Restore(
                    items = selectedNestedItems,
                    icon = Icons.TwoTone.Restore,
                    labelRes = R.string.explorer_trash_restore_selected_action,
                    isEnabled = selectedNestedItems.isNotEmpty(),
                )
            )

            actions.add(
                ExplorerActionBarItem.TrashNested.DeletePermanently(
                    items = selectedNestedItems,
                    icon = Icons.TwoTone.DeleteForever,
                    labelRes = R.string.explorer_trash_delete_selected_action,
                    isEnabled = selectedNestedItems.isNotEmpty(),
                )
            )
        } else {
            // null = still loading -> not treated as empty.
            val isEmpty = location.items?.isEmpty() == true

            actions.add(ExplorerActionBarItem.Common.Refresh())
            // Sort/filter/view-style have no effect on an empty trash folder.
            if (!isEmpty) {
                actions.add(ExplorerActionBarItem.Common.Sort())
                actions.add(ExplorerActionBarItem.Common.Filter())
                actions.add(ExplorerActionBarItem.Common.UpdateViewStyle(viewStyle.toggled()))
            }
        }

        return actions
    }
}
