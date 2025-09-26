package eu.darken.butler.explorer.ui.explorer.dialogs

import eu.darken.butler.common.files.APath
import eu.darken.butler.explorer.core.SortSettings
import eu.darken.butler.explorer.core.engine.ExplorerItem

sealed interface ExplorerDialogState {

    data object None : ExplorerDialogState

    data object CreateItem : ExplorerDialogState

    data class DeleteConfirmation(val items: Set<APath>) : ExplorerDialogState

    data class Rename(val item: APath) : ExplorerDialogState

    data class EditSortOptions(val currentSortSettings: SortSettings) : ExplorerDialogState

    data class FileOptions(val item: ExplorerItem.File) : ExplorerDialogState
}