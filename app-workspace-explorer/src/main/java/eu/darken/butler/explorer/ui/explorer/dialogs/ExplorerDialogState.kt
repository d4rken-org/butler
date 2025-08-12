package eu.darken.butler.explorer.ui.explorer.dialogs

import eu.darken.butler.common.files.APath

sealed interface ExplorerDialogState {
    
    data object None : ExplorerDialogState
    
    data object CreateItem : ExplorerDialogState
    
    data class DeleteConfirmation(
        val items: Set<APath>,
    ) : ExplorerDialogState
    
    data class Rename(
        val item: APath,
    ) : ExplorerDialogState
}