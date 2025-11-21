package eu.darken.butler.explorer.ui.explorer.actions

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.twotone.DeleteForever
import androidx.compose.material.icons.twotone.Restore
import eu.darken.butler.explorer.R
import eu.darken.butler.explorer.core.engine.ExplorerLocation
import eu.darken.butler.explorer.ui.explorer.ExplorerSelectionState
import javax.inject.Inject

class RecycleBinActionProvider @Inject constructor() : ExplorerActionProvider {

    override fun getActions(
        location: ExplorerLocation,
        selectionState: ExplorerSelectionState,
    ): List<ExplorerAction> {
        if (location !is ExplorerLocation.RecycleBin) return emptyList()

        val actions = mutableListOf<ExplorerAction>()

        // Show restore action if items are selected
        if (selectionState.selectedItems.isNotEmpty()) {
            actions.add(
                ExplorerAction.RecycleBin.RestoreSelected(
                    icon = Icons.TwoTone.Restore,
                    labelRes = R.string.explorer_recyclebin_restore_selected_action,
                    isEnabled = true,
                )
            )
        }

        // Show delete permanently action if items are selected
        if (selectionState.selectedItems.isNotEmpty()) {
            actions.add(
                ExplorerAction.RecycleBin.DeletePermanentlySelected(
                    icon = Icons.TwoTone.DeleteForever,
                    labelRes = R.string.explorer_recyclebin_delete_selected_action,
                    isEnabled = true,
                )
            )
        }

        // Always show empty bin action
        actions.add(
            ExplorerAction.RecycleBin.EmptyBin(
                icon = Icons.TwoTone.DeleteForever,
                labelRes = R.string.explorer_recyclebin_empty_bin_action,
                isEnabled = location.info?.itemCount?.let { it > 0 } ?: false,
            )
        )

        return actions
    }
}
