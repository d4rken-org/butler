package eu.darken.butler.explorer.ui.explorer.util

import eu.darken.butler.explorer.core.engine.ExplorerItem

data class ExplorerSelectionState(
    val selectableItems: Set<ExplorerItem> = emptySet(),
    val selectedItems: Set<ExplorerItem> = emptySet(),
) {
    val isSelectionMode: Boolean = selectedItems.isNotEmpty()
    val selectionCount: Int = selectedItems.size
    val isAllSelected: Boolean get() = selectableItems.size == selectedItems.size

    val selectedSize: Long?
        get() {
            val size = selectedItems
                .filterIsInstance<ExplorerItem.Lookup>()
                .mapNotNull { it.lookup.size }
                .sum()
            return if (size > 0) size else null
        }
}