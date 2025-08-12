package eu.darken.butler.explorer.ui.explorer.actions

import eu.darken.butler.explorer.core.engine.ExplorerLocation

interface ExplorerActionProvider {
    
    fun getActions(
        location: ExplorerLocation,
        selectionState: SelectionState,
        capabilities: LocationCapabilities,
    ): List<ExplorerAction>
    
    data class SelectionState(
        val selectedItems: Set<String> = emptySet(),
        val hasClipboard: Boolean = false,
    ) {
        val isSelectionMode: Boolean = selectedItems.isNotEmpty()
        val selectionCount: Int = selectedItems.size
    }
    
    data class LocationCapabilities(
        val canWrite: Boolean = false,
        val hasRootAccess: Boolean = false,
        val hasAdbAccess: Boolean = false,
    )
}