package eu.darken.butler.explorer.ui.explorer

import eu.darken.butler.explorer.core.engine.ExplorerItem

data class ExplorerSelectionState(
    val selectableItems: Set<ExplorerItem> = emptySet(),
    val selectedItems: Set<ExplorerItem> = emptySet(),
) {
    val isSelectionMode: Boolean = selectedItems.isNotEmpty()
    val selectionCount: Int = selectedItems.size
    val isAllSelected: Boolean get() = selectableItems.size == selectedItems.size
}