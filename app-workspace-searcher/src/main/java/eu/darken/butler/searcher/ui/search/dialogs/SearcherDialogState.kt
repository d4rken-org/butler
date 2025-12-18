package eu.darken.butler.searcher.ui.search.dialogs

import eu.darken.butler.common.files.APath
import eu.darken.butler.searcher.core.SearchSortSettings
import eu.darken.butler.workspace.core.clipboard.ClipboardClip

sealed interface SearcherDialogState {
    data object None : SearcherDialogState
    data class DeleteConfirmation(
        val paths: Set<APath<*>>,
        val forcePermDelete: Boolean = false,
    ) : SearcherDialogState

    data class ClipboardInfo(val clip: ClipboardClip) : SearcherDialogState
    data class EditSortOptions(val currentSortSettings: SearchSortSettings) : SearcherDialogState
}
