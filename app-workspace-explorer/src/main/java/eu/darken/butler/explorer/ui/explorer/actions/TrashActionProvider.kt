package eu.darken.butler.explorer.ui.explorer.actions

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.twotone.DeleteForever
import androidx.compose.material.icons.twotone.DeleteSweep
import androidx.compose.material.icons.twotone.Restore
import eu.darken.butler.explorer.R
import eu.darken.butler.explorer.core.ExplorerViewStyle
import eu.darken.butler.explorer.core.engine.ExplorerLocation
import eu.darken.butler.explorer.ui.explorer.ExplorerSelectionState
import javax.inject.Inject

class TrashActionProvider @Inject constructor() : ExplorerActionProvider {

    override fun getActions(
        location: ExplorerLocation,
        selectionState: ExplorerSelectionState,
        viewStyle: ExplorerViewStyle,
    ): List<ExplorerAction> {
        if (location !is ExplorerLocation.Trash) return emptyList()

        val actions = mutableListOf<ExplorerAction>()

        // Show restore action if items are selected
        if (selectionState.selectedItems.isNotEmpty()) {
            actions.add(
                ExplorerAction.Trash.RestoreSelected(
                    icon = Icons.TwoTone.Restore,
                    labelRes = R.string.explorer_trash_restore_selected_action,
                    isEnabled = true,
                )
            )
        }

        // Show delete permanently action if items are selected
        if (selectionState.selectedItems.isNotEmpty()) {
            actions.add(
                ExplorerAction.Trash.DeletePermanentlySelected(
                    icon = Icons.TwoTone.DeleteForever,
                    labelRes = R.string.explorer_trash_delete_selected_action,
                    isEnabled = true,
                )
            )
        }

        // Show empty trash action only when nothing is selected
        if (selectionState.selectedItems.isEmpty()) {
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
}
