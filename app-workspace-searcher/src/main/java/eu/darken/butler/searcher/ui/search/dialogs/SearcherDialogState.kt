package eu.darken.butler.searcher.ui.search.dialogs

import eu.darken.butler.common.files.APath
import eu.darken.butler.searcher.core.FilterCondition
import eu.darken.butler.searcher.core.SearchItem
import eu.darken.butler.searcher.core.SearchSortSettings
import eu.darken.butler.workspace.core.clipboard.ClipboardClip

sealed interface SearcherDialogState {
    data object None : SearcherDialogState
    data class DeleteConfirmation(
        val paths: Set<APath<*>>,
        val forcePermDelete: Boolean = false,
    ) : SearcherDialogState

    data object ClearHistoryConfirmation : SearcherDialogState

    data class ClipboardInfo(val clip: ClipboardClip) : SearcherDialogState
    data class EditSortOptions(val currentSortSettings: SearchSortSettings) : SearcherDialogState

    /**
     * Edit size condition - null existing means adding new
     */
    data class EditSizeCondition(
        val existing: FilterCondition.Size? = null,
    ) : SearcherDialogState

    /**
     * Edit date condition - null existing means adding new
     */
    data class EditDateCondition(
        val existing: FilterCondition.ModifiedDate? = null,
    ) : SearcherDialogState

    /**
     * Edit file type condition - null existing means adding new
     */
    data class EditTypeCondition(
        val existing: FilterCondition.Type? = null,
    ) : SearcherDialogState

    data class ShowItemProperties(val result: SearchItem) : SearcherDialogState
}
