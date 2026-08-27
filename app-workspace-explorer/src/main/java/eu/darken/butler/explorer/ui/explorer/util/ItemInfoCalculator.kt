package eu.darken.butler.explorer.ui.explorer.util

import eu.darken.butler.explorer.core.engine.ExplorerItem
import eu.darken.butler.explorer.ui.explorer.dialogs.ExplorerDialogState
import javax.inject.Inject

class ItemInfoCalculator @Inject constructor() {

    fun calculateInfo(
        selectedItems: List<ExplorerItem>,
        allItems: List<ExplorerItem>?,
    ): ExplorerDialogState.ItemInfo.InfoContext? {
        if (selectedItems.isEmpty()) return null

        // Selections are snapshots taken before extended data was loaded, resolve against the live list
        val items = selectedItems.map { selected -> allItems?.firstOrNull { it.id == selected.id } ?: selected }

        return when {
            items.size == 1 -> {
                when (val item = items.first()) {
                    is ExplorerItem.File -> ExplorerDialogState.ItemInfo.InfoContext.SingleFile(item)
                    is ExplorerItem.Directory -> ExplorerDialogState.ItemInfo.InfoContext.SingleDirectory(item)
                    is ExplorerItem.Storage.SAF -> ExplorerDialogState.ItemInfo.InfoContext.SingleSAF(item)
                    is ExplorerItem.Storage.Local -> ExplorerDialogState.ItemInfo.InfoContext.SingleLocalStorage(item)
                    is ExplorerItem.Storage.Network -> {
                        ExplorerDialogState.ItemInfo.InfoContext.SingleNetwork(item.location.id)
                    }
                    else -> null
                }
            }

            items.size > 1 -> {
                val fileCount = items.count { it is ExplorerItem.File }
                val directoryCount = items.count { it is ExplorerItem.Directory }

                // Calculate total size - only for items that have size information
                val totalSize = items
                    .filterIsInstance<ExplorerItem.Lookup>()
                    .mapNotNull { it.lookup.size }
                    .sum()

                ExplorerDialogState.ItemInfo.InfoContext.MultipleItems(
                    selectedItems = items,
                    fileCount = fileCount,
                    directoryCount = directoryCount,
                    totalSize = if (totalSize > 0) totalSize else null,
                )
            }

            else -> null
        }
    }
}