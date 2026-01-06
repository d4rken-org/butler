package eu.darken.butler.explorer.ui.explorer.actions

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.twotone.DeleteForever
import androidx.compose.material.icons.twotone.DeleteSweep
import androidx.compose.material.icons.twotone.Restore
import eu.darken.butler.explorer.R
import eu.darken.butler.explorer.core.ExplorerViewStyle
import eu.darken.butler.explorer.core.engine.ExplorerItem
import eu.darken.butler.explorer.core.engine.ExplorerLocation
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
    ): List<ExplorerAction> = when (location) {
        is ExplorerLocation.Trash.Root -> getRootActions(location, selectionState, viewStyle)
        is ExplorerLocation.Trash.Nested -> getNestedActions(selectionState, viewStyle)
        else -> emptyList()
    }

    private fun getRootActions(
        location: ExplorerLocation.Trash.Root,
        selectionState: ExplorerSelectionState,
        viewStyle: ExplorerViewStyle,
    ): List<ExplorerAction> {
        val actions = mutableListOf<ExplorerAction>()

        if (selectionState.isSelectionMode) {
            if (!selectionState.isAllSelected) {
                actions.add(ExplorerAction.Trash.SelectAll)
            }

            val selectedRootItems = selectionState.selectedItems.filterIsInstance<ExplorerItem.Trash.Root>()

            actions.add(
                ExplorerAction.Trash.Restore(
                    items = selectedRootItems,
                    icon = Icons.TwoTone.Restore,
                    labelRes = R.string.explorer_trash_restore_selected_action,
                    isEnabled = selectedRootItems.isNotEmpty(),
                )
            )

            actions.add(
                ExplorerAction.Trash.DeletePermanently(
                    items = selectedRootItems,
                    icon = Icons.TwoTone.DeleteForever,
                    labelRes = R.string.explorer_trash_delete_selected_action,
                    isEnabled = selectedRootItems.isNotEmpty(),
                )
            )
        } else {
            actions.add(ExplorerAction.Common.Refresh())
            actions.add(ExplorerAction.Common.Sort())
            actions.add(ExplorerAction.Common.Filter())
            actions.add(ExplorerAction.Common.UpdateViewStyle(viewStyle))

            actions.add(
                ExplorerAction.Trash.EmptyBin(
                    icon = Icons.TwoTone.DeleteSweep,
                    labelRes = R.string.explorer_trash_empty_trash_action,
                    isEnabled = location.info?.itemCount?.let { it > 0 } ?: false,
                )
            )
        }

        return actions
    }

    private fun getNestedActions(
        selectionState: ExplorerSelectionState,
        viewStyle: ExplorerViewStyle,
    ): List<ExplorerAction> {
        val actions = mutableListOf<ExplorerAction>()

        if (selectionState.isSelectionMode) {
            if (!selectionState.isAllSelected) {
                actions.add(ExplorerAction.TrashNested.SelectAll)
            }

            val selectedNestedItems = selectionState.selectedItems.filterIsInstance<ExplorerItem.Trash.Nested>()

            actions.add(
                ExplorerAction.TrashNested.Restore(
                    items = selectedNestedItems,
                    icon = Icons.TwoTone.Restore,
                    labelRes = R.string.explorer_trash_restore_selected_action,
                    isEnabled = selectedNestedItems.isNotEmpty(),
                )
            )

            actions.add(
                ExplorerAction.TrashNested.DeletePermanently(
                    items = selectedNestedItems,
                    icon = Icons.TwoTone.DeleteForever,
                    labelRes = R.string.explorer_trash_delete_selected_action,
                    isEnabled = selectedNestedItems.isNotEmpty(),
                )
            )
        } else {
            actions.add(ExplorerAction.Common.Refresh())
            actions.add(ExplorerAction.Common.Sort())
            actions.add(ExplorerAction.Common.Filter())
            actions.add(ExplorerAction.Common.UpdateViewStyle(viewStyle))
        }

        return actions
    }
}
