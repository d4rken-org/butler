package eu.darken.butler.explorer.ui.explorer

data class ExplorerSelectionState(
    val selectableItems: Set<String> = emptySet(),
    val selectedItems: Set<String> = emptySet(),
    val hasClipboard: Boolean = false,
) {
    val isSelectionMode: Boolean = selectedItems.isNotEmpty()
    val selectionCount: Int = selectedItems.size
    val isAllSelected: Boolean get() = selectableItems.size == selectedItems.size
}