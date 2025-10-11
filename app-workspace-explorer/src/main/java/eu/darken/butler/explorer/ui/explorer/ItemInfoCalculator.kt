package eu.darken.butler.explorer.ui.explorer

import eu.darken.butler.explorer.core.engine.ExplorerItem
import eu.darken.butler.explorer.ui.explorer.dialogs.ExplorerDialogState
import javax.inject.Inject

class ItemInfoCalculator @Inject constructor() {

    fun calculateInfo(
        selectedItems: List<ExplorerItem>,
        allItems: List<ExplorerItem>?,
    ): ExplorerDialogState.ItemInfo.InfoContext? {
        if (selectedItems.isEmpty()) return null

        return when {
            selectedItems.size == 1 -> {
                when (val item = selectedItems.first()) {
                    is ExplorerItem.File -> ExplorerDialogState.ItemInfo.InfoContext.SingleFile(item)
                    is ExplorerItem.Directory -> ExplorerDialogState.ItemInfo.InfoContext.SingleDirectory(item)
                    is ExplorerItem.Storage.SAF -> ExplorerDialogState.ItemInfo.InfoContext.SingleSAF(item)
                    else -> null
                }
            }
            selectedItems.size > 1 -> {
                val fileCount = selectedItems.count { it is ExplorerItem.File }
                val directoryCount = selectedItems.count { it is ExplorerItem.Directory }

                // Calculate total size - only for items that have size information
                val totalSize = selectedItems
                    .filterIsInstance<ExplorerItem.Lookup>()
                    .sumOf { it.lookup.size }

                ExplorerDialogState.ItemInfo.InfoContext.MultipleItems(
                    selectedItems = selectedItems,
                    fileCount = fileCount,
                    directoryCount = directoryCount,
                    totalSize = if (totalSize > 0) totalSize else null,
                )
            }
            else -> null
        }
    }
}
