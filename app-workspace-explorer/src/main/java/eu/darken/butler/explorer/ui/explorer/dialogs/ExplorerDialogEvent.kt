package eu.darken.butler.explorer.ui.explorer.dialogs

import eu.darken.butler.common.files.APath

sealed interface ExplorerDialogEvent {
    
    data object ShowCreateItem : ExplorerDialogEvent
    
    data class ShowDeleteConfirmation(
        val items: Set<APath>,
    ) : ExplorerDialogEvent
    
    data class ShowRename(
        val item: APath,
    ) : ExplorerDialogEvent
    
    data object Dismiss : ExplorerDialogEvent
}