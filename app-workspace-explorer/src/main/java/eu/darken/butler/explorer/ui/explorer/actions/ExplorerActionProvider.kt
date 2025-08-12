package eu.darken.butler.explorer.ui.explorer.actions

import eu.darken.butler.explorer.core.engine.ExplorerLocation

interface ExplorerActionProvider {
    
    fun getActions(
        location: ExplorerLocation,
        selectionState: SelectionState,
    ): List<ExplorerAction>
    
    data class SelectionState(
        val selectedItems: Set<String> = emptySet(),
        val hasClipboard: Boolean = false,
    ) {
        val isSelectionMode: Boolean = selectedItems.isNotEmpty()
        val selectionCount: Int = selectedItems.size
    }
}